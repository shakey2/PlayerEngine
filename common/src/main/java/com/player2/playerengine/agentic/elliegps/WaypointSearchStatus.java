package com.player2.playerengine.agentic.elliegps;

/** Provenance/health status for a structured waypoint search. */
public enum WaypointSearchStatus {
    INDEXED,
    AUTHORITATIVE_SPATIAL,
    FULL_STORE_FALLBACK,
    FAILED_STORE_UNAVAILABLE,
    FAILED_SEARCH_ERROR,
    FAILED_SCAN_LIMIT
}
