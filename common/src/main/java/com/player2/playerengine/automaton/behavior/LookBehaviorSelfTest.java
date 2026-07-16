package com.player2.playerengine.automaton.behavior;

/** Pure regression checks for exact forced targets and legacy idle-look leveling. */
public final class LookBehaviorSelfTest {
    private LookBehaviorSelfTest() {
    }

    public static void runAll() {
        require(LookBehavior.effectiveLookScramble(0.25, true) == 0.0,
                "forced look targets are never randomized");
        require(!LookBehavior.shouldNudgePitch(true, false),
                "forced look targets bypass pitch leveling");
        require(LookBehavior.levelAdjustedPitch(73.5F, 73.5F, 73.5F, false) == 73.5F,
                "forced steep farm aim remains exact across the look tick");

        require(LookBehavior.shouldNudgePitch(false, false),
                "ordinary non-free look retains idle pitch leveling");
        require(LookBehavior.levelAdjustedPitch(73.5F, 73.5F, 73.5F, true) == 72.5F,
                "ordinary repeated downward look still nudges toward level");
        require(!LookBehavior.shouldNudgePitch(false, true),
                "free look retains its no-nudge policy");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
