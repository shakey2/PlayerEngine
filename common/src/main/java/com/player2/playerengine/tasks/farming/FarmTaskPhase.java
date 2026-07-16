package com.player2.playerengine.tasks.farming;

/** Shared bounded phase vocabulary for setup and harvest operations. */
public enum FarmTaskPhase {
    PRECHECK,
    SELECT_SITE,
    ACQUIRE_RESOURCES,
    REVALIDATE,
    CLEAR,
    FILL,
    OPEN_CENTER,
    REGISTER_PREPARED,
    REPAIR,
    PLACE_WATER,
    TILL,
    VERIFY,
    COMMIT,
    HARVEST_RESOLVE,
    HARVEST_SCAN,
    HARVEST_BREAK,
    HARVEST_PICKUP,
    HARVEST_RESCAN,
    DONE,
    FAILED
}
