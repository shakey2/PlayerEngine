package com.player2.playerengine.automaton.api.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Stored damage for Baritone-style stack hashing without calling {@link ItemStack#getDamageValue()},
 * which on loaders can invoke {@link net.minecraft.world.item.Item#getMaxDamage()} and crash mod items.
 */
public final class BaritoneStackDamage {
   private BaritoneStackDamage() {
   }

   public static int storedDamage(ItemStack stack) {
      return stack.has(DataComponents.DAMAGE) ? stack.get(DataComponents.DAMAGE) : 0;
   }
}
