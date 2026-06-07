package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class CraftMacroSupport {
   private CraftMacroSupport() {
   }

   public static boolean isMacroEnabled(com.player2.playerengine.PlayerEngineController mod) {
      return mod.getModSettings().isFastCraftMacrosEnabled();
   }

   public static boolean isSupportedItem(Item item) {
      if (item == Items.CHEST || item == Items.CRAFTING_TABLE || item == Items.STICK) {
         return true;
      }
      if (Arrays.asList(ItemHelper.PLANKS).contains(item)) {
         return true;
      }
      return Arrays.asList(ItemHelper.WOOD_SIGN).contains(item);
   }

   public static boolean isSupportedTarget(ItemTarget target) {
      if (target.isCatalogueItem()) {
         String name = target.getCatalogueName();
         if (name.equals("sign") || name.equals("chest") || name.equals("crafting_table") || name.equals("stick") || name.equals("planks")) {
            return true;
         }
         return name.endsWith("_sign") || name.endsWith("_planks");
      }
      for (Item match : target.getMatches()) {
         if (isSupportedItem(match)) {
            return true;
         }
      }
      return false;
   }

   public static Optional<Item> resolveSingleOutputItem(ItemTarget target) {
      if (target.getMatches().length == 1) {
         return Optional.of(target.getMatches()[0]);
      }
      if (target.isCatalogueItem()) {
         String name = target.getCatalogueName();
         if (name.equals("sign")) {
            return Optional.of(Items.OAK_SIGN);
         }
         if (name.equals("planks")) {
            return Optional.of(Items.OAK_PLANKS);
         }
         if (name.equals("chest")) {
            return Optional.of(Items.CHEST);
         }
         if (name.equals("crafting_table")) {
            return Optional.of(Items.CRAFTING_TABLE);
         }
         if (name.equals("stick")) {
            return Optional.of(Items.STICK);
         }
         if (name.endsWith("_sign") && TaskCatalogue.taskExists(name)) {
            return Optional.of(TaskCatalogue.getItemMatches(name)[0]);
         }
         if (name.endsWith("_planks") && TaskCatalogue.taskExists(name)) {
            return Optional.of(TaskCatalogue.getItemMatches(name)[0]);
         }
      }
      return Optional.empty();
   }
}
