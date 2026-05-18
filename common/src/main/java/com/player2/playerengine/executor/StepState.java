package com.player2.playerengine.executor;

/**
 * Lifecycle states for a single tracked TaskStep execution.
 *
 * Allowed transitions (enforced by StepExecution.transitionTo):
 *   PENDING  -> RUNNING     (preconditions passed, task submitted to chain)
 *   PENDING  -> BLOCKED     (preconditions failed before submission)
 *   RUNNING  -> SUCCEEDED   (task.isFinished() == true in finish callback)
 *   RUNNING  -> FAILED      (task stopped without isFinished())
 *   RUNNING  -> ROLLED_BACK (failed + rollback policy applied — stub in Phase A2)
 *
 * BLOCKED, SUCCEEDED, FAILED, and ROLLED_BACK are terminal states.
 */
public enum StepState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    BLOCKED
}
