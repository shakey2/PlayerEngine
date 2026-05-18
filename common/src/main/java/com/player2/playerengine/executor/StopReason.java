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

    /** Local call-count hard limit or Joules-balance hard threshold reached; no new AI steps until resolved. */
    BUDGET_HARD_LIMIT
}
