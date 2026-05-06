package com.player2.playerengine.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

public class EnchantmentUtils {
    public static int getEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> enchantment){
        try {
            int effLevel = stack.has(DataComponents.ENCHANTMENTS) ? stack.get(DataComponents.ENCHANTMENTS).entrySet().stream().filter(e -> e.getKey().unwrapKey().get().equals(Enchantments.EFFICIENCY)).findFirst().get().getIntValue() : 0;
            return effLevel;
        }catch (Exception e){
            return 0;
        }
    }
}
