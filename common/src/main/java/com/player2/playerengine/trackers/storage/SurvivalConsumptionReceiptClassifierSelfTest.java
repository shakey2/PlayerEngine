package com.player2.playerengine.trackers.storage;

/** Deterministic exact-once and fail-closed receipt matrix. */
public final class SurvivalConsumptionReceiptClassifierSelfTest {
    private SurvivalConsumptionReceiptClassifierSelfTest() {
    }

    public static void runAll() {
        require(classify(false, true, 4, 3),
                "one observed item delta records a confirmed consumption");
        require(!classify(true, true, 4, 3),
                "an already-recorded receipt cannot be credited twice");
        require(!classify(false, true, 4, 4),
                "no inventory delta cannot be credited");
        require(!classify(false, true, 4, 2),
                "a multi-item delta is ambiguous and cannot be credited");
        require(!classify(false, false, 4, 3),
                "a missing baseline cannot be credited");
    }

    private static boolean classify(
            boolean alreadyRecorded,
            boolean baselineCaptured,
            int before,
            int after) {
        return SurvivalConsumptionReceiptClassifier.shouldRecord(
                alreadyRecorded, baselineCaptured, before, after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
