package com.player2.playerengine.util.equip;

import com.player2.playerengine.multiversion.equip.WeaponStatsVer;
import com.player2.playerengine.multiversion.equip.WeaponVer;
import net.minecraft.world.item.ItemStack;

public final class WeaponEquipScorer {
   private WeaponEquipScorer() {
   }

   public static boolean isUpgrade(ItemStack candidate, ItemStack currentlyHeld) {
      if (candidate.isEmpty() || !WeaponVer.isMeleeWeapon(candidate)) {
         return false;
      }
      if (currentlyHeld.isEmpty()) {
         return true;
      }
      if (!WeaponVer.isMeleeWeapon(currentlyHeld)) {
         return true;
      }
      return WeaponStatsVer.read(candidate).isBetterThan(WeaponStatsVer.read(currentlyHeld));
   }
}
