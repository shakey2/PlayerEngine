package com.player2.playerengine.trackers.storage;

/** Pure fail-closed check for crediting one survival item-consumption receipt. */
public final class SurvivalConsumptionReceiptClassifier {
    private SurvivalConsumptionReceiptClassifier() {
    }

    public static boolean shouldRecord(
            boolean alreadyRecorded,
            boolean baselineCaptured,
            int itemCountBeforeUse,
            int itemCountAfterUse) {
        return !alreadyRecorded
                && baselineCaptured
                && itemCountBeforeUse >= 1
                && itemCountAfterUse >= 0
                && itemCountAfterUse == itemCountBeforeUse - 1;
    }
}
