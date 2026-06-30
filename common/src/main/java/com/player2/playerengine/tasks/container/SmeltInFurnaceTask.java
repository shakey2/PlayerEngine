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

public class SmeltInFurnaceTask extends ResourceTask {
   /** Vanilla furnace cook duration per item, in game ticks. Handed RAW to {@link FuelPlanner}. */
   private static final int FURNACE_COOK_TICKS = 200;
   private static final FuelPlanner FUEL_PLANNER = new FuelPlanner();

   private final SmeltTarget[] targets;
   private final TimerGame smeltTimer = new TimerGame(10.0);
   private BlockPos furnacePos = null;
   private boolean isSmelting = false;
   // CACHED-INSTANCE CONTRACT: isEqualResource compares only `targets`, so the Task framework keeps ONE
   // cached SmeltInFurnaceTask while the wrapper (CollectIronIngotTask/CollectGoldIngotTask) re-constructs
   // an equal candidate every tick. The mutable state below (single fuel-gather latch + chosen plan)
   // therefore persists for the whole batch on that cached instance. isEqualResource MUST NOT start
   // comparing these fields — doing so would change task identity mid-batch, reset the latch, and re-spiral.
   private boolean fuelGatherAttempted = false;
   // Whether a fuel gather actually ran (candidate found AND resolved to a real task). Drives the
   // truthful terminal message: "gathered but still short" vs "no fuel type available to gather".
   private boolean fuelGatherRan = false;
   // GATHER-SUSTAIN state (Issue 1, 2026-06-23): the old code latched fuelGatherAttempted=true on the SAME
   // tick it issued the gather, so on the NEXT tick (plan() still empty because the gather has not run yet)
   // it fell straight to the terminal "gathered fuel but still short" before the gather did any work. We now
   // keep the gather subtask alive (return the SAME instance each tick) until it self-finishes, and only then
   // re-check plan() and fire the terminal if fuel is still short. The framework keeps the first-returned sub
   // running as Task.sub; we hold the reference here so we can poll isFinished()/stopped() on it without
   // down-casting getSub().
   private boolean fuelGatherInProgress = false;
   private Task fuelGatherTask = null;
   private FuelPlan currentPlan = null;
   // SCANNER-LAG seed (Issue 1, 2026-06-23): the PlaceBlockNearbyTask we dispatch to place a furnace is saved
   // on the instance (NOT a bare `return new ...` each tick) for the same reason CraftInTableTask saves its
   // table place-task — the block scanner does not index a just-placed furnace for several ticks, so without
   // seeding furnacePos from getPlaced() the next tick reads "no furnace found" and dispatches a SECOND place.
   private PlaceBlockNearbyTask pendingFurnacePlaceTask = null;

   public SmeltInFurnaceTask(SmeltTarget... targets) {
      super(extractItemTargets(targets));
      this.targets = targets;
   }

   public SmeltInFurnaceTask(SmeltTarget target) {
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
      controller.getBehaviour().addProtectedItems(Items.FURNACE);

      for (SmeltTarget target : this.targets) {
         controller.getBehaviour().addProtectedItems(target.getMaterial().getMatches());
      }
   }

