package com.player2.playerengine.agentic.elliegps;

import java.util.Objects;

/** Checked authoritative-store mutation result and before/after records. */
public record WaypointMutationResult(
        WaypointMutationStatus status,
        WaypointRecord previous,
        WaypointRecord current
) {
    public WaypointMutationResult {
        status = Objects.requireNonNull(status, "status");
    }
}
