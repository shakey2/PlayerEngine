package com.player2.playerengine.agentic.elliegps;

/** Exhaustive checked outcomes shared by all authoritative waypoint mutations. */
public enum WaypointMutationStatus {
    COMMITTED,
    COMMITTED_INDEX_DEGRADED,
    NO_CHANGE,
    NO_CHANGE_INDEX_DEGRADED,
    NOT_FOUND,
    NOT_FOUND_INDEX_DEGRADED,
    REJECTED_TYPE_CONFLICT,
    REJECTED_TARGET_CONFLICT,
    FAILED_STORE_UNAVAILABLE,
    FAILED_JSON_COMMIT
}