   @Override
   protected Task onResourceTick(PlayerEngineController controller) {
      boolean allDone = Arrays.stream(this.targets)
         .allMatch(target -> controller.getItemStorage().getItemCount(target.getItem()) >= target.getItem().getTargetCount());
      if (allDone) {
         this.setDebugState("Done smelting.");
         return null;
      } else {
         SmeltTarget currentTarget = null;

         for (SmeltTarget target : this.targets) {
            if (controller.getItemStorage().getItemCount(target.getItem()) < target.getItem().getTargetCount()) {
               currentTarget = target;
               break;
            }
         }

         if (currentTarget == null) {
            Debug.logWarning("Smelting task is running, but all targets are met. This should not happen.");
            return null;
         } else {
            this.smeltTimer.setInterval(10 * currentTarget.getItem().getTargetCount());
            int batchCount = currentTarget.getItem().getTargetCount();
            // FURNACE-FIRST (Issue 1, 2026-06-23): acquire the furnace BEFORE the fuel gate, so a missing
            // furnace is not misattributed to a fuel shortfall (the old order ran the fuel terminal first and
            // never reached furnace acquisition). Runs every tick regardless of isSmelting, preserving the
            // furnace-destroyed-mid-smelt path. Returns non-null only when an obtain/place task must run.
            Task furnaceAcquire = this.findOrPlaceFurnace(controller);
            if (furnaceAcquire != null) {
               return furnaceAcquire;
            }
            if (!this.isSmelting) {
               if (controller.getItemStorage().getItemCount(currentTarget.getMaterial()) < currentTarget.getMaterial().getTargetCount()) {
                  this.setDebugState("Collecting materials for smelting: " + currentTarget.getMaterial());
                  return TaskCatalogue.getItemTask(currentTarget.getMaterial());
               }

               // Fuel gate in SMELT-OPERATIONS via FuelPlanner (raw cook-ticks; no coal-count ever). A
               // present plan covers the WHOLE batch from one fuel type (charcoal->coal->planks->logs)
               // and names the chosen fuel item + exact unit count for the LOAD step below.
               // planResolved tracks whether a usable fuel plan exists THIS tick (fresh plan() OR the
               // post-gather re-plan). We drive the gather/terminal decision off this local — NOT the
               // persistent currentPlan field — so a currentPlan left set by an earlier refuel cycle never
               // suppresses the gather/terminal on a genuinely fuel-empty tick.
               boolean planResolved = false;
               Optional<FuelPlan> plan = FUEL_PLANNER.plan(batchCount, FURNACE_COOK_TICKS, controller, null);
               if (plan.isPresent()) {
                  this.currentPlan = plan.get();
                  planResolved = true;
               } else if (this.fuelGatherInProgress) {
                  // GATHER-SUSTAIN (Issue 1, 2026-06-23): a gather subtask was issued on an earlier tick and
                  // plan() is still empty. Do NOT fall through to the terminal yet — keep the SAME gather
                  // instance alive until it self-finishes, otherwise we give up before the gather has run
                  // (the original one-tick give-up bug). isFinished()==true means the gather met its fuel
                  // targets (success); stopped()==true without finished means it gave up (e.g. nothing
                  // sourceable). Anything else means it is still working — sustain it.
                  if (this.fuelGatherTask != null && this.fuelGatherTask.isFinished()) {
                     // Gather succeeded: fuel is now held. Clear the in-progress flag and re-plan THIS tick
                     // (getItemStorage() reflects the gather's inventory writes immediately, same as every
                     // other slot action). If a plan now exists we fall through to smelting; if it somehow
                     // still does not, the terminal below fires truthfully (gather ran but still short).
                     this.fuelGatherInProgress = false;
                     this.fuelGatherTask = null;
                     Optional<FuelPlan> rePlan = FUEL_PLANNER.plan(batchCount, FURNACE_COOK_TICKS, controller, null);
                     if (rePlan.isPresent()) {
                        this.currentPlan = rePlan.get();
                        planResolved = true;
                     }
                  } else if (this.fuelGatherTask != null && this.fuelGatherTask.stopped()) {
                     // Gather gave up without meeting its targets: fall through to the terminal.
                     this.fuelGatherInProgress = false;
                     this.fuelGatherTask = null;
                  } else {
                     // Still gathering: return the SAME instance so the framework keeps ticking it.
                     this.setDebugState("Collecting fuel...");
                     return this.fuelGatherTask;
                  }
               }

               // Re-evaluate after the gather-sustain block: planResolved is true iff a usable plan exists
               // THIS tick. If so we proceed to smelting; otherwise we either issue the first gather or fire
               // the terminal. (If the gather is still in progress we already returned it above.)
               if (!planResolved) {
                  // Plan empty: not enough of any single fuel type held. Try ONE gather pass for the
                  // smallest-deficit acquirable fuel (deficitFor encodes the no-spiral policy: coal rung
                  // only with a held wood pickaxe, else planks/logs). The fuelGatherAttempted latch lives
                  // on the cached instance so this runs exactly once per batch. A gather actually RAN only
                  // when deficitFor returned a candidate AND that candidate resolved to a real task; we
                  // track that so the terminal message below tells both audiences the truthful subcase.
                  if (!this.fuelGatherAttempted) {
                     this.fuelGatherAttempted = true;
                     Optional<DeficitCandidate> deficit = FUEL_PLANNER.deficitFor(batchCount, FURNACE_COOK_TICKS, controller, null);
                     if (deficit.isPresent()) {
                        DeficitCandidate cand = deficit.get();
                        // Resolve via FuelPlanner so log species with no Item-keyed task fall back to the
                        // generic "log"/"planks" catalogue entry instead of silently returning null.
                        Task gather = FuelPlanner.gatherTaskFor(cand);
                        if (gather != null) {
                           this.fuelGatherRan = true;
                           // SUSTAIN the gather: save the instance and mark in-progress so subsequent ticks
                           // keep ticking it (above) instead of giving up before it has done any work.
                           this.fuelGatherInProgress = true;
                           this.fuelGatherTask = gather;
                           this.setDebugState("Collecting fuel: " + cand.item());
                           return gather;
                        }
                     }
                  }
                  // Single gather attempt already spent (or no acquirable fuel) and plan STILL empty ->
                  // terminal fuel shortfall. Report truthfully to BOTH audiences and self-stop so the
                  // wrapper completes the get-step (never idle-spin on unsourceable fuel).
                  int smelted = controller.getItemStorage().getItemCount(currentTarget.getItem());
                  String itemName = currentTarget.getItem().getMatches()[0].getDescription().getString();
                  // Distinguish the two truthful subcases: (a) we DID gather fuel but it still wasn't
                  // enough, vs (b) no acquirable fuel type existed to even try (coal needs a pickaxe;
                  // no logs/planks reachable). Saying "couldn't gather more" when no gather ran is a
                  // truthfulness bug for the model.
                  Component playerTailComp = this.fuelGatherRan
                     ? Component.translatable("message.playerengine.furnace.partial_tail_gathered")
                     : Component.translatable("message.playerengine.furnace.partial_tail_nofuel");
                  String modelTail = this.fuelGatherRan
                     ? ", gathered fuel but still short"
                     : ", no acquirable fuel type (coal needs a pickaxe; no logs/planks reachable)";
                  // Player channel (milestone bypasses the throttle); distinct human string.
                  controller.reportAgenticProgress(
                     Component.translatable("message.playerengine.furnace.partial", smelted, batchCount, itemName, playerTailComp), true);
                  // Model channel (distinct machine string), aggregated up the wrapper by ResourceTask.getFailureReason().
                  this.recordFailureReason(
                     "smelt incomplete: smelted " + smelted + "/" + batchCount + " " + itemName + modelTail);
                  this.stop(this);
                  return null;
               }
            }

            // Furnace acquisition is handled by findOrPlaceFurnace() at the TOP of this tick (before the fuel
            // gate), so by the time control reaches here furnacePos is guaranteed non-null and valid — the
            // old inline locate-or-place block here is dead and was removed. The mid-smelt furnace-destroyed
            // case is covered too: findOrPlaceFurnace() runs every tick regardless of isSmelting.
            if (!this.furnacePos
               .closerThan(
                  new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z), 4.5
               )) {
               this.setDebugState("Going to furnace.");
               return new GetCloseToBlockTask(this.furnacePos);
            } else if (controller.getWorld().getBlockEntity(this.furnacePos) instanceof AbstractFurnaceBlockEntity furnace) {
               ItemStack outputStack = furnace.getItem(2);
               if (!outputStack.isEmpty()) {
                  this.setDebugState("Taking smelted items.");
                  LivingEntityInventory playerInv = ((IInventoryProvider)controller.getEntity()).getLivingInventory();
                  if (!playerInv.insertStack(outputStack)) {
                     this.setDebugState("Inventory is full, cannot take smelted items.");
                     return null;
                  }

                  furnace.setItem(2, ItemStack.EMPTY);
                  furnace.setChanged();
               }

               if (this.isSmelting) {
                  this.setDebugState("Waiting for items to smelt...");
                  if (this.smeltTimer.elapsed()) {
                     this.isSmelting = false;
                  }

                  return null;
               } else {
                  ItemStack materialSlot = furnace.getItem(0);
                  ItemStack fuelSlot = furnace.getItem(1);
                  LivingEntityInventory playerInv = ((IInventoryProvider)controller.getEntity()).getLivingInventory();
                  // Refuel whenever the fuel slot is empty (drop the litTime AND-clause) so batches larger
                  // than one fuel item's worth refuel mid-batch. Load the PLAN-chosen fuel item (NOT
                  // getSupportedFuelItems()[0]), accumulating up to the planned unit count across ALL
                  // inventory slots holding it.
                  // INCREMENTAL BY DESIGN: a furnace fuel slot holds at most one stack (64), so we cap the
                  // load at 64 here. When the plan needs more than a stack (e.g. hundreds of planks at 300
                  // ticks each), this slot burns down and the empty-slot guard above re-enters next cycle to
                  // top it up from the SAME currentPlan.fuelCount() — refuelling is therefore per-cycle, not
                  // a single shot. Do NOT raise this cap to load >64 into one slot (vanilla rejects it).
                  if (fuelSlot.isEmpty() && this.currentPlan != null) {
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
                        furnace.setItem(1, loaded);
                        furnace.setChanged();
                     }
                  }

                  if (materialSlot.isEmpty()) {
                     this.setDebugState("Adding material.");
                     Item materialItem = currentTarget.getMaterial().getMatches()[0];
                     int materialSlotIndex = playerInv.getSlotWithStack(new ItemStack(materialItem));
                     if (materialSlotIndex != -1) {
                        furnace.setItem(0, playerInv.removeItem(materialSlotIndex, currentTarget.getMaterial().getTargetCount()));
                        this.isSmelting = true;
                        this.smeltTimer.reset();
                        furnace.setChanged();
                        return null;
                     }
                  }

                  this.isSmelting = true;
                  this.smeltTimer.reset();
                  this.setDebugState("Waiting for furnace...");
                  return null;
               }
            } else {
               Debug.logWarning("Block at furnace position is not a furnace BE. Resetting.");
               this.furnacePos = null;
               return new TimeoutWanderTask(1.0F);
            }
         }
      }
   }

   /**
    * Furnace find-or-place (Issue 1, 2026-06-23). Extracted so it can run at the TOP of onResourceTick,
    * BEFORE the fuel gate, so a missing furnace is acquired as part of the batch instead of being pre-empted
    * by a premature fuel-shortfall terminal. Returns the obtain/place task to dispatch, or null when a usable
    * furnace is already set (in which case the existing furnace block below re-validates and interacts).
    *
    * <p>This runs regardless of isSmelting state (matching the original block's outer position), so the
    * furnace-destroyed-mid-smelt case (player picks up the furnace while isSmelting=true) is still handled:
    * furnacePos is invalidated and a replacement is dispatched rather than navigating to a non-existent block.
    */
   private Task findOrPlaceFurnace(PlayerEngineController controller) {
      // SCANNER-LAG seed: adopt a freshly placed furnace from the saved place-task BEFORE any scanner lookup,
      // so we never dispatch a SECOND place while the block scanner has not yet indexed the first (mirrors
      // CraftInTableTask's pendingTablePlaceTask handling).
      if (this.pendingFurnacePlaceTask != null) {
         BlockPos placed = this.pendingFurnacePlaceTask.getPlaced();
         if (placed != null) {
            if (controller.getWorld().getBlockState(placed).is(Blocks.FURNACE)) {
               this.furnacePos = placed;
            }
            // Drop the saved place-task once it reports a placement (even a rejected one with a stale
            // getPlaced()); a genuinely needed re-place builds a FRESH instance below.
            this.pendingFurnacePlaceTask = null;
         }
      }

      if (this.furnacePos != null && controller.getWorld().getBlockState(this.furnacePos).is(Blocks.FURNACE)) {
         return null;
      }
      Optional<BlockPos> nearestFurnace = controller.getBlockScanner().getNearestBlock(Blocks.FURNACE);
      if (nearestFurnace.isPresent()
         && !nearestFurnace.get()
            .closerThan(
               new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z),
               100.0
            )) {
         nearestFurnace = Optional.empty();
      }

      if (!nearestFurnace.isPresent()) {
         this.furnacePos = null;
         if (controller.getItemStorage().hasItem(Items.FURNACE)) {
            this.setDebugState("Placing furnace.");
            if (this.pendingFurnacePlaceTask == null) {
               this.pendingFurnacePlaceTask = new PlaceBlockNearbyTask(Blocks.FURNACE);
            }
            return this.pendingFurnacePlaceTask;
         }

         this.setDebugState("Obtaining furnace.");
         return TaskCatalogue.getItemTask(Items.FURNACE, 1);
      }

      this.furnacePos = nearestFurnace.get();
      return null;
   }

   @Override
   protected void onResourceStop(PlayerEngineController controller, Task interruptTask) {
      controller.getBehaviour().pop();
      // CACHED-INSTANCE RESET: isEqualResource compares only `targets`, so the Task framework may REUSE this
      // same SmeltInFurnaceTask instance for a later batch (incl. a re-issued @smelt of the same target). If
      // we do not clear the per-batch state here, the next batch starts with fuelGatherAttempted=true (gather
      // skipped) and a stopped fuelGatherTask still in-progress, firing a FALSE "still short" terminal on its
      // first tick. Clear every per-batch field — the new gather-sustain fields, the pre-existing fuel latches,
      // the chosen plan, and the remembered/just-placed furnace.
      this.fuelGatherAttempted = false;
      this.fuelGatherRan = false;
      this.fuelGatherInProgress = false;
      this.fuelGatherTask = null;
      this.currentPlan = null;
      this.pendingFurnacePlaceTask = null;
      this.furnacePos = null;
      this.isSmelting = false;
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof SmeltInFurnaceTask task ? Arrays.equals((Object[])task.targets, (Object[])this.targets) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Smelting in Furnace";
   }
}
