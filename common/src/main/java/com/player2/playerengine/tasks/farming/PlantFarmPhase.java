package com.player2.playerengine.tasks.farming;

/** Bounded phase vocabulary for one finite ordered planting operation. */
public enum PlantFarmPhase {
    RESOLVE,
    TRAVEL,
    SCAN,
    ACQUIRE,
    PLANT,
    RESCAN,
    COMMIT,
    DONE,
    FAILED
}
