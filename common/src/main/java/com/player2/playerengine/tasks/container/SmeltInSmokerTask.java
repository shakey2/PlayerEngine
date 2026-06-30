package com.player2.playerengine.tasks.container;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.mixins.MixinAbstractFurnaceBlockEntity;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.tasks.cooking.FuelPlanner;
import com.player2.playerengine.tasks.cooking.FuelPlanner.DeficitCandidate;
import com.player2.playerengine.tasks.cooking.FuelPlanner.FuelPlan;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.SmeltTarget;
import com.player2.playerengine.util.time.TimerGame;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public class SmeltInSmokerTask extends ResourceTask {
   /** Vanilla smoker cook duration per item, in game ticks (100, NOT 200 — handed RAW to {@link FuelPlanner}). */
   private static final int SMOKER_COOK_TICKS = 100;
   private static final FuelPlanner FUEL_PLANNER = new FuelPlanner();

   private final SmeltTarget[] targets;
   private final TimerGame smeltTimer = new TimerGame(5.0);
   private BlockPos smokerPos = null;
   private boolean isSmelting = false;
   private SmeltInSmokerTask.SmokerCache cache;
   // CACHED-INSTANCE CONTRACT: isEqualResource compares only `targets`, so the Task framework keeps ONE
   // cached SmeltInSmokerTask while a food wrapper re-constructs an equal candidate every tick. The
   // mutable state below (single fuel-gather latch + chosen plan) persists for the whole batch on that
   // cached instance. isEqualResource MUST NOT start comparing these fields (would reset the latch -> re-spiral).
   private boolean fuelGatherAttempted = false;
   // Whether a fuel gather actually ran (candidate found AND resolved to a real task). Drives the
   // truthful terminal message: "gathered but still short" vs "no fuel type available to gather".
   private boolean fuelGatherRan = false;
   private FuelPlan currentPlan = null;

   public SmeltInSmokerTask(SmeltTarget... targets) {
      super(extractItemTargets(targets));
      this.targets = targets;
   }

   public SmeltInSmokerTask(SmeltTarget target) {
      this(new SmeltTarget[]{target});
   }

   private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
      List<ItemTarget> result = new ArrayList<>(recipeTargets.length);

      for (SmeltTarget target : recipeTargets) {
         result.add(target.getItem());
      }

      return result.toArray(ItemTarget[]::new);
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController controller) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController controller) {
      controller.getBehaviour().push();
      controller.getBehaviour().addProtectedItems(Items.SMOKER);

      for (SmeltTarget target : this.targets) {
         controller.getBehaviour().addProtectedItems(target.getMaterial().getMatches());
      }
   }

   @Override
   protected Task onResourceTick(PlayerEngineController controller) {
      boolean allDone = Arrays.stream(this.targets)
         .allMatch(target -> controller.getItemStorage().getItemCount(target.getItem()) >= target.getItem().getTargetCount());
      if (allDone) {
         this.setDebugState("Done smoking.");
         return null;
      } else {
         SmeltTarget currentTarget = Arrays.stream(this.targets)
            .filter(t -> controller.getItemStorage().getItemCount(t.getItem()) < t.getItem().getTargetCount())
            .findFirst()
            .orElse(null);
         if (currentTarget == null) {
            return null;
         } else {
            this.smeltTimer.setInterval(10 * currentTarget.getItem().getTargetCount());
            int batchCount = currentTarget.getItem().getTargetCount();
            if (!this.isSmelting) {
               if (!controller.getItemStorage().hasItem(currentTarget.getMaterial())) {
                  this.setDebugState("Collecting raw food: " + currentTarget.getMaterial());
                  return TaskCatalogue.getItemTask(currentTarget.getMaterial());
               }

               // Fuel gate in SMELT-OPERATIONS via FuelPlanner using the RAW 100-tick smoker value (the
               // exact case the old /200 shortcut got wrong). A present plan covers the WHOLE batch from
               // one fuel type and names the chosen fuel item + exact unit count for the LOAD step.
               Optional<FuelPlan> plan = FUEL_PLANNER.plan(batchCount, SMOKER_COOK_TICKS, controller, null);
               if (plan.isPresent()) {
                  this.currentPlan = plan.get();
               } else {
                  // Plan empty: try ONE gather pass for the smallest-deficit acquirable fuel (deficitFor
                  // encodes the no-spiral policy). The fuelGatherAttempted latch on the cached instance
                  // makes this run exactly once per batch. fuelGatherRan records whether a gather actually
                  // ran (candidate found AND resolved) so the terminal message is truthful per subcase.
                  if (!this.fuelGatherAttempted) {
                     this.fuelGatherAttempted = true;
                     Optional<DeficitCandidate> deficit = FUEL_PLANNER.deficitFor(batchCount, SMOKER_COOK_TICKS, controller, null);
                     if (deficit.isPresent()) {
                        DeficitCandidate cand = deficit.get();
                        // Resolve via FuelPlanner so log species with no Item-keyed task fall back to the
                        // generic "log"/"planks" catalogue entry instead of silently returning null.
                        Task gather = FuelPlanner.gatherTaskFor(cand);
                        if (gather != null) {
                           this.fuelGatherRan = true;
                           this.setDebugState("Collecting fuel: " + cand.item());
                           return gather;
                        }
                     }
                  }
                  // Single gather attempt spent (or no acquirable fuel) and plan STILL empty -> terminal
                  // fuel shortfall. Report truthfully to BOTH audiences and self-stop so the wrapper
                  // completes the get-step (never idle-spin on unsourceable fuel).
                  int smelted = controller.getItemStorage().getItemCount(currentTarget.getItem());
                  String itemName = currentTarget.getItem().getMatches()[0].getDescription().getString();
                  // Distinguish "gathered but still short" from "no acquirable fuel type to even try" so
                  // the model is never told we tried to gather when no gather ran (truthfulness).
                  Component playerTailComp = this.fuelGatherRan
                     ? Component.translatable("message.playerengine.smoker.partial_tail_gathered")
                     : Component.translatable("message.playerengine.smoker.partial_tail_nofuel");
                  String modelTail = this.fuelGatherRan
                     ? ", gathered fuel but still short"
                     : ", no acquirable fuel type (coal needs a pickaxe; no logs/planks reachable)";
                  controller.reportAgenticProgress(
                     Component.translatable("message.playerengine.smoker.partial", smelted, batchCount, itemName, playerTailComp), true);
                  this.recordFailureReason(
                     "smoke incomplete: cooked " + smelted + "/" + batchCount + " " + itemName + modelTail);
                  this.stop(this);
                  return null;
               }
            }

            if (this.smokerPos == null || !controller.getWorld().getBlockState(this.smokerPos).is(Blocks.SMOKER)) {
               Optional<BlockPos> nearestSmoker = controller.getBlockScanner().getNearestBlock(Blocks.SMOKER);
               if (!nearestSmoker.isPresent()) {
                  if (controller.getItemStorage().hasItem(Items.SMOKER)) {
                     this.setDebugState("Placing smoker.");
                     return new PlaceBlockNearbyTask(Blocks.SMOKER);
                  }

                  this.setDebugState("Obtaining smoker.");
                  return TaskCatalogue.getItemTask(Items.SMOKER, 1);
               }

               this.smokerPos = nearestSmoker.get();
            }

            if (!this.smokerPos
               .closerThan(
                  new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z), 4.5
               )) {
               this.setDebugState("Going to smoker.");
               return new GetCloseToBlockTask(this.smokerPos);
            } else if (controller.getWorld().getBlockEntity(this.smokerPos) instanceof AbstractFurnaceBlockEntity smoker) {
               ItemStack outputStack = smoker.getItem(2);
               if (!outputStack.isEmpty()) {
                  this.setDebugState("Taking smoked items.");
                  LivingEntityInventory playerInv = ((IInventoryProvider)controller.getEntity()).getLivingInventory();
                  if (!playerInv.insertStack(outputStack)) {
                     this.setDebugState("Inventory full.");
                     return null;
                  }

                  smoker.setItem(2, ItemStack.EMPTY);
                  smoker.setChanged();
               }

               if (this.isSmelting) {
                  this.setDebugState("Waiting for items to smoke...");
                  if (this.smeltTimer.elapsed()) {
                     this.isSmelting = false;
                  }

                  return null;
               } else {
                  LivingEntityInventory playerInv = ((IInventoryProvider)controller.getEntity()).getLivingInventory();
                  // Refuel whenever the fuel slot is empty (drop the litTime AND-clause) so larger batches
                  // refuel mid-batch. Load the PLAN-chosen fuel item, accumulating up to the planned unit
                  // count across ALL inventory slots holding it.
                  // INCREMENTAL BY DESIGN: the fuel slot holds at most one stack (64), so we cap at 64. When
                  // the plan needs more than a stack, the empty-slot guard re-enters next cycle to top up
                  // from the SAME currentPlan.fuelCount(). Do NOT raise this cap to load >64 into one slot.
                  if (smoker.getItem(1).isEmpty() && this.currentPlan != null) {
                     this.setDebugState("Adding fuel: " + this.currentPlan.fuelItem());
                     Item fuelItem = this.currentPlan.fuelItem();
                     int want = Math.min(this.currentPlan.fuelCount(), 64);
                     ItemStack loaded = ItemStack.EMPTY;
                     while (want > 0) {
                        int fuelSlotIndex = playerInv.getSlotWithStack(new ItemStack(fuelItem));
                        if (fuelSlotIndex == -1) {
                           break;
                        }
                        ItemStack pulled = playerInv.removeItem(fuelSlotIndex, want);
                        if (pulled.isEmpty()) {
                           break;
                        }
                        if (loaded.isEmpty()) {
                           loaded = pulled;
                        } else {
                           loaded.grow(pulled.getCount());
                        }
                        want -= pulled.getCount();
                     }
                     if (!loaded.isEmpty()) {
                        smoker.setItem(1, loaded);
                        smoker.setChanged();
                     }
                  }

                  if (smoker.getItem(0).isEmpty()) {
                     this.setDebugState("Adding raw food.");
                     Item materialItem = currentTarget.getMaterial().getMatches()[0];
                     int materialSlotIndex = playerInv.getSlotWithStack(new ItemStack(materialItem));
                     if (materialSlotIndex != -1) {
                        smoker.setItem(0, playerInv.removeItem(materialSlotIndex, currentTarget.getMaterial().getTargetCount()));
                        this.isSmelting = true;
                        this.smeltTimer.reset();
                        smoker.setChanged();
                        return null;
                     }
                  }

                  this.isSmelting = true;
                  this.smeltTimer.reset();
                  this.setDebugState("Waiting for smoker...");
                  return null;
               }
            } else {
               Debug.logWarning("Block at smoker position is not a smoker BE. Resetting.");
               this.smokerPos = null;
               return new TimeoutWanderTask(1.0F);
            }
         }
      }
   }

   @Override
   protected void onResourceStop(PlayerEngineController controller, Task interruptTask) {
      controller.getBehaviour().pop();
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof SmeltInSmokerTask task ? Arrays.equals((Object[])task.targets, (Object[])this.targets) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Smelting in Smoker";
   }

   static class SmokerCache {
      public ItemStack materialSlot = ItemStack.EMPTY;
      public ItemStack fuelSlot = ItemStack.EMPTY;
      public ItemStack outputSlot = ItemStack.EMPTY;
      public double burningFuelCount;
      public double burnPercentage;
   }
}
