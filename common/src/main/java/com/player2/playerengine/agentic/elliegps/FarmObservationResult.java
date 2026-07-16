package com.player2.playerengine.agentic.elliegps;

import java.util.Objects;

/** Observation plus a bounded controlled reason suitable for command feedback. */
public record FarmObservationResult(
        FarmObservationStatus status,
        FarmWaypointObservation observation,
        String controlledReason
) {
    public static final int MAX_REASON_LENGTH = 160;

    public FarmObservationResult {
        status = Objects.requireNonNull(status, "status");
        if ((status == FarmObservationStatus.OBSERVED) != (observation != null)) {
            throw new IllegalArgumentException("only OBSERVED results carry an observation");
        }
        controlledReason = bound(controlledReason);
    }

    private static String bound(String reason) {
        String value = reason == null ? "" : reason.strip();
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }
}
