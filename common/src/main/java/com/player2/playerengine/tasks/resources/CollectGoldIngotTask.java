package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.container.CraftInTableTask;
import com.player2.playerengine.tasks.container.SmeltInFurnaceTask;
import com.player2.playerengine.tasks.movement.DefaultGoToDimensionTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.Dimension;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.SmeltTarget;
import com.player2.playerengine.util.helpers.WorldHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class CollectGoldIngotTask extends ResourceTask {
   private final int count;

   public CollectGoldIngotTask(int count) {
      super(Items.GOLD_INGOT, count);
      this.count = count;
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController mod) {
      mod.getBehaviour().push();
   }

   @Override
   protected Task onResourceTick(PlayerEngineController mod) {
      if (WorldHelper.getCurrentDimension(mod) == Dimension.OVERWORLD) {
         // Wrapper terminal propagation: if the cached SmeltInFurnaceTask child self-stopped with a
         // fuel-shortfall reason, copy it onto THIS wrapper and stop rather than re-spawning a fresh leaf
         // that would re-stall (and starve onGetComplete). Read-only `sub` walk via thisOrChildSatisfies.
         String[] childReason = new String[1];
         this.thisOrChildSatisfies(task -> {
            if (task instanceof SmeltInFurnaceTask leaf && leaf.stopped()) {
               leaf.getAggregatedFailureReason().ifPresent(r -> childReason[0] = r);
               return childReason[0] != null;
            }
            return false;
         });
         if (childReason[0] != null) {
            this.recordFailureReason(childReason[0]);
            this.stop(this);
            return null;
         }
         return new SmeltInFurnaceTask(new SmeltTarget(new ItemTarget(Items.GOLD_INGOT, this.count), new ItemTarget(Items.RAW_GOLD, this.count)));
      } else if (WorldHelper.getCurrentDimension(mod) == Dimension.NETHER) {
         int nuggs = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET);
         int nuggs_needed = this.count * 9 - mod.getItemStorage().getItemCount(Items.GOLD_INGOT) * 9;
         if (nuggs >= nuggs_needed) {
            ItemTarget n = new ItemTarget(Items.GOLD_NUGGET);
            CraftingRecipe recipe = CraftingRecipe.newShapedRecipe("gold_ingot", new ItemTarget[]{n, n, n, n, n, n, n, n, n}, 1);
            return new CraftInTableTask(new RecipeTarget(Items.GOLD_INGOT, this.count, recipe));
         } else {
            return new MineAndCollectTask(new ItemTarget(Items.GOLD_NUGGET, this.count * 9), new Block[]{Blocks.NETHER_GOLD_ORE}, MiningRequirement.WOOD);
         }
      } else {
         return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
      }
   }

   @Override
   protected void onResourceStop(PlayerEngineController mod, Task interruptTask) {
      mod.getBehaviour().pop();
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CollectGoldIngotTask && ((CollectGoldIngotTask)other).count == this.count;
   }

   @Override
   protected String toDebugStringName() {
      return "Collecting " + this.count + " gold.";
   }
}
