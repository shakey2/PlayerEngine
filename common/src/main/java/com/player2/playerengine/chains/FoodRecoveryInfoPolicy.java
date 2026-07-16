package com.player2.playerengine.chains;

/** Pure starvation-episode state machine, isolated from FoodChain's runtime config bootstrap. */
final class FoodRecoveryInfoPolicy {
    static final int STARVING_FOOD_LEVEL = 6;

    private FoodRecoveryInfoPolicy() {
    }

    static State advance(
            boolean episodeActive,
            boolean recoveryInfoPending,
            boolean isStarving,
            int foodLevel,
            boolean isTryingToEat,
            boolean requestFillup) {
        boolean notifyPlayer = false;
        boolean deferModelInfo = false;
        if (isStarving) {
            if (!episodeActive) {
                notifyPlayer = true;
            }
            episodeActive = true;
            // Food may have appeared and vanished without raising hunger. That is not recovery.
            recoveryInfoPending = false;
        } else if (episodeActive) {
            recoveryInfoPending = true;
            if (foodLevel > STARVING_FOOD_LEVEL && !isTryingToEat && !requestFillup) {
                episodeActive = false;
                recoveryInfoPending = false;
                deferModelInfo = true;
            }
        } else {
            recoveryInfoPending = false;
        }
        return new State(
                episodeActive, recoveryInfoPending, notifyPlayer, deferModelInfo);
    }

    record State(
            boolean episodeActive,
            boolean recoveryInfoPending,
            boolean notifyPlayer,
            boolean deferModelInfo) {
    }
}
