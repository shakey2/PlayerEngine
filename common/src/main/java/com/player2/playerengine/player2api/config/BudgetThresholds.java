package com.player2.playerengine.player2api.config;

/**
 * Read-only view of budget threshold configuration (Phase A4).
 *
 * <p>Implemented by both {@link Player2ServerRuntimeConfig} (server-global, used for
 * OWNER_PAYS_ALL mode) and {@link PlayerBudgetConfig} (per-player, used for PROMPTER_PAYS mode).
 * {@link com.player2.playerengine.executor.BudgetTracker} and
 * {@link com.player2.playerengine.player2api.JoulesCache} accept this interface so enforcement
 * logic does not need to know which source provided the thresholds.
 */
public interface BudgetThresholds {
    /** Max AI calls per window before soft-limit behavior; 0 = disabled. */
    int getSoftBudgetCallsPerWindow();

    /** Max AI calls per window before hard-limit behavior; 0 = disabled. */
    int getHardBudgetCallsPerWindow();

    /** Rolling window duration for call-count limits, in minutes. */
    int getBudgetWindowMinutes();

    /** Cached Joules balance below which soft-limit behavior triggers; 0 = disabled. */
    int getSoftJoulesThreshold();

    /** Cached Joules balance below which hard-limit behavior triggers; 0 = disabled. */
    int getHardJoulesThreshold();

    /** How often to re-poll {@code GET /v1/joules} per billing key, in seconds. */
    int getJoulesRefreshIntervalSeconds();
}
