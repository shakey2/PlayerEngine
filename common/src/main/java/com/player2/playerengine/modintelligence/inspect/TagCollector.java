package com.player2.playerengine.modintelligence.inspect;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TagCollector {
    private TagCollector() {}

    public static <T> List<String> collectTagIds(Registry<T> registry, ResourceLocation id) {
        ResourceKey<T> key = ResourceKey.create(registry.key(), id);
        return registry.getHolder(key)
                .map(holder -> {
                    List<String> tags = new ArrayList<>();
                    holder.tags().forEach(tagKey -> tags.add(tagKey.location().toString()));
                    Collections.sort(tags);
                    return tags;
                })
                .orElse(Collections.emptyList());
    }
}
