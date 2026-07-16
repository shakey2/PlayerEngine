package com.player2.playerengine.util.helpers;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Lightweight assertions for the automatic mining-source policy.
 *
 * <p>Run manually with assertions enabled:
 * {@code java -ea ...AutomaticMiningSourcePolicySelfTest}.
 */
public final class AutomaticMiningSourcePolicySelfTest {

   private AutomaticMiningSourcePolicySelfTest() {
   }

   public static void main(String[] args) {
      assert AutomaticMiningSourcePolicy.blocksFor(Items.CHEST).length == 0;
      assertOnly(Blocks.OAK_LOG, AutomaticMiningSourcePolicy.blocksFor(Items.CHEST, Items.OAK_LOG));

      // The policy is deliberately narrow: other storage blocks are unchanged.
      assertOnly(Blocks.TRAPPED_CHEST, AutomaticMiningSourcePolicy.blocksFor(Items.TRAPPED_CHEST));
      assertOnly(Blocks.BARREL, AutomaticMiningSourcePolicy.blocksFor(Items.BARREL));

      // Physical item-to-block conversion stays intact; only automatic acquisition is filtered.
      assertOnly(Blocks.CHEST, ItemHelper.itemsToBlocks(new net.minecraft.world.item.Item[]{Items.CHEST}));

      assert AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("chest");
      assert AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("minecraft:chest");
      assert AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("#minecraft:chests");
      assert AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("#forge:chests/wooden");
      assert AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("#c:chests/wooden");
      assert !AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("minecraft:trapped_chest");
      assert !AutomaticMiningSourcePolicy.isProtectedAgenticMineTarget("minecraft:oak_log");
      System.out.println("AutomaticMiningSourcePolicySelfTest: PASS");
   }

   private static void assertOnly(Block expected, Block[] actual) {
      assert actual.length == 1;
      assert actual[0] == expected;
   }
}
