package com.player2.playerengine.multiversion.equip;

public record ArmorStats(double armorPoints, double toughness, double knockbackResistance) {
   public static final ArmorStats ZERO = new ArmorStats(0, 0, 0);

   public int compareTo(ArmorStats other) {
      int c = Double.compare(this.armorPoints, other.armorPoints);
      if (c != 0) {
         return c;
      }
      c = Double.compare(this.toughness, other.toughness);
      if (c != 0) {
         return c;
      }
      return Double.compare(this.knockbackResistance, other.knockbackResistance);
   }

   public boolean isBetterThan(ArmorStats other) {
      return compareTo(other) > 0;
   }
}
