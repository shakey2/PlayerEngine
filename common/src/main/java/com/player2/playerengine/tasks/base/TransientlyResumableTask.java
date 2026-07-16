package com.player2.playerengine.tasks.base;

/**
 * Explicit opt-in contract for a multi-tick task that can restart from a live-state checkpoint.
 *
 * <p>A resumable task must keep the same logical root object so completion callbacks continue to
 * observe the eventual terminal outcome. It must checkpoint durable identity (for example a selected
 * farm center), quiesce inputs/pathing/behaviour pushes, and rebuild remaining work from the live
 * world and inventory on its next {@code onStart()}. It must not retain or replay an in-flight child
 * task blindly. The task framework detaches the active child after the checkpoint hook returns, so
 * the root must reconstruct any remaining child work rather than retaining a child reference of its
 * own. Whole-operation deadlines must remain cumulative across restarts.</p>
 *
 * <p>Operator cancellation, genuine task replacement, disconnect, and runner shutdown are terminal
 * operations. They never call {@link #prepareForTransientResume(TaskSuspensionCause)}.</p>
 */
public interface TransientlyResumableTask {

    /**
     * Attempts to checkpoint and quiesce this task for a safety-preserving interruption.
     *
     * @return {@code true} only when restarting this same logical task is safe
     */
    boolean prepareForTransientResume(TaskSuspensionCause cause);

    /**
     * Terminalizes this logical submission when its pending resume is explicitly discarded. This
     * may also be called after chain assignment but before the first tick, when no checkpoint was
     * necessary; implementations must therefore tolerate an otherwise uninitialized run.
     */
    void onTransientResumeAbandoned();

    /**
     * Releases root-owned resources that must remain below child-owned LIFO frames until every
     * active child has completed its interruption cleanup. Implementations should keep this
     * idempotent because it is also invoked on terminal discard.
     */
    default void afterChildrenStopped() {
    }
}
