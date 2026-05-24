package com.player2.playerengine.automaton.api.utils;

import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Stored damage for Baritone-style stack hashing without calling {@link ItemStack#getDamageValue()},
 * which on Forge can invoke {@link net.minecraft.world.item.Item#getMaxDamage()} and crash mod items.
 * On 1.20.1, damage is persisted on the stack tag ({@code Damage}), not a separate field.
 */
public final class BaritoneStackDamage {
   private BaritoneStackDamage() {
   }

   public static int storedDamage(ItemStack stack) {
      var tag = stack.getTag();
      if (tag != null && tag.contains("Damage", Tag.TAG_ANY_NUMERIC)) {
         return tag.getInt("Damage");
      }

      return 0;
   }
}
