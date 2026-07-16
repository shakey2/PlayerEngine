package com.player2.playerengine.tasks.farming;

/** Bounded result of returning from resource acquisition to the frozen farm site. */
public enum FarmReturnStatus {
    NOT_REQUIRED,
    RETURNED,
    TIMED_OUT,
    UNAVAILABLE;

    public boolean incomplete() {
        return this == TIMED_OUT || this == UNAVAILABLE;
    }
}
