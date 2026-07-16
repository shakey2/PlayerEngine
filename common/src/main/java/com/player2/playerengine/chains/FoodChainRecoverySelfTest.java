package com.player2.playerengine.chains;

/** Deterministic regression checks for starvation recovery notification timing. */
public final class FoodChainRecoverySelfTest {
    private FoodChainRecoverySelfTest() {
    }

    public static void runAll() {
        FoodRecoveryInfoPolicy.State state = FoodRecoveryInfoPolicy.advance(
                false, false, true, 6, false, false);
        require(state.episodeActive() && state.notifyPlayer(), "starvation must open one episode");
        require(!state.deferModelInfo(), "starvation must not report recovery");

        state = FoodRecoveryInfoPolicy.advance(
                state.episodeActive(), state.recoveryInfoPending(), false, 6, true, true);
        require(state.episodeActive() && state.recoveryInfoPending(),
                "food arrival must arm recovery while eating");
        require(!state.deferModelInfo(), "food arrival alone must not report recovery");

        state = FoodRecoveryInfoPolicy.advance(
                state.episodeActive(), state.recoveryInfoPending(), false, 12, false, true);
        require(!state.deferModelInfo(), "multi-item fill-up must finish before recovery is reported");

        state = FoodRecoveryInfoPolicy.advance(
                state.episodeActive(), state.recoveryInfoPending(), false, 20, false, false);
        require(!state.episodeActive() && state.deferModelInfo(),
                "quiescent hunger recovery must emit exactly one passive note");

        state = FoodRecoveryInfoPolicy.advance(
                state.episodeActive(), state.recoveryInfoPending(), false, 20, false, false);
        require(!state.deferModelInfo(), "settled recovery must not emit twice");

        state = FoodRecoveryInfoPolicy.advance(false, false, true, 6, false, false);
        state = FoodRecoveryInfoPolicy.advance(true, false, false, 6, true, true);
        state = FoodRecoveryInfoPolicy.advance(true, true, true, 6, false, false);
        require(state.episodeActive() && !state.recoveryInfoPending() && !state.notifyPlayer(),
                "failed food attempt must stay in the same starvation episode");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("FoodChainRecoverySelfTest failed: " + message);
        }
    }
}
