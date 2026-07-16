package com.player2.playerengine.util.helpers;

import com.player2.playerengine.util.ItemTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Policy for world blocks inferred automatically from an item acquisition target.
 *
 * <p>{@link Block#byItem(Item)} describes a physical item-to-block relationship; it does not mean
 * that an already-placed block is an acceptable source for obtaining that item. In particular, a
 * request to obtain a new chest must craft one (or collect an existing dropped chest item), never
 * dismantle a chest in the world. Explicit block-mining commands do not use this policy.
 */
public final class AutomaticMiningSourcePolicy {

   private AutomaticMiningSourcePolicy() {
   }

   /** Returns the allowed automatically inferred mining sources for the supplied concrete items. */
   public static Block[] blocksFor(Item... items) {
      if (items == null || items.length == 0) {
         return new Block[0];
      }
      List<Block> result = new ArrayList<>(items.length);
      for (Item item : items) {
         if (item == null || isProtectedAcquisitionItem(item)) {
            continue;
         }
         Block block = Block.byItem(item);
         if (block != null && block != Blocks.AIR) {
            result.add(block);
         }
      }
      return result.toArray(Block[]::new);
   }

   /** Returns the allowed automatically inferred mining sources for one or more item targets. */
   public static Block[] blocksFor(ItemTarget... targets) {
      if (targets == null || targets.length == 0) {
         return new Block[0];
      }
      List<Block> result = new ArrayList<>(targets.length);
      for (ItemTarget target : targets) {
         if (target == null) {
            continue;
         }
         for (Block block : blocksFor(target.getMatches())) {
            result.add(block);
         }
      }
      return result.toArray(Block[]::new);
   }

   /** True when a gather target contains an item that must never be sourced from its placed block. */
   public static boolean containsProtectedAcquisitionItem(ItemTarget... targets) {
      if (targets == null) {
         return false;
      }
      for (ItemTarget target : targets) {
         if (target == null) {
            continue;
         }
         for (Item item : target.getMatches()) {
            if (isProtectedAcquisitionItem(item)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Agentic plans may not turn a chest-obtain/place goal into an explicit chest-mining step. Direct
    * player-issued mining commands bypass the agentic step factory and remain available.
    */
   public static boolean isProtectedAgenticMineTarget(String rawBlockId) {
      if (rawBlockId == null) {
         return false;
      }
      String normalized = rawBlockId.trim().toLowerCase(Locale.ROOT);
      while (normalized.startsWith("#")) {
         normalized = normalized.substring(1);
      }
      int namespaceSeparator = normalized.indexOf(':');
      String path = namespaceSeparator >= 0 ? normalized.substring(namespaceSeparator + 1) : normalized;
      return normalized.equals("chest")
            || normalized.equals("minecraft:chest")
            || path.equals("chests")
            || path.startsWith("chests/");
   }

   private static boolean isProtectedAcquisitionItem(Item item) {
      return item == Items.CHEST;
   }
}
