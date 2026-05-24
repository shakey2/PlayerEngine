package com.player2.playerengine.multiversion.equip;

import com.google.common.collect.Multimap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

public final class WeaponStatsVer {
   private WeaponStatsVer() {
   }

   public static WeaponStats read(ItemStack stack) {
      return new WeaponStats(readAttackDamage(stack));
   }

   public static double readAttackDamage(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0;
      }
      double attack = 0;
      Multimap<Attribute, AttributeModifier> modifiers = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
      for (Attribute attr : modifiers.keySet()) {
         if (attr == Attributes.ATTACK_DAMAGE) {
            for (AttributeModifier mod : modifiers.get(attr)) {
               attack += mod.getAmount();
            }
         }
      }
      return attack;
   }
}
