package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.CraftInInventoryTask;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.trackers.storage.ItemStorageTracker;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.Dimension;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.world.item.Item;

public class CollectPlanksTask extends ResourceTask {
   private final Item[] planks;
   private final Item[] logs;
   // Logs the task may COUNT/CONVERT from already-held stock when deciding "do I have enough planks".
   // Deliberately decoupled from {@link #logs} (the MINE/SCAN target): the bot must only go out to mine
   // natural trees/fungi (NATURAL_LOG), but it may still USE *_wood/bark or stripped_* it already holds.
   // For the wood-specific constructors heldLogs == logs (unchanged behavior); only the generic
   // (no-specific-wood) constructor widens heldLogs to the full ItemHelper.LOG set.
   private final Item[] heldLogs;
   private final int targetCount;
   private boolean logsInNether;

   public CollectPlanksTask(Item[] planks, Item[] logs, int count, boolean logsInNether) {
      this(planks, logs, logs, count, logsInNether);
   }

   public CollectPlanksTask(Item[] planks, Item[] logs, Item[] heldLogs, int count, boolean logsInNether) {
      super(new ItemTarget(planks, count));
      this.planks = planks;
      this.logs = logs;
      this.heldLogs = heldLogs;
      this.targetCount = count;
      this.logsInNether = logsInNether;
   }

   public CollectPlanksTask(int count) {
      // Generic (no specific wood) plank collection. Two distinct log sets:
      //   - MINE/SCAN target = NATURAL_LOG: the bot only goes out to mine real trees/fungi, never
      //     *_wood/bark or stripped_* (player-built / structure blocks).
      //   - HELD-STOCK / craft input = ItemHelper.LOG (broad): held wood/bark/stripped stock STILL counts
      //     toward "do I have enough planks" and is converted in place (the bot may USE what it already
      //     holds; it just must not SEEK those variants in the world). Restores the held-stock counting
      //     that narrowing the mine target alone would have regressed.
      this(ItemHelper.PLANKS, ItemHelper.NATURAL_LOG, ItemHelper.LOG, count, false);
   }

   public CollectPlanksTask(Item plank, Item log, int count) {
      this(new Item[]{plank}, new Item[]{log}, count, false);
   }

   public CollectPlanksTask(Item plank, int count) {
      this(plank, ItemHelper.planksToLog(plank), count);
   }

   private static CraftingRecipe generatePlankRecipe(Item[] logs) {
      return CraftingRecipe.newShapedRecipe("planks", new Item[][]{logs, null, null, null}, 4);
   }

   @Override
   protected double getPickupRange(PlayerEngineController mod) {
      ItemStorageTracker storage = mod.getItemStorage();
      return storage.getItemCount(ItemHelper.LOG) * 4 > this.targetCount ? 10.0 : 50.0;
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController mod) {
   }

   @Override
   protected Task onResourceTick(PlayerEngineController mod) {
      // Held-stock accounting + in-place conversion use the broad heldLogs set (the bot may convert
      // *_wood/bark/stripped it already holds), independent of the narrower this.logs MINE/SCAN target.
      int totalInventoryPlankCount = mod.getItemStorage().getItemCount(this.planks);
      int potentialPlanks = totalInventoryPlankCount + mod.getItemStorage().getItemCount(this.heldLogs) * 4;
      if (potentialPlanks >= this.targetCount) {
         for (Item logCheck : this.heldLogs) {
            int count = mod.getItemStorage().getItemCount(logCheck);
            if (count > 0) {
               Item plankCheck = ItemHelper.logToPlanks(logCheck);
               if (plankCheck == null) {
                  Debug.logError("Invalid/Un-convertable log: " + logCheck + " (failed to find corresponding plank)");
               }

               int plankCount = mod.getItemStorage().getItemCount(plankCheck);
               int otherPlankCount = totalInventoryPlankCount - plankCount;
               int targetTotalPlanks = Math.min(count * 4 + plankCount, this.targetCount - otherPlankCount);
               this.setDebugState("We have " + logCheck + ", crafting " + targetTotalPlanks + " planks.");
               return new CraftInInventoryTask(new RecipeTarget(plankCheck, targetTotalPlanks, generatePlankRecipe(this.heldLogs)));
            }
         }
      }

      ArrayList<ItemTarget> blocksTomine = new ArrayList<>(2);
      blocksTomine.add(new ItemTarget(this.logs, mod.getItemStorage().getItemCount(this.logs) + 1));
      if (!mod.getBehaviour().exclusivelyMineLogs()) {
      }

      MineAndCollectTask mineAndCollectTask = new MineAndCollectTask(blocksTomine.toArray(ItemTarget[]::new), MiningRequirement.HAND);
      if (this.logsInNether) {
         mineAndCollectTask.forceDimension(Dimension.NETHER);
      }

      return mineAndCollectTask;
   }

   @Override
   protected void onResourceStop(PlayerEngineController mod, Task interruptTask) {
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CollectPlanksTask;
   }

   @Override
   protected String toDebugStringName() {
      return "Crafting " + this.targetCount + " planks " + Arrays.toString((Object[])this.planks);
   }

   public CollectPlanksTask logsInNether() {
      this.logsInNether = true;
      return this;
   }
}
