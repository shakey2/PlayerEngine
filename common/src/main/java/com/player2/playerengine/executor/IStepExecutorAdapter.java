package com.player2.playerengine.executor;

import com.player2.playerengine.tasks.base.Task;

import java.util.Optional;

/**
 * Adapter interface between the Phase A contract layer (TaskStep + metadata)
 * and the existing runtime (UserTaskChain / Task).
 *
 * The adapter:
 *   1. Evaluates preconditions before submitting a task to the chain.
 *   2. Tracks state transitions (PENDING -> RUNNING -> SUCCEEDED / FAILED /
 *      ROLLED_BACK / BLOCKED) via StepExecution.
 *   3. Always invokes onComplete when the step reaches a terminal state so
 *      that CommandExecutor's sequential chain callbacks are preserved.
 *
 * Phase A2: accepts a pre-built Task (task construction remains in Command
 * subclasses). Phase B will extend this with a TaskStep -> Task factory
 * registry when the planner produces structured TaskStep objects.
 */
public interface IStepExecutorAdapter {

    /**
     * Submit a pre-built {@code task} for tracked execution on the
     * UserTaskChain.
     *
     * @param stepId         Stable identifier for this step (used in logs).
     * @param stepKind       Action family descriptor, e.g. {@code follow_player}.
     * @param task           Fully-constructed Task to execute.
     * @param rollbackPolicy What to do if the step fails.
     * @param onComplete     Called when the step reaches any terminal state
     *                       (success, failure, or blocked). Must not be null.
     *                       Preserves CommandExecutor sequential chain callbacks.
     * @return The StepExecution tracker; callers may observe state and log.
     */
    StepExecution submit(String stepId, String stepKind,
                         Task task, RollbackPolicy rollbackPolicy,
                         Runnable onComplete);

    /**
     * Request cancellation of the currently executing step (if any).
     *
     * Cancelling a step that is already terminal is a no-op.
     * The onComplete callback registered at submit time is still invoked
     * (with a FAILED terminal state) so command chains remain unblocked.
     *
     * @param reason Short human-readable reason appended to the transition log.
     */
    void cancel(String reason);

    /**
     * Returns the most recently submitted StepExecution, or empty if none
     * has been submitted yet. The returned execution may be in any state,
     * including terminal.
     */
    Optional<StepExecution> getActiveExecution();
}
