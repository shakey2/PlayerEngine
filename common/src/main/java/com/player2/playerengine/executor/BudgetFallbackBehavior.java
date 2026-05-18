package com.player2.playerengine.executor;

/**
 * What to do when the soft budget limit (call-count or Joules-balance) is reached (Phase A4).
 */
public enum BudgetFallbackBehavior {
    /** Route the next completion through the configured fallback AI profile's base URL. */
    SWITCH_PROFILE,
    /** Block new AI calls as if the hard limit were reached. */
    HARD_STOP
}
