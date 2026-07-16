package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Immutable, bounded setup/harvest completion payload used by direct and agentic feedback. */
public record FarmTaskOutcome(
        boolean terminal,
        boolean successful,
        FarmTaskReason reason,
        FarmTaskPhase phase,
        String dimension,
        BlockPos center,
        int cleared,
        int filled,
        int tilled,
        boolean sourcePlaced,
        WaypointMutationResult waypointResult,
        FarmReturnStatus returnStatus
) {
    public FarmTaskOutcome(
            boolean terminal,
            boolean successful,
            FarmTaskReason reason,
            FarmTaskPhase phase,
            String dimension,
            BlockPos center,
            int cleared,
            int filled,
            int tilled,
            boolean sourcePlaced,
            WaypointMutationResult waypointResult) {
        this(terminal, successful, reason, phase, dimension, center, cleared, filled, tilled,
                sourcePlaced, waypointResult, FarmReturnStatus.NOT_REQUIRED);
    }

    public FarmTaskOutcome {
        reason = Objects.requireNonNull(reason, "reason");
        phase = Objects.requireNonNull(phase, "phase");
        returnStatus = Objects.requireNonNull(returnStatus, "returnStatus");
        if (dimension != null && dimension.isBlank()) {
            throw new IllegalArgumentException("dimension cannot be blank");
        }
        if (center != null) {
            center = center.immutable();
        }
        requireRange(cleared, 0, FarmPlotPolicy.MAX_CLEAR_ACTIONS, "cleared");
        requireRange(filled, 0, FarmPlotPolicy.MAX_FILL_ACTIONS, "filled");
        requireRange(tilled, 0, FarmPlotPolicy.MAX_TILL_ACTIONS, "tilled");

        if (successful && (!terminal || reason != FarmTaskReason.NONE || phase != FarmTaskPhase.DONE)) {
            throw new IllegalArgumentException("successful outcome must be terminal DONE with reason NONE");
        }
        if (successful && (dimension == null || center == null || waypointResult == null)) {
            throw new IllegalArgumentException("successful outcome needs dimension, center, and waypoint result");
        }
        if (terminal && !successful
                && (reason == FarmTaskReason.NONE || phase == FarmTaskPhase.DONE)) {
            throw new IllegalArgumentException("failed terminal outcome needs a reason and operational phase");
        }
        if (!terminal && successful) {
            throw new IllegalArgumentException("non-terminal outcome cannot be successful");
        }
        if (!terminal && reason != FarmTaskReason.NONE) {
            throw new IllegalArgumentException("non-terminal outcome cannot carry a failure reason");
        }
        if (!terminal && returnStatus != FarmReturnStatus.NOT_REQUIRED) {
            throw new IllegalArgumentException("non-terminal outcome cannot carry a return result");
        }
        if (successful && returnStatus.incomplete()) {
            throw new IllegalArgumentException("successful outcome cannot carry an incomplete return");
        }
    }

    public static FarmTaskOutcome initial() {
        return new FarmTaskOutcome(false, false, FarmTaskReason.NONE, FarmTaskPhase.PRECHECK,
                null, null, 0, 0, 0, false, null);
    }

    private static void requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
    }
}
