package com.player2.playerengine.agentic.elliegps;

import java.util.List;
import java.util.Objects;

/** Immutable structured-search records plus the route used to obtain them. */
public record WaypointSearchResult(List<WaypointRecord> records, WaypointSearchStatus status) {
    public WaypointSearchResult {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        status = Objects.requireNonNull(status, "status");
    }
}
