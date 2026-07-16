package com.player2.playerengine.tasks.farming;

import java.util.Objects;
import java.util.Optional;

/** Optional mutation of one recorded farm's future bot-planting restriction. */
public record FarmPlantingPolicy(Kind kind, String plantingItemId) {
    public enum Kind {
        PRESERVE,
        CLEAR,
        SET
    }

    public FarmPlantingPolicy {
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SET) {
            plantingItemId = FarmPlantingRequest.canonicalId(plantingItemId);
        } else if (plantingItemId != null) {
            throw new IllegalArgumentException("only SET may carry a planting item id");
        }
    }

    public static FarmPlantingPolicy preserve() {
        return new FarmPlantingPolicy(Kind.PRESERVE, null);
    }

    public static FarmPlantingPolicy clear() {
        return new FarmPlantingPolicy(Kind.CLEAR, null);
    }

    public static FarmPlantingPolicy set(String plantingItemId) {
        return new FarmPlantingPolicy(Kind.SET, plantingItemId);
    }

    public Optional<String> restrictedItemId() {
        return Optional.ofNullable(plantingItemId);
    }

    public static FarmPlantingPolicy parse(String raw) {
        String value = raw == null || raw.isBlank() ? "preserve" : raw.strip();
        if ("preserve".equals(value)) {
            return preserve();
        }
        if ("mixed".equals(value)) {
            return clear();
        }
        String prefix = "single:";
        if (value.startsWith(prefix) && value.length() > prefix.length()) {
            return set(value.substring(prefix.length()));
        }
        throw new IllegalArgumentException("farm_policy must be preserve, mixed, or single:<item-id>");
    }
}
