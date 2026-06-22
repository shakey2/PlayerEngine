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

    // --- Phase B3: RAG live prompt ---

    /** Max tools injected into the NPC system prompt per turn (clamped 5–20 on read). */
    private int ragTopK = 12;
    /** When retrieval fails, fall back to the full command list instead of always-include only. */
    private boolean ragFallbackToFullList = true;
    /** When false, NPC prompts use the full command list (pre-B3 behavior). */
    private boolean ragLiveEnabled = true;
    /** Minimum alphanumeric goal length before running retrieval (clamped 1–16 on read). */
    private int ragMinGoalChars = 3;

    // --- Phase B5: Failure-driven alias learning ---

    private boolean enableDeepCheckRephrase = false;
    private boolean enableAliasLearning = false;
    private boolean enableDeepCheckMessage = false;
    private int deepCheckMaxAttemptsPerTurn = 2;
    private int deepCheckCallsPerWindow = 3;
    private int deepCheckWindowMinutes = 10;
    private double deepCheckWeakBelowScore = 0.015;
    private double deepCheckWeakGapRatio = 0.15;
    private double deepCheckWeakTokenCoverage = 0.35;
    private boolean forceDeepCheckOnEmpty = true;

    // --- Phase B4: Mod intelligence ---

    private boolean modIntelligenceEnabled = true;
    private boolean modIntelligenceInspectOnLaunch = true;
    private boolean modIntelligenceEnrichmentEnabled = false;
    private int modIntelligenceMaxInspectEntriesPerLaunch = 5000;
    /** Per-batch enrichment API call cap; 0 = unlimited (the joules budget remains the spend safeguard). */
    private int modIntelligenceMaxEnrichmentCallsPerLaunch = 50;
    private int modIntelligenceMaxEnrichmentFailuresPerLaunch = 20;
    private int modIntelligenceQueryTopK = 12;
    private double modIntelligenceMinQueryConfidence = 0.5;
    private boolean modIntelligenceObserveRuntimeNbt = false;
    private int modIntelligenceMaxObservedSamplesPerSubject = 5;
    /**
     * When true on integrated singleplayer, skip B4.5 large-queue budget gate.
     * Ignored on dedicated servers (per-player limits always apply there).
     */
    private boolean modIntelligenceBypassLargeQueueBudgetGate = false;
    /**
     * When true on integrated singleplayer, skip B4.5 model blacklist checks for enrichment.
     * Ignored on dedicated servers.
     */
    private boolean modIntelligenceBypassModelBlacklist = false;

    /** When true, owner clients may send {@code tts_playback_done} to early-clear speaker cooldown. */
    private boolean botTtsPlaybackAckEnabled = false;

    // --- Bot lifecycle: auto-respawn + permadeath toggles ---

    /**
     * When true, the server-level lifecycle config wins over each player's per-player config
     * (default ON: server overrides). Consulted only on dedicated servers; integrated/LAN/SP
     * always use the server config. See {@code BotLifecycleSettingsResolver}.
     */
    private boolean serverOverridesPlayerConfig = true;
    /** Server-level auto-respawn value (used in SP/LAN, or on a dedicated server when override is ON). */
    private boolean serverAutoRespawn = true;
    /**
     * Server-level bot permadeath, tri-state (Model A): {@code null} = unset, so the resolver falls back
     * to {@code world.isHardcore()}; explicit {@code true}/{@code false} is honored verbatim (OFF works
     * even in hardcore). Gson omits the key when {@code null}, and an absent key deserializes back to
     * {@code null} (= unset). Nullable on purpose — do not change to a primitive boolean.
     */
    private Boolean serverBotPermadeath = null;

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

    // --- Phase B3 getters/setters ---

    public int getRagTopK() { return ragTopK; }

    public void setRagTopK(int v) { this.ragTopK = v; }

    /** Clamped to [5, 20]. */
    public int getRagTopKClamped() {
        int k = ragTopK;
        if (k < 5) return 5;
        if (k > 20) return 20;
        return k;
    }

    public boolean isRagFallbackToFullList() { return ragFallbackToFullList; }

    public void setRagFallbackToFullList(boolean ragFallbackToFullList) {
        this.ragFallbackToFullList = ragFallbackToFullList;
    }

    public boolean isRagLiveEnabled() { return ragLiveEnabled; }

    public void setRagLiveEnabled(boolean ragLiveEnabled) {
        this.ragLiveEnabled = ragLiveEnabled;
    }

    public int getRagMinGoalChars() { return ragMinGoalChars; }

    public void setRagMinGoalChars(int v) { this.ragMinGoalChars = v; }

    /** Clamped to [1, 16]. */
    public int getRagMinGoalCharsClamped() {
        int m = ragMinGoalChars;
        if (m < 1) return 1;
        if (m > 16) return 16;
        return m;
    }

    public boolean isModIntelligenceEnabled() { return modIntelligenceEnabled; }
    public void setModIntelligenceEnabled(boolean v) { this.modIntelligenceEnabled = v; }

    public boolean isModIntelligenceInspectOnLaunch() { return modIntelligenceInspectOnLaunch; }
    public void setModIntelligenceInspectOnLaunch(boolean v) { this.modIntelligenceInspectOnLaunch = v; }

    public boolean isModIntelligenceEnrichmentEnabled() { return modIntelligenceEnrichmentEnabled; }
    public void setModIntelligenceEnrichmentEnabled(boolean v) { this.modIntelligenceEnrichmentEnabled = v; }

    public int getModIntelligenceMaxInspectEntriesPerLaunch() { return modIntelligenceMaxInspectEntriesPerLaunch; }
    public void setModIntelligenceMaxInspectEntriesPerLaunch(int v) {
        this.modIntelligenceMaxInspectEntriesPerLaunch = v;
    }

    public int getModIntelligenceMaxEnrichmentCallsPerLaunch() { return modIntelligenceMaxEnrichmentCallsPerLaunch; }
    public void setModIntelligenceMaxEnrichmentCallsPerLaunch(int v) {
        this.modIntelligenceMaxEnrichmentCallsPerLaunch = v;
    }

    public int getModIntelligenceMaxEnrichmentFailuresPerLaunch() {
        return modIntelligenceMaxEnrichmentFailuresPerLaunch;
    }
    public void setModIntelligenceMaxEnrichmentFailuresPerLaunch(int v) {
        this.modIntelligenceMaxEnrichmentFailuresPerLaunch = v;
    }

    public int getModIntelligenceQueryTopK() { return modIntelligenceQueryTopK; }
    public void setModIntelligenceQueryTopK(int v) { this.modIntelligenceQueryTopK = v; }

    public int getModIntelligenceQueryTopKClamped() {
        int k = modIntelligenceQueryTopK;
        if (k < 1) return 1;
        if (k > 50) return 50;
        return k;
    }

    public double getModIntelligenceMinQueryConfidence() { return modIntelligenceMinQueryConfidence; }
    public void setModIntelligenceMinQueryConfidence(double v) { this.modIntelligenceMinQueryConfidence = v; }

    public boolean isModIntelligenceObserveRuntimeNbt() { return modIntelligenceObserveRuntimeNbt; }
    public void setModIntelligenceObserveRuntimeNbt(boolean v) { this.modIntelligenceObserveRuntimeNbt = v; }

    public int getModIntelligenceMaxObservedSamplesPerSubject() {
        return modIntelligenceMaxObservedSamplesPerSubject;
    }
    public void setModIntelligenceMaxObservedSamplesPerSubject(int v) {
        this.modIntelligenceMaxObservedSamplesPerSubject = v;
    }

    public boolean isModIntelligenceBypassLargeQueueBudgetGate() {
        return modIntelligenceBypassLargeQueueBudgetGate;
    }

    public void setModIntelligenceBypassLargeQueueBudgetGate(boolean v) {
        this.modIntelligenceBypassLargeQueueBudgetGate = v;
    }

    public boolean isModIntelligenceBypassModelBlacklist() {
        return modIntelligenceBypassModelBlacklist;
    }

    public void setModIntelligenceBypassModelBlacklist(boolean v) {
        this.modIntelligenceBypassModelBlacklist = v;
    }

    public boolean isBotTtsPlaybackAckEnabled() {
        return botTtsPlaybackAckEnabled;
    }

    public void setBotTtsPlaybackAckEnabled(boolean botTtsPlaybackAckEnabled) {
        this.botTtsPlaybackAckEnabled = botTtsPlaybackAckEnabled;
    }

    // --- Bot lifecycle getters/setters ---

    public boolean isServerOverridesPlayerConfig() {
        return serverOverridesPlayerConfig;
    }

    public void setServerOverridesPlayerConfig(boolean serverOverridesPlayerConfig) {
        this.serverOverridesPlayerConfig = serverOverridesPlayerConfig;
    }

    public boolean isServerAutoRespawn() {
        return serverAutoRespawn;
    }

    public void setServerAutoRespawn(boolean serverAutoRespawn) {
        this.serverAutoRespawn = serverAutoRespawn;
    }

    /** Nullable on purpose: {@code null} = unset (resolver falls back to {@code world.isHardcore()}). */
    public Boolean getServerBotPermadeath() {
        return serverBotPermadeath;
    }

    /** Accepts {@code null} (unset) or an explicit {@code true}/{@code false} (Model A). */
    public void setServerBotPermadeath(Boolean serverBotPermadeath) {
        this.serverBotPermadeath = serverBotPermadeath;
    }

    // --- Phase B5 getters/setters ---

    public boolean isEnableDeepCheckRephrase() { return enableDeepCheckRephrase; }
    public void setEnableDeepCheckRephrase(boolean v) { this.enableDeepCheckRephrase = v; }

    public boolean isEnableAliasLearning() { return enableAliasLearning; }
    public void setEnableAliasLearning(boolean v) { this.enableAliasLearning = v; }

    public boolean isEnableDeepCheckMessage() { return enableDeepCheckMessage; }
    public void setEnableDeepCheckMessage(boolean v) { this.enableDeepCheckMessage = v; }

    public int getDeepCheckMaxAttemptsPerTurn() { return deepCheckMaxAttemptsPerTurn; }
    public void setDeepCheckMaxAttemptsPerTurn(int v) { this.deepCheckMaxAttemptsPerTurn = v; }

    /** Clamped to [0, 3]. */
    public int getDeepCheckMaxAttemptsPerTurnClamped() {
        int n = deepCheckMaxAttemptsPerTurn;
        if (n < 0) return 0;
        if (n > 3) return 3;
        return n;
    }

    public int getDeepCheckCallsPerWindow() { return deepCheckCallsPerWindow; }
    public void setDeepCheckCallsPerWindow(int v) { this.deepCheckCallsPerWindow = v; }

    public int getDeepCheckCallsPerWindowClamped() {
        int n = deepCheckCallsPerWindow;
        if (n < 0) return 0;
        if (n > 100) return 100;
        return n;
    }

    public int getDeepCheckWindowMinutes() { return deepCheckWindowMinutes; }
    public void setDeepCheckWindowMinutes(int v) { this.deepCheckWindowMinutes = v; }

    public int getDeepCheckWindowMinutesClamped() {
        int m = deepCheckWindowMinutes;
        if (m < 1) return 1;
        if (m > 1440) return 1440;
        return m;
    }

    public double getDeepCheckWeakBelowScore() { return deepCheckWeakBelowScore; }
    public void setDeepCheckWeakBelowScore(double v) { this.deepCheckWeakBelowScore = v; }

    public double getDeepCheckWeakBelowScoreClamped() {
        double s = deepCheckWeakBelowScore;
        if (s < 0.0) return 0.0;
        if (s > 1.0) return 1.0;
        return s;
    }

    public double getDeepCheckWeakGapRatio() { return deepCheckWeakGapRatio; }
    public void setDeepCheckWeakGapRatio(double v) { this.deepCheckWeakGapRatio = v; }

    public double getDeepCheckWeakGapRatioClamped() {
        double g = deepCheckWeakGapRatio;
        if (g < 0.0) return 0.0;
        if (g > 1.0) return 1.0;
        return g;
    }

    public double getDeepCheckWeakTokenCoverage() { return deepCheckWeakTokenCoverage; }
    public void setDeepCheckWeakTokenCoverage(double v) { this.deepCheckWeakTokenCoverage = v; }

    public double getDeepCheckWeakTokenCoverageClamped() {
        double c = deepCheckWeakTokenCoverage;
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }

    public boolean isForceDeepCheckOnEmpty() { return forceDeepCheckOnEmpty; }
    public void setForceDeepCheckOnEmpty(boolean v) { this.forceDeepCheckOnEmpty = v; }
}
