package com.player2.playerengine.tasks.container;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.tasks.crafting.CraftingTableLocator;
import com.player2.playerengine.tasks.crafting.CraftingTableTarget;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;
import com.player2.playerengine.tasks.resources.CollectRecipeCataloguedResourcesTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.util.time.TimerGame;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CraftInTableTask extends ResourceTask {
   private final RecipeTarget[] targets;
   private BlockPos craftingTablePos = null;
   // TABLE-REUSE fix (Issue 2, 2026-06-23): the PlaceBlockNearbyTask we dispatch is saved on the instance
   // (NOT a bare `return new ...` every tick). The Task framework keeps the FIRST-returned sub running as
   // Task.sub (isEqual matches on the block array), so a fresh instance each tick would never get ticked and
   // its getPlaced() would stay null forever -> the just-placed table is never seeded into craftingTablePos
   // -> a SECOND table gets placed. Returning the SAME running instance makes getPlaced() observable, which
   // is what lets us adopt the freshly placed table before the block scanner has indexed it. Mirrors the
   // saved-instance pattern in CraftMacroResourceTask.FIND_OR_PLACE_TABLE so a table placed by EITHER path is
   // recognised and reused by the other (one shared CraftingTableLocator reuse policy).
   private PlaceBlockNearbyTask pendingTablePlaceTask = null;
   private final TimerGame craftTimer = new TimerGame(2.0);
   private boolean isCrafting = false;

   public CraftInTableTask(RecipeTarget[] targets) {
      super(extractItemTargets(targets));
      this.targets = targets;
   }

   public CraftInTableTask(RecipeTarget target) {
      this(new RecipeTarget[]{target});
   }

   private static ItemTarget[] extractItemTargets(RecipeTarget[] recipeTargets) {
      return Arrays.stream(recipeTargets).map(t -> new ItemTarget(t.getOutputItem(), t.getTargetCount())).toArray(ItemTarget[]::new);
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController controller) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController controller) {
      controller.getBehaviour().push();

      for (RecipeTarget target : this.targets) {
         for (ItemTarget ingredient : target.getRecipe().getSlots()) {
            if (ingredient != null && !ingredient.isEmpty()) {
               controller.getBehaviour().addProtectedItems(ingredient.getMatches());
            }
         }
      }
   }

   @Override
   protected Task onResourceTick(PlayerEngineController controller) {
      boolean allDone = Arrays.stream(this.targets)
         .allMatch(targetx -> controller.getItemStorage().getItemCount(targetx.getOutputItem()) >= targetx.getTargetCount());
      if (allDone) {
         return null;
      } else if (!StorageHelper.hasRecipeMaterialsOrTarget(controller, this.targets)) {
         this.setDebugState("Collecting ingredients");
         return new CollectRecipeCataloguedResourcesTask(false, this.targets);
      } else {
         // TABLE-REUSE fix (Issue 2, 2026-06-23): unify onto the shared CraftingTableLocator policy used by
         // CraftMacroResourceTask, replacing the old divergent inline path (raw getNearestBlock with no
         // canReach filter + a throw-away `return new PlaceBlockNearbyTask(...)` every tick). The old code
         // had no reuse radius and never observed getPlaced(), so a table this task placed at one y-level was
         // not reused after the bot moved down, and the macro/this task each placed their own table.
         //
         // Step 1 — seed craftingTablePos from a just-completed place BEFORE any scanner lookup. The block
         // scanner does not index a client-placed block for several ticks, so without this the freshly placed
         // table reads "not found / unreachable" and we would place a SECOND one.
         if (this.pendingTablePlaceTask != null) {
            BlockPos placed = this.pendingTablePlaceTask.getPlaced();
            if (placed != null) {
               if (controller.getWorld().getBlockState(placed).is(Blocks.CRAFTING_TABLE)) {
                  this.craftingTablePos = placed;
               }
               // Whether or not the placed block is actually a crafting table (a failed/rejected placement
               // leaves a stale getPlaced() pointing at the wrong block), drop the saved place-task once it
               // reports a placement. A stale instance must never be re-dispatched in step 5; a genuinely
               // needed re-place builds a FRESH PlaceBlockNearbyTask next tick.
               this.pendingTablePlaceTask = null;
            }
         }

         // Step 2 — invalidate a remembered position that is no longer a crafting table (e.g. it was broken).
         if (this.craftingTablePos != null && !controller.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE)) {
            this.craftingTablePos = null;
         }

         // Step 3 — arm's-reach locate (remembered pos honoured first, then nearest scanned table). On success
         // craftingTablePos is within REACH=3.5 AND canReach, so we fall straight through to the crafting
         // branch below — no separate distance check needed for this path.
         Optional<CraftingTableTarget> reachable = CraftingTableLocator.findReachable(controller, this.craftingTablePos);
         if (reachable.isPresent()) {
            this.craftingTablePos = reachable.get().pos();
            // fall through to the approach-or-craft block below.
         } else {
            // Step 4 — wider REUSE search: walk to a pre-existing pathable table within the reuse radius
            // (default 48 = 3 chunks) instead of placing a new one. The pathability filter
            // (findReusableNearby -> WorldHelper.canReach) keeps an unreachable/elevated table from being
            // chosen; if a chosen table turns out unreachable mid-approach, next tick findReachable and
            // findReusableNearby both return empty (negative-reach cache) and we fall through to place our own.
            Optional<BlockPos> reusable = CraftingTableLocator
               .findReusableNearby(controller, controller.getModSettings().getCraftingTableReuseRadius());
            if (reusable.isPresent()) {
               this.craftingTablePos = reusable.get();
               this.setDebugState("Walking to existing crafting table at: " + this.craftingTablePos.toShortString());
               return new GetCloseToBlockTask(this.craftingTablePos);
            }

            // Step 5 — no usable table: place from inventory (saved instance, see field comment) or obtain one.
            this.craftingTablePos = null;
            if (controller.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
               this.setDebugState("Placing crafting table.");
               if (this.pendingTablePlaceTask == null) {
                  this.pendingTablePlaceTask = new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE);
               }
               return this.pendingTablePlaceTask;
            } else {
               this.setDebugState("Obtaining crafting table.");
               return TaskCatalogue.getItemTask(Items.CRAFTING_TABLE, 1);
            }
         }

         // Approach-or-craft: findReachable above guarantees arm's reach for the default (prefer-local)
         // setting; this guard also covers the non-prefer-local path (where findReachable returns the nearest
         // table without a reach check) by walking to it before crafting.
         if (!this.craftingTablePos
            .closerThan(
               new Vec3i((int)controller.getEntity().position().x, (int)controller.getEntity().position().y, (int)controller.getEntity().position().z), 3.5
            )) {
            this.setDebugState("Going to crafting table at: " + this.craftingTablePos.toShortString());
            return new GetCloseToBlockTask(this.craftingTablePos);
         } else {
            this.setDebugState("Crafting...");
            if (!this.isCrafting) {
               this.craftTimer.reset();
               this.isCrafting = true;
            }

            if (!this.craftTimer.elapsed()) {
               return null;
            } else {
               for (RecipeTarget target : this.targets) {
                  int currentAmount = controller.getItemStorage().getItemCount(target.getOutputItem());
                  if (currentAmount < target.getTargetCount()) {
                     int craftsNeeded = (int)Math.ceil((double)(target.getTargetCount() - currentAmount) / target.getRecipe().outputCount());

                     for (int i = 0; i < craftsNeeded; i++) {
                        if (!StorageHelper.hasRecipeMaterialsOrTarget(
                           controller, new RecipeTarget(target.getOutputItem(), target.getRecipe().outputCount(), target.getRecipe())
                        )) {
                           Debug.logWarning("Not enough ingredients to craft, even though the check passed. Aborting.");
                           return new CollectRecipeCataloguedResourcesTask(false, this.targets);
                        }

                        LivingEntityInventory inventory = ((IInventoryProvider)controller.getEntity()).getLivingInventory();

                        for (ItemTarget ingredient : target.getRecipe().getSlots()) {
                           if (ingredient != null && !ingredient.isEmpty()) {
                              inventory.remove(stack -> ingredient.matches(stack.getItem()), ingredient.getTargetCount(), inventory);
                           }
                        }

                        ItemStack result = new ItemStack(target.getOutputItem(), target.getRecipe().outputCount());
                        inventory.insertStack(result);
                        controller.getItemStorage().registerSlotAction();
                     }
                  }
               }

               controller.getEntity().swing(InteractionHand.MAIN_HAND);
               return null;
            }
         }
      }
   }

   @Override
   protected void onResourceStop(PlayerEngineController controller, Task interruptTask) {
      controller.getBehaviour().pop();
      // CACHED-INSTANCE RESET: isEqualResource compares only `targets`, so the framework may REUSE this same
      // instance for a later craft. Clear the remembered table and the saved place-task so a phantom position
      // (or a stale getPlaced() from a table no longer standing) is never adopted on the next run. Mirrors
      // CraftMacroResourceTask.onResourceStop nulling both.
      this.craftingTablePos = null;
      this.pendingTablePlaceTask = null;
      this.isCrafting = false;
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CraftInTableTask task ? Arrays.equals((Object[])task.targets, (Object[])this.targets) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Craft on table: " + Arrays.toString(Arrays.stream(this.targets).map(t -> t.getOutputItem().getDescription().getString()).toArray());
   }

   public RecipeTarget[] getRecipeTargets() {
      return this.targets;
   }
}
