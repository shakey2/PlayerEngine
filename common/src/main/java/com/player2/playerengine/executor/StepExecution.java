package com.player2.playerengine.executor;

import com.player2.playerengine.util.Debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable lifecycle tracker for a single TaskStep execution.
 *
 * Created in PENDING state by TaskStepExecutorAdapter. Every state change is
 * validated against the allowed transition table and emitted to the internal
 * debug log so transitions are observable without a code-level debugger.
 *
 * Package-private constructor and transitionTo — only TaskStepExecutorAdapter
 * drives transitions; callers observe via getState() / getLog().
 */
public class StepExecution {

    private final String stepId;
    private final String stepKind;
    private final long startTimeMs;
    private StepState state;
    private final List<String> log;

    StepExecution(String stepId, String stepKind) {
        this.stepId = stepId;
        this.stepKind = stepKind;
        this.startTimeMs = System.currentTimeMillis();
        this.state = StepState.PENDING;
        this.log = new ArrayList<>();
        Debug.logInternal("[StepExecution] " + stepId + " [" + stepKind + "] created in PENDING");
    }

    /**
     * Transition to {@code next} state, recording {@code reason} in the log.
     *
     * @throws IllegalStateException if the transition is not allowed by the
     *                               StepState table (programming error).
     */
    void transitionTo(StepState next, String reason) {
        if (!isValidTransition(this.state, next)) {
            throw new IllegalStateException(
                "[StepExecution] " + stepId + " illegal transition "
                + this.state + " -> " + next + " (reason: " + reason + ")"
            );
        }
        String entry = "[" + this.state + " -> " + next + "] " + reason;
        this.log.add(entry);
        this.state = next;
        Debug.logInternal("[StepExecution] " + stepId + " [" + stepKind + "] " + entry);
    }

    private static boolean isValidTransition(StepState from, StepState to) {
        switch (from) {
            case PENDING:
                return to == StepState.RUNNING || to == StepState.BLOCKED;
            case RUNNING:
                return to == StepState.SUCCEEDED
                    || to == StepState.FAILED
                    || to == StepState.ROLLED_BACK;
            case FAILED:
                return to == StepState.ROLLED_BACK;
            default:
                return false;
        }
    }

    /** Current lifecycle state. */
    public StepState getState() {
        return this.state;
    }

    /** Stable identifier within the enclosing Plan (for logging and ordering). */
    public String getStepId() {
        return this.stepId;
    }

    /** Descriptor of the action family, e.g. {@code follow_player}. */
    public String getStepKind() {
        return this.stepKind;
    }

    /** Wall-clock millisecond at which this execution was created. */
    public long getStartTimeMs() {
        return this.startTimeMs;
    }

    /**
     * Ordered, human-readable log of every state transition.
     * Each entry has the form {@code "[FROM -> TO] reason"}.
     */
    public List<String> getLog() {
        return Collections.unmodifiableList(this.log);
    }

    /**
     * Returns the last entry in the transition log, or an empty string if no transitions
     * have occurred yet. Useful for concise single-line failure summaries.
     */
    public String getLastLogEntry() {
        return log.isEmpty() ? "" : log.get(log.size() - 1);
    }

    /**
     * Wall-clock milliseconds elapsed since this execution was created.
     */
    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /**
     * Returns {@code true} when no further transitions are possible.
     * Terminal states: SUCCEEDED, FAILED, ROLLED_BACK, BLOCKED.
     */
    public boolean isTerminal() {
        return this.state == StepState.SUCCEEDED
            || this.state == StepState.FAILED
            || this.state == StepState.ROLLED_BACK
            || this.state == StepState.BLOCKED;
    }

    @Override
    public String toString() {
        return "StepExecution{id=" + stepId + ", kind=" + stepKind + ", state=" + state + "}";
    }
}
