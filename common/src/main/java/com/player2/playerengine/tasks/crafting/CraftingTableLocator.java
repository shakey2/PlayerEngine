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

   /**
    * TABLE-REUSE search (distinct from {@link #isReachable}'s arm's-reach gate). Finds the nearest
    * pre-existing crafting_table the macro should ADOPT and WALK to instead of placing a new one: it must
    * be a real crafting table within {@code radius} blocks of the bot AND pass a pathability/reachability
    * filter ({@link WorldHelper#canReach}, the same negative-cache the gather subtree honors) so an
    * unreachable / elevated table is not chosen. Returns empty when none qualifies, in which case the
    * caller places its own table. This does NOT imply arm's reach — the caller still walks to the result
    * via MOVE_TO_TABLE, and the bounded approach (MAX_TABLE_APPROACH_STALL_TICKS) guards against a table
    * that turns out unreachable mid-approach.
    *
    * @param radius reuse search radius in blocks (e.g. 48 = 3 chunks), measured from the bot.
    */
   public static Optional<BlockPos> findReusableNearby(PlayerEngineController mod, double radius) {
      Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.CRAFTING_TABLE);
      if (nearest.isEmpty()) {
         return Optional.empty();
      }
      BlockPos pos = nearest.get();
      if (!mod.getWorld().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
         return Optional.empty();
      }
      if (!pos.closerToCenterThan(mod.getPlayer().position(), radius)) {
         return Optional.empty();
      }
      if (!WorldHelper.canReach(mod, pos)) {
         return Optional.empty();
      }
      return Optional.of(pos);
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
