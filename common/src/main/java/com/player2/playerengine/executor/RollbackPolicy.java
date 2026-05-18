package com.player2.playerengine.executor;

/**
 * Describes the compensating action policy after a TaskStep failure.
 *
 * Mirrors the spec-level definition in masterplan/task-contract-spec.md §2.6.
 * Full enforcement is deferred to Phase A3+; Phase A2 provides a logging stub
 * for BEST_EFFORT and REQUIRED.
 */
public enum RollbackPolicy {

    /** No compensating action required; safe to abandon (e.g. pure movement). */
    NONE,

    /**
     * Attempt cleanup (pick drops, revert partial place) but success is not
     * guaranteed. Phase A2: logged as stub, no actual compensating task runs.
     */
    BEST_EFFORT,

    /**
     * Compensating TaskSteps must complete before reporting final failure.
     * Phase A2: logged as stub, no actual compensating task runs.
     */
    REQUIRED
}
