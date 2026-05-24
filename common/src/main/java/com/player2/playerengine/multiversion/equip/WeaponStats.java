package com.player2.playerengine.multiversion.equip;

public record WeaponStats(double attackDamage) {
   public static final WeaponStats ZERO = new WeaponStats(0);

   public int compareTo(WeaponStats other) {
      return Double.compare(this.attackDamage, other.attackDamage);
   }

   public boolean isBetterThan(WeaponStats other) {
      return compareTo(other) > 0;
   }
}
