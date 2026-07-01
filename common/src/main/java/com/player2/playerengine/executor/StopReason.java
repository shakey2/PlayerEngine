package com.player2.playerengine.executor;

/**
 * Normalized stop/failure reasons for tracked step execution (Phase A3).
 * Aligns with the Part A1 error taxonomy plus explicit cancellation categories.
 */
public enum StopReason {
    /** Transient conditions where retry may succeed (policy defines caps). */
    RETRYABLE,
    /** Unrecoverable under the current plan without replanning. */
    FATAL,
    /** Forward progress caused side effects; rollback/compensation applies. */
    COMPENSATABLE,
    /** Human must change configuration, permissions, inventory, or platform state. */
    USER_ACTION_REQUIRED,

    /** User or operator issued stop / adapter cancel. */
    CANCELLED_OPERATOR,
    /** Billing/prompt player disconnected per server policy. */
    CANCELLED_DISCONNECT,
    /** A new tracked step replaced the previous non-terminal execution. */
    CANCELLED_SUPERSEDED,
    /**
     * A survival-critical task (auto-eat / food gathering) preempted a running tracked task (e.g.
     * follow). Benign/expected — not a FATAL unexpected stop. Its name contains {@code CANCELLED_}, so
     * the raw state-machine chat dump at {@code TaskStepExecutorAdapter} is auto-suppressed, and the
     * command layer routes the model a truthful "paused to eat, re-issue to continue" note.
     */
    CANCELLED_SUPERSEDED_BY_SURVIVAL,
    /** The companion was despawned/dismissed/removed while a task was running; the run cannot resume. */
    CANCELLED_RESPAWN,
    /**
     * The followed player vanished (died / disconnected / changed dimension) while a follow was
     * running. An expected, graceful termination of {@code follow_player} — not a FATAL unexpected
     * stop and not an operator/companion cancel. Suppresses the raw state-machine chat dump (it is
     * replaced with a concise human line) and routes the model a truthful "do not retry" note.
     */
    FOLLOWED_TARGET_GONE,

    /** Local call-count hard limit or Joules-balance hard threshold reached; no new AI steps until resolved. */
    BUDGET_HARD_LIMIT
}
