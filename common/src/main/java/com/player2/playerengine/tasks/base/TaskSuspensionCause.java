package com.player2.playerengine.tasks.base;

/** Safety-preserving reasons which may pause and later resume one logical task submission. */
public enum TaskSuspensionCause {
    /** A short body-language task temporarily overlays the active user task. */
    GESTURE_OVERLAY,
    /** A higher-priority task chain, such as food or defense, temporarily owns the scheduler. */
    HIGHER_PRIORITY_CHAIN
}
