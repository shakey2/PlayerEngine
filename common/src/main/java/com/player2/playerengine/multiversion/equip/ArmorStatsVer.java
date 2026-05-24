package com.player2.playerengine.multiversion.equip;

import com.google.common.collect.Multimap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

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
      Multimap<Attribute, AttributeModifier> modifiers = stack.getAttributeModifiers(slot);
      for (Attribute attr : modifiers.keySet()) {
         for (AttributeModifier mod : modifiers.get(attr)) {
            double amount = mod.getAmount();
            if (attr == Attributes.ARMOR) {
               armor += amount;
            } else if (attr == Attributes.ARMOR_TOUGHNESS) {
               toughness += amount;
            } else if (attr == Attributes.KNOCKBACK_RESISTANCE) {
               knockback += amount;
            }
         }
      }
      return new ArmorStats(armor, toughness, knockback);
   }
}
