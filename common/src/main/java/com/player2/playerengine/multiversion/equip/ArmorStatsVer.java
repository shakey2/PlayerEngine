package com.player2.playerengine.multiversion.equip;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

public final class ArmorStatsVer {
   private ArmorStatsVer() {
   }

   public static ArmorStats read(ItemStack stack, EquipmentSlot slot) {
      if (stack.isEmpty()) {
         return ArmorStats.ZERO;
      }
      double armor = 0;
      double toughness = 0;
      double knockback = 0;
      if (stack.getItem() instanceof ArmorItem armorItem) {
         armor = armorItem.getDefense();
         toughness = armorItem.getToughness();
      }
      ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
      for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
         if (!entry.slot().test(slot)) {
            continue;
         }
         double amount = entry.modifier().amount();
         if (entry.attribute().is(Attributes.ARMOR)) {
            armor += amount;
         } else if (entry.attribute().is(Attributes.ARMOR_TOUGHNESS)) {
            toughness += amount;
         } else if (entry.attribute().is(Attributes.KNOCKBACK_RESISTANCE)) {
            knockback += amount;
         }
      }
      return new ArmorStats(armor, toughness, knockback);
   }
}
