package com.player2.playerengine.modintelligence.inspect;

import com.player2.playerengine.multiversion.item.ItemNbtSnapshotVer;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SanitizedNbtSnapshot {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id", "count", "components", "tag", "food", "damage", "durability", "maxDamage"
    );
    private static final int MAX_VALUE_LEN = 256;

    private SanitizedNbtSnapshot() {}

    public static Map<String, String> fromDefaultStack(Item item, HolderLookup.Provider registryAccess) {
        if (item == null) {
            return Map.of();
        }
        try {
            ItemStack stack = new ItemStack(item);
            Map<String, String> raw = ItemNbtSnapshotVer.snapshot(stack, registryAccess);
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                String key = e.getKey().toLowerCase();
                if (!ALLOWED_KEYS.contains(key) && !key.startsWith("component.")) {
                    continue;
                }
                String val = e.getValue();
                if (val == null) {
                    continue;
                }
                if (val.length() > MAX_VALUE_LEN) {
                    val = val.substring(0, MAX_VALUE_LEN) + "...";
                }
                out.put(key, val);
            }
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
