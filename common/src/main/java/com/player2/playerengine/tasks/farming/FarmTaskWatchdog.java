package com.player2.playerengine.tasks.farming;

import java.util.Objects;

/** Pure tick watchdog for whole-operation, phase, and no-progress deadlines. */
public final class FarmTaskWatchdog {
    private int wholeTicks;
    private int phaseTicks;
    private int noProgressTicks;
    private FarmTaskPhase phase = FarmTaskPhase.PRECHECK;

    public void reset() {
        wholeTicks = 0;
        restartFromCheckpoint();
    }

    /** Re-arms phase-local deadlines while preserving the finite whole-operation budget. */
    public void restartFromCheckpoint() {
        phaseTicks = 0;
        noProgressTicks = 0;
        phase = FarmTaskPhase.PRECHECK;
    }

    public void tick() {
        wholeTicks++;
        phaseTicks++;
        noProgressTicks++;
    }

    public void enterPhase(FarmTaskPhase next) {
        phase = Objects.requireNonNull(next, "next");
        phaseTicks = 0;
        noProgressTicks = 0;
    }

    public void markProgress() {
        noProgressTicks = 0;
    }

    public FarmTaskPhase phase() {
        return phase;
    }

    public int wholeTicks() {
        return wholeTicks;
    }

    public int phaseTicks() {
        return phaseTicks;
    }

    public int noProgressTicks() {
        return noProgressTicks;
    }

    public boolean wholeSetupExpired() {
        return wholeTicks >= FarmPlotPolicy.WHOLE_SETUP_TICKS;
    }

    public boolean phaseExpired(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return phaseTicks >= limit;
    }

    public boolean mutationStalled() {
        return noProgressTicks >= FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS;
    }
}
