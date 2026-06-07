package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class CraftingTableLocator {
   private static final double REACH = 3.5;

   private CraftingTableLocator() {
   }

   public static Optional<CraftingTableTarget> findReachable(PlayerEngineController mod, BlockPos remembered) {
      if (remembered != null && mod.getWorld().getBlockState(remembered).is(Blocks.CRAFTING_TABLE)) {
         if (isReachable(mod, remembered)) {
            return Optional.of(new CraftingTableTarget(remembered, false));
         }
      }
      if (mod.getModSettings().isPreferLocalCraftingTable()) {
         Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
         if (nearest.isPresent() && isReachable(mod, nearest.get())) {
            return Optional.of(new CraftingTableTarget(nearest.get(), false));
         }
      } else {
         Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
         if (nearest.isPresent()) {
            return Optional.of(new CraftingTableTarget(nearest.get(), false));
         }
      }
      return Optional.empty();
   }

   public static boolean isReachable(PlayerEngineController mod, BlockPos pos) {
      return pos.closerToCenterThan(mod.getPlayer().position(), REACH) && WorldHelper.canReach(mod, pos);
   }

   public static Task placeNearbyTask() {
      return new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE);
   }

   public static Task obtainTableItemTask(PlayerEngineController mod) {
      if (findReachable(mod, null).isPresent()) {
         return null;
      }
      if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
         return null;
      }
      return null;
   }
}
