package com.player2.playerengine.agentic.elliegps;

import java.util.Objects;

/** Explicit tri-state update for the optional future bot-planting restriction on one farm. */
public record FarmPlantingRestrictionUpdate(Kind kind, String plantingItemId) {

    public enum Kind {
        PRESERVE,
        CLEAR,
        SET
    }

    private static final FarmPlantingRestrictionUpdate PRESERVE =
            new FarmPlantingRestrictionUpdate(Kind.PRESERVE, null);
    private static final FarmPlantingRestrictionUpdate CLEAR =
            new FarmPlantingRestrictionUpdate(Kind.CLEAR, null);

    public FarmPlantingRestrictionUpdate {
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SET) {
            plantingItemId = FarmCropCount.requireCanonicalId(
                    plantingItemId, "plantingRestrictionItemId");
        } else if (plantingItemId != null) {
            throw new IllegalArgumentException(
                    "only a SET restriction update may carry a planting item id");
        }
    }

    public static FarmPlantingRestrictionUpdate preserve() {
        return PRESERVE;
    }

    public static FarmPlantingRestrictionUpdate clear() {
        return CLEAR;
    }

    public static FarmPlantingRestrictionUpdate set(String canonicalPlantingItemId) {
        return new FarmPlantingRestrictionUpdate(Kind.SET, canonicalPlantingItemId);
    }

    String apply(String existingRestriction) {
        return switch (kind) {
            case PRESERVE -> existingRestriction;
            case CLEAR -> null;
            case SET -> plantingItemId;
        };
    }
}
