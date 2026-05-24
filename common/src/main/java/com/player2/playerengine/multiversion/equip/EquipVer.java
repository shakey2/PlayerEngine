package com.player2.playerengine.multiversion.equip;

import java.util.Optional;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

public final class EquipVer {
   private EquipVer() {
   }

   public static boolean isBodyArmor(ItemStack stack) {
      return getBodyArmorSlot(stack).isPresent();
   }

   public static Optional<EquipmentSlot> getBodyArmorSlot(ItemStack stack) {
      if (stack.isEmpty()) {
         return Optional.empty();
      }
      if (stack.getItem() instanceof ArmorItem armorItem) {
         EquipmentSlot slot = armorItem.getType().getSlot();
         return isHumanoidArmorSlot(slot) ? Optional.of(slot) : Optional.empty();
      }
      return Optional.empty();
   }

   private static boolean isHumanoidArmorSlot(EquipmentSlot slot) {
      return slot == EquipmentSlot.HEAD
            || slot == EquipmentSlot.CHEST
            || slot == EquipmentSlot.LEGS
            || slot == EquipmentSlot.FEET;
   }
}
