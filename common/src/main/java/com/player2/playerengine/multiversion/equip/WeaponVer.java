package com.player2.playerengine.multiversion.equip;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

public final class WeaponVer {
   private WeaponVer() {
   }

   public static boolean isMeleeWeapon(ItemStack stack) {
      if (stack.isEmpty() || EquipVer.isBodyArmor(stack)) {
         return false;
      }
      if (stack.getItem() instanceof SwordItem
            || stack.getItem() instanceof AxeItem
            || stack.getItem() instanceof TridentItem
            || stack.getItem() instanceof MaceItem) {
         return true;
      }
      return WeaponStatsVer.readAttackDamage(stack) > 0;
   }
}
