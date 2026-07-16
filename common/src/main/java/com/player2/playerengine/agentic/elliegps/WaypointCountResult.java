package com.player2.playerengine.agentic.elliegps;

import java.util.Objects;

/** Non-negative authoritative waypoint count plus the search route/health that produced it. */
public record WaypointCountResult(int count, WaypointSearchStatus status) {
    public WaypointCountResult {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        status = Objects.requireNonNull(status, "status");
    }
}
