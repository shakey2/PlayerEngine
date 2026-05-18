package com.player2.playerengine.player2api.config;

/**
 * Per-player budget settings (Phase A4).
 *
 * <p>Stored as {@code player2npc/persistentdata/owners/<uuid>/player-budget.json} in the world
 * folder. Used in PROMPTER_PAYS mode so each player controls their own AI call limits independently.
 *
 * <p>All threshold fields default to 0 (disabled) so players without a saved config get no limits —
 * matching pre-A4 behavior. Does not include {@code fallbackProfile} or {@code budgetFallbackBehavior}
 * since profile routing is a server-level decision.
 */
public class PlayerBudgetConfig implements BudgetThresholds {

    /** Soft call-count limit per window; 0 = disabled. */
    private int softBudgetCallsPerWindow = 0;
    /** Hard call-count limit per window; 0 = disabled. */
    private int hardBudgetCallsPerWindow = 0;
    /** Rolling window duration in minutes [1–1440]. */
    private int budgetWindowMinutes = 60;

    /** Joules balance below which soft limit triggers; 0 = disabled. */
    private int softJoulesThreshold = 0;
    /** Joules balance below which hard limit triggers; 0 = disabled. */
    private int hardJoulesThreshold = 0;
    /** How often to poll {@code GET /v1/joules}, in seconds [60–86400]. */
    private int joulesRefreshIntervalSeconds = 300;

    public PlayerBudgetConfig() {
    }

    @Override
    public int getSoftBudgetCallsPerWindow() { return softBudgetCallsPerWindow; }
    public void setSoftBudgetCallsPerWindow(int v) { this.softBudgetCallsPerWindow = Math.max(0, v); }

    @Override
    public int getHardBudgetCallsPerWindow() { return hardBudgetCallsPerWindow; }
    public void setHardBudgetCallsPerWindow(int v) { this.hardBudgetCallsPerWindow = Math.max(0, v); }

    @Override
    public int getBudgetWindowMinutes() { return budgetWindowMinutes; }
    public void setBudgetWindowMinutes(int v) { this.budgetWindowMinutes = Math.min(Math.max(v, 1), 1440); }

    @Override
    public int getSoftJoulesThreshold() { return softJoulesThreshold; }
    public void setSoftJoulesThreshold(int v) { this.softJoulesThreshold = Math.max(0, v); }

    @Override
    public int getHardJoulesThreshold() { return hardJoulesThreshold; }
    public void setHardJoulesThreshold(int v) { this.hardJoulesThreshold = Math.max(0, v); }

    @Override
    public int getJoulesRefreshIntervalSeconds() { return joulesRefreshIntervalSeconds; }
    public void setJoulesRefreshIntervalSeconds(int v) {
        this.joulesRefreshIntervalSeconds = Math.min(Math.max(v, 60), 86400);
    }

    /** True when all thresholds are disabled (no limits set). */
    public boolean isAllDisabled() {
        return softBudgetCallsPerWindow == 0 && hardBudgetCallsPerWindow == 0
                && softJoulesThreshold == 0 && hardJoulesThreshold == 0;
    }
}
