package com.player2.playerengine.multiversion.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-version default-stack snapshot for capability fingerprinting (B4) — 1.20.1.
 */
public final class ItemNbtSnapshotVer {
    private ItemNbtSnapshotVer() {}

    public static Map<String, String> snapshot(ItemStack stack, net.minecraft.core.HolderLookup.Provider registryAccess) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        out.put("id", String.valueOf(stack.getItem()));
        out.put("count", String.valueOf(stack.getCount()));
        if (ItemVer.isFood(stack.getItem())) {
            var food = ItemVer.getFoodComponent(stack.getItem());
            if (food != null) {
                out.put("food", "nutrition=" + food.getHunger() + ",saturation=" + food.getSaturationModifier());
            }
        }
        if (stack.isDamageableItem()) {
            out.put("maxDamage", String.valueOf(stack.getMaxDamage()));
        }
        try {
            CompoundTag tag = stack.save(new CompoundTag());
            String json = tag.toString();
            if (json.length() <= 512) {
                out.put("tag", json);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
