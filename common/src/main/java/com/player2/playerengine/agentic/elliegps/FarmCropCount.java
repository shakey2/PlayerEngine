package com.player2.playerengine.agentic.elliegps;

import net.minecraft.resources.ResourceLocation;

/** One canonical crop entry persisted in a farm waypoint. */
public record FarmCropCount(String blockId, String plantingItemId, int count) {
    public FarmCropCount {
        blockId = requireCanonicalId(blockId, "blockId");
        if (plantingItemId != null) {
            plantingItemId = requireCanonicalId(plantingItemId, "plantingItemId");
        }
        if (count < 1 || count > FarmWaypointData.MAX_FARMLAND_BLOCKS) {
            throw new IllegalArgumentException("count must be between 1 and 80");
        }
    }

    static String requireCanonicalId(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !parsed.toString().equals(value)) {
            throw new IllegalArgumentException(field + " must be a canonical namespaced id");
        }
        return value;
    }
}
