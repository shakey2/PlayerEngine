package com.player2.playerengine.player2api.config;

import com.player2.playerengine.executor.BudgetFallbackBehavior;

/**
 * Persisted server-side settings for Player2 API routing (see DESIGN.md §6).
 *
 * <p>Implements {@link BudgetThresholds} so it can be passed directly to budget enforcement
 * utilities (used in OWNER_PAYS_ALL mode or as the server-wide fallback).
 */
public class Player2ServerRuntimeConfig implements BudgetThresholds {
    private Player2PayerMode payerMode = Player2PayerMode.PROMPTER_PAYS;
    private boolean dedicatedClientProxy = false;
    private boolean ownerOfflineServerContinuation = false;
    /** When true, automatons only receive chat that opens with their character name or short name. */
    private boolean callByNameChat = true;
    /** Client id used for optional client-side heartbeat when enabled (e.g. NPC game id). */
    private String heartbeatClientId = "player2-ai-npc-minecraft";
    /** Max AI companions alive in the world per owning player (1–20). */
    private int maxSpawnedCompanionsPerPlayer = 3;
    /** Max distinct character IDs with on-disk storage per player; 0 = unlimited (0–100). */
    private int maxStoredCharacterIdsPerPlayer = 20;

    // --- Phase A4: Cost-safety controls ---

    /** Warn/switch profile when call count exceeds this per window; 0 = disabled. */
    private int softBudgetCallsPerWindow = 0;
    /** Block new AI calls when call count exceeds this per window; 0 = disabled. */
    private int hardBudgetCallsPerWindow = 0;
    /** Rolling window duration for call-count limits, in minutes [1–1440]. */
    private int budgetWindowMinutes = 60;

    /** Warn/switch profile when cached Joules balance falls below this; 0 = disabled. */
    private int softJoulesThreshold = 0;
    /** Block new AI calls when cached Joules balance falls below this; 0 = disabled. */
    private int hardJoulesThreshold = 0;
    /** How often to re-poll GET /v1/joules per billing key, in seconds [60–86400]. */
    private int joulesRefreshIntervalSeconds = 300;

    /** AI profile name to route to at soft limits (null = no profile override). */
    private String fallbackProfile = null;
    /** What to do at soft limits: SWITCH_PROFILE or HARD_STOP. */
    private BudgetFallbackBehavior budgetFallbackBehavior = BudgetFallbackBehavior.HARD_STOP;

    public Player2PayerMode getPayerMode() {
        return payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public void setPayerMode(Player2PayerMode payerMode) {
        this.payerMode = payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public boolean isDedicatedClientProxy() {
        return dedicatedClientProxy;
    }

    public void setDedicatedClientProxy(boolean dedicatedClientProxy) {
        this.dedicatedClientProxy = dedicatedClientProxy;
    }

    public boolean isOwnerOfflineServerContinuation() {
        return ownerOfflineServerContinuation;
    }

    public void setOwnerOfflineServerContinuation(boolean ownerOfflineServerContinuation) {
        this.ownerOfflineServerContinuation = ownerOfflineServerContinuation;
    }

    public boolean isCallByNameChat() {
        return callByNameChat;
    }

    public void setCallByNameChat(boolean callByNameChat) {
        this.callByNameChat = callByNameChat;
    }

    public String getHeartbeatClientId() {
        return heartbeatClientId == null || heartbeatClientId.isBlank()
                ? "player2-ai-npc-minecraft"
                : heartbeatClientId;
    }

    public void setHeartbeatClientId(String heartbeatClientId) {
        this.heartbeatClientId = heartbeatClientId;
    }

    public int getMaxSpawnedCompanionsPerPlayer() {
        return maxSpawnedCompanionsPerPlayer;
    }

    public void setMaxSpawnedCompanionsPerPlayer(int maxSpawnedCompanionsPerPlayer) {
        this.maxSpawnedCompanionsPerPlayer = maxSpawnedCompanionsPerPlayer;
    }

    public int getMaxStoredCharacterIdsPerPlayer() {
        return maxStoredCharacterIdsPerPlayer;
    }

    public void setMaxStoredCharacterIdsPerPlayer(int maxStoredCharacterIdsPerPlayer) {
        this.maxStoredCharacterIdsPerPlayer = maxStoredCharacterIdsPerPlayer;
    }

    // --- Phase A4 getters/setters ---

    public int getSoftBudgetCallsPerWindow() { return softBudgetCallsPerWindow; }
    public void setSoftBudgetCallsPerWindow(int v) { this.softBudgetCallsPerWindow = Math.max(0, v); }

    public int getHardBudgetCallsPerWindow() { return hardBudgetCallsPerWindow; }
    public void setHardBudgetCallsPerWindow(int v) { this.hardBudgetCallsPerWindow = Math.max(0, v); }

    public int getBudgetWindowMinutes() { return budgetWindowMinutes; }
    public void setBudgetWindowMinutes(int v) { this.budgetWindowMinutes = v; }

    public int getSoftJoulesThreshold() { return softJoulesThreshold; }
    public void setSoftJoulesThreshold(int v) { this.softJoulesThreshold = Math.max(0, v); }

    public int getHardJoulesThreshold() { return hardJoulesThreshold; }
    public void setHardJoulesThreshold(int v) { this.hardJoulesThreshold = Math.max(0, v); }

    public int getJoulesRefreshIntervalSeconds() { return joulesRefreshIntervalSeconds; }
    public void setJoulesRefreshIntervalSeconds(int v) { this.joulesRefreshIntervalSeconds = v; }

    public String getFallbackProfile() { return fallbackProfile; }
    public void setFallbackProfile(String v) { this.fallbackProfile = (v == null || v.isBlank()) ? null : v.trim(); }

    public BudgetFallbackBehavior getBudgetFallbackBehavior() {
        return budgetFallbackBehavior == null ? BudgetFallbackBehavior.HARD_STOP : budgetFallbackBehavior;
    }
    public void setBudgetFallbackBehavior(BudgetFallbackBehavior v) {
        this.budgetFallbackBehavior = v == null ? BudgetFallbackBehavior.HARD_STOP : v;
    }
}
