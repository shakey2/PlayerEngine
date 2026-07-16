package com.player2.playerengine.tasks.farming;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One ordered planting-item request. Public identity is the planting item, not the crop block. */
public record FarmPlantingRequest(String plantingItemId, int count) {
    public FarmPlantingRequest {
        plantingItemId = canonicalId(plantingItemId);
        if (count < 1 || count > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException("planting count must be between 1 and 80");
        }
    }

    static String canonicalId(String raw) {
        String value = Objects.requireNonNull(raw, "plantingItemId").strip();
        String candidate = value.indexOf(':') > 0 ? value : "minecraft:" + value;
        ResourceLocation parsed = ResourceLocation.tryParse(candidate);
        if (value.isEmpty() || parsed == null || parsed.getPath().isEmpty()
                || !parsed.toString().equals(candidate)) {
            throw new IllegalArgumentException(
                    "planting item id must be canonical; bare ids use the minecraft namespace");
        }
        return candidate;
    }
}
