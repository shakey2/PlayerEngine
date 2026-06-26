package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import java.util.Arrays;
import java.util.HashMap;
import net.minecraft.world.item.Item;
import org.apache.commons.lang3.ArrayUtils;

public class CollectRecipeCataloguedResourcesTask extends Task {
   private final RecipeTarget[] targets;
   private final boolean ignoreUncataloguedSlots;
   private boolean finished = false;
   private String failureReason = null;

   public CollectRecipeCataloguedResourcesTask(boolean ignoreUncataloguedSlots, RecipeTarget... targets) {
      this.targets = targets;
      this.ignoreUncataloguedSlots = ignoreUncataloguedSlots;
   }

   @Override
   protected void onStart() {
      this.finished = false;
      this.failureReason = null;
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      HashMap<String, Integer> catalogueCount = new HashMap<>();
      HashMap<Item, Integer> itemCount = new HashMap<>();

      // Once a genuine failure has been recorded, never re-enter the target loop below — doing so
      // would return a fresh gather task (the items were never collected, so the target is still
      // unmet) and re-spawn the stopped sub-chain. The CraftInInventoryTask interception fires on
      // the next tick to stop the chain cleanly; until then we must short-circuit to a no-op finish.
      if (this.failureReason != null) {
         this.finished = true;
         return null;
      }

      // Stopped-child failure guard: if the previously-dispatched sub has self-stopped with a
      // genuine failure (stopped but not finished), propagate the reason and signal completion
      // so the caller (CraftInInventoryTask) can intercept and stop cleanly. Without this guard
      // the framework restarts the stopped sub from first=true on the next tick (because onTick()
      // re-returns the same isEqual candidate), wiping its sub-chain and re-spawning a fresh
      // SmeltInFurnaceTask leaf (clean fuel-gather latch) every tick -> ~10 Hz infinite spin.
      // Must run BEFORE the target loop below: a guard placed after a return inside the loop is dead.
      Task cachedSub = this.getSub();
      if (cachedSub != null && cachedSub.stopped() && !cachedSub.isFinished()) {
         // Record the leaf's reason when present; otherwise stamp a sentinel so failureReason is
         // never left null on a genuine stopped-child failure. A null reason here would let
         // isFinished()'s safety-reset branch flip finished back to false next tick (the target is
         // unmet), re-entering this loop and re-spawning the sub — the exact spin this guard prevents.
         String reason = null;
         if (cachedSub instanceof ResourceTask rt) {
            reason = rt.getAggregatedFailureReason().orElse(null);
         }
         this.failureReason = (reason != null) ? reason : "sub-task failed";
         this.finished = true;
         return null;
      }

      for (RecipeTarget target : this.targets) {
         if (target != null) {
            int weNeed = target.getTargetCount() - mod.getItemStorage().getItemCount(target.getOutputItem());
            if (weNeed > 0) {
               CraftingRecipe recipe = target.getRecipe();

               for (int i = 0; i < recipe.getSlotCount(); i++) {
                  ItemTarget slot = recipe.getSlot(i);
                  if (slot != null && !slot.isEmpty()) {
                     int numberOfRepeats = (int)Math.floor(-0.1 + (double)weNeed / target.getRecipe().outputCount()) + 1;
                     if (!slot.isCatalogueItem()) {
                        if (slot.getMatches().length != 1) {
                           if (!this.ignoreUncataloguedSlots) {
                              Debug.logWarning(
                                 "Recipe collection for recipe "
                                    + recipe
                                    + " slot "
                                    + i
                                    + " is not catalogued. Please define an explicit collectRecipeSubTask() function for this item target:"
                                    + slot
                              );
                           }
                        } else {
                           Item item = slot.getMatches()[0];
                           itemCount.put(item, itemCount.getOrDefault(item, 0) + numberOfRepeats);
                        }
                     } else {
                        String targetName = slot.getCatalogueName();
                        catalogueCount.put(targetName, catalogueCount.getOrDefault(targetName, 0) + numberOfRepeats);
                     }
                  }
               }
            }
         }
      }

      for (String catalogueMaterialName : catalogueCount.keySet()) {
         int count = catalogueCount.get(catalogueMaterialName);
         ItemTarget itemTarget = new ItemTarget(catalogueMaterialName, count);
         if (count > 0 && !StorageHelper.itemTargetsMet(mod, itemTarget)) {
            this.setDebugState("Getting " + itemTarget);
            return TaskCatalogue.getItemTask(catalogueMaterialName, count);
         }
      }

      for (Item item : itemCount.keySet()) {
         int count = itemCount.get(item);
         if (count > 0 && mod.getItemStorage().getItemCount(item) < count) {
            this.setDebugState("Getting " + item.getDescriptionId());
            return TaskCatalogue.getItemTask(item, count);
         }
      }

      this.finished = true;
      return null;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof CollectRecipeCataloguedResourcesTask task ? Arrays.equals((Object[])task.targets, (Object[])this.targets) : false;
   }

   @Override
   protected String toDebugString() {
      return "Collect Recipe Resources: " + ArrayUtils.toString(this.targets);
   }

   @Override
   public boolean isFinished() {
      // When a genuine failure has been recorded by the stopped-child guard, treat this task as
      // permanently finished regardless of item state. The safety-reset below must NOT fire in this
      // case — it would undo the finished=true set by the guard (the items were never gathered, so
      // hasRecipeMaterialsOrTarget() returns false), creating a per-tick toggle that re-spawns the
      // collect sub forever and prevents clean termination. This bypass MUST be the first statement.
      if (this.failureReason != null) {
         return true;
      }

      if (this.finished && !StorageHelper.hasRecipeMaterialsOrTarget(this.controller, this.targets)) {
         this.finished = false;
         Debug.logMessage("Invalid collect recipe \"finished\" state, resetting.");
      }

      return this.finished;
   }

   public String getFailureReason() {
      return this.failureReason;
   }
}
