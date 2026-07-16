package com.player2.playerengine.tasks.farming;

import java.util.Locale;

/** Typed terminal reason for planting; tokens are bounded and safe for model feedback. */
public enum PlantFarmReason {
    NONE,
    ELLIEGPS_DISABLED,
    STORE_UNAVAILABLE,
    NO_RECORDED_FARMS,
    ALL_RECORDED_FARMS_FULL,
    NO_POLICY_COMPATIBLE_FARM,
    INSUFFICIENT_OPEN_SLOTS,
    EXACT_FARM_NOT_FOUND,
    EXACT_FARM_FULL,
    EXACT_FARM_POLICY_CONFLICT,
    TYPE_CONFLICT,
    STALE_FARM,
    UNSUPPORTED_FARM_DATA,
    UNSUPPORTED_PLANTABLE,
    STEM_CROP_EXCLUDED,
    PLANTABLE_UNAVAILABLE,
    ACQUISITION_TIMEOUT,
    FARM_NEEDS_REPAIR,
    NO_SAFE_STANCE,
    INTERACTION_DENIED,
    RECEIPT_MISMATCH,
    WAYPOINT_JSON_FAILURE,
    SEARCH_SCAN_LIMIT,
    CHUNK_UNLOADED,
    DIMENSION_CHANGED,
    OPERATION_TIMEOUT,
    CANCELLED_OPERATOR,
    INTERNAL_CONTRACT_FAILURE;

    public String controlledReason() {
        return name().toLowerCase(Locale.ROOT);
    }
}
