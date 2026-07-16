package com.player2.playerengine.tasks.farming;

import java.util.Objects;

/** Pure classifier for the paired world/inventory receipt of one normal planting click. */
public final class FarmPlantingReceiptClassifier {
    public enum Disposition {
        PENDING,
        SUCCESS,
        INVALID
    }

    private FarmPlantingReceiptClassifier() {
    }

    public static Disposition classify(
            int issuedAttempts,
            boolean baselineCaptured,
            boolean targetMatchesExpectedBefore,
            boolean supportMatchesExpectedFarmland,
            String expectedCanonicalCropId,
            String observedCanonicalCropId,
            int requestedItemsBefore,
            int requestedItemsAfter) {
        return classify(
                issuedAttempts,
                baselineCaptured,
                targetMatchesExpectedBefore,
                supportMatchesExpectedFarmland,
                expectedCanonicalCropId,
                observedCanonicalCropId,
                0,
                requestedItemsBefore,
                requestedItemsAfter);
    }

    public static Disposition classify(
            int issuedAttempts,
            boolean baselineCaptured,
            boolean targetMatchesExpectedBefore,
            boolean supportMatchesExpectedFarmland,
            String expectedCanonicalCropId,
            String observedCanonicalCropId,
            int confirmedSurvivalConsumptions,
            int requestedItemsBefore,
            int requestedItemsAfter) {
        if (issuedAttempts <= 0) {
            return Disposition.PENDING;
        }
        if (!baselineCaptured
                || requestedItemsBefore < 1
                || requestedItemsAfter < 0
                || confirmedSurvivalConsumptions < 0
                || expectedCanonicalCropId == null
                || expectedCanonicalCropId.isBlank()) {
            return Disposition.INVALID;
        }
        if (!supportMatchesExpectedFarmland) {
            return Disposition.INVALID;
        }

        boolean expectedFamilyObserved = Objects.equals(
                expectedCanonicalCropId, observedCanonicalCropId);
        long rawConsumed = (long) requestedItemsBefore - requestedItemsAfter;
        long plantingConsumed = rawConsumed - confirmedSurvivalConsumptions;
        if (plantingConsumed < 0L) {
            return Disposition.INVALID;
        }
        if (expectedFamilyObserved && !targetMatchesExpectedBefore
                && plantingConsumed == 1L) {
            return Disposition.SUCCESS;
        }
        if (targetMatchesExpectedBefore
                && observedCanonicalCropId == null
                && plantingConsumed == 0L) {
            return Disposition.PENDING;
        }
        return Disposition.INVALID;
    }
}
