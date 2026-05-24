package com.player2.playerengine.player2api.config;

import com.player2.playerengine.util.helpers.ConfigHelper;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads {@code playerengine/server_player2.json}. Thread-safe reads.
 */
public final class Player2ServerConfigHolder {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CONFIG_PATH = "server_player2.json";

    private static volatile Player2ServerRuntimeConfig cached = defaultConfig();

    private Player2ServerConfigHolder() {
    }

    private static Player2ServerRuntimeConfig defaultConfig() {
        return new Player2ServerRuntimeConfig();
    }

    public static Player2ServerRuntimeConfig get() {
        return cached;
    }

    public static void load() {
        Player2ServerRuntimeConfig next = ConfigHelper.getConfig(CONFIG_PATH, Player2ServerConfigHolder::defaultConfig,
                Player2ServerRuntimeConfig.class);
        validateAndFix(next);
        cached = next;
        LOGGER.info("Player2 server config: payerMode={} dedicated={} ownerOfflineContinue={} callByNameChat={} maxSpawnedCompanions={} maxStoredCharacterIds={}",
                cached.getPayerMode(), cached.isDedicatedClientProxy(), cached.isOwnerOfflineServerContinuation(),
                cached.isCallByNameChat(), cached.getMaxSpawnedCompanionsPerPlayer(), cached.getMaxStoredCharacterIdsPerPlayer());
        LOGGER.info("Player2 RAG config: ragLiveEnabled={} ragTopK={} ragFallbackToFullList={} ragMinGoalChars={}",
                cached.isRagLiveEnabled(), cached.getRagTopKClamped(), cached.isRagFallbackToFullList(),
                cached.getRagMinGoalCharsClamped());
        LOGGER.info("Player2 TTS pacing config: botTtsPlaybackAckEnabled={}", cached.isBotTtsPlaybackAckEnabled());
    }

    public static void save() {
        validateAndFix(cached);
        ConfigHelper.saveConfig(CONFIG_PATH, cached);
    }

    public static void setAndSave(Player2ServerRuntimeConfig cfg) {
        validateAndFix(cfg);
        cached = cfg;
        save();
    }

    public static void validateAndFix(Player2ServerRuntimeConfig c) {
        if (c == null) {
            return;
        }
        if (c.isDedicatedClientProxy() && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: dedicatedClientProxy and ownerOfflineServerContinuation are mutually exclusive; disabling ownerOfflineServerContinuation.");
            c.setOwnerOfflineServerContinuation(false);
        }
        if (c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: ownerOfflineServerContinuation only applies to OWNER_PAYS_ALL; disabling.");
            c.setOwnerOfflineServerContinuation(false);
        }
        int maxSpawn = c.getMaxSpawnedCompanionsPerPlayer();
        if (maxSpawn < 1 || maxSpawn > 20) {
            LOGGER.warn("Player2 config: maxSpawnedCompanionsPerPlayer out of range 1–20 (got {}); using 3.", maxSpawn);
            c.setMaxSpawnedCompanionsPerPlayer(3);
        }
        int maxStored = c.getMaxStoredCharacterIdsPerPlayer();
        if (maxStored < 0 || maxStored > 100) {
            LOGGER.warn("Player2 config: maxStoredCharacterIdsPerPlayer out of range 0–100 (got {}); using 20.", maxStored);
            c.setMaxStoredCharacterIdsPerPlayer(20);
        }

        // Phase A4: budget field validation
        int window = c.getBudgetWindowMinutes();
        if (window < 1 || window > 1440) {
            LOGGER.warn("Player2 config: budgetWindowMinutes out of range 1–1440 (got {}); using 60.", window);
            c.setBudgetWindowMinutes(60);
        }
        int joulesRefresh = c.getJoulesRefreshIntervalSeconds();
        if (joulesRefresh < 60 || joulesRefresh > 86400) {
            LOGGER.warn("Player2 config: joulesRefreshIntervalSeconds out of range 60–86400 (got {}); using 300.", joulesRefresh);
            c.setJoulesRefreshIntervalSeconds(300);
        }
        int soft = c.getSoftBudgetCallsPerWindow();
        int hard = c.getHardBudgetCallsPerWindow();
        if (soft > 0 && hard > 0 && soft >= hard) {
            LOGGER.warn("Player2 config: softBudgetCallsPerWindow ({}) >= hardBudgetCallsPerWindow ({}) — soft should be lower than hard.", soft, hard);
        }

        // Phase B3: RAG field validation (clamp on read via getters; fix stored values if out of range)
        int ragTopK = c.getRagTopK();
        if (ragTopK < 5 || ragTopK > 20) {
            LOGGER.warn("Player2 config: ragTopK out of range 5–20 (got {}); using clamped value {}.", ragTopK, c.getRagTopKClamped());
            c.setRagTopK(c.getRagTopKClamped());
        }
        int ragMin = c.getRagMinGoalChars();
        if (ragMin < 1 || ragMin > 16) {
            LOGGER.warn("Player2 config: ragMinGoalChars out of range 1–16 (got {}); using clamped value {}.", ragMin, c.getRagMinGoalCharsClamped());
            c.setRagMinGoalChars(c.getRagMinGoalCharsClamped());
        }

        int inspectCap = c.getModIntelligenceMaxInspectEntriesPerLaunch();
        if (inspectCap < 0 || inspectCap > 50000) {
            LOGGER.warn("Player2 config: modIntelligenceMaxInspectEntriesPerLaunch out of range 0–50000 (got {}); using 5000.", inspectCap);
            c.setModIntelligenceMaxInspectEntriesPerLaunch(5000);
        }
        int enrichCalls = c.getModIntelligenceMaxEnrichmentCallsPerLaunch();
        if (enrichCalls < 0 || enrichCalls > 1000) {
            LOGGER.warn("Player2 config: modIntelligenceMaxEnrichmentCallsPerLaunch out of range 0–1000 (got {}); using 50.", enrichCalls);
            c.setModIntelligenceMaxEnrichmentCallsPerLaunch(50);
        }
        int enrichFails = c.getModIntelligenceMaxEnrichmentFailuresPerLaunch();
        if (enrichFails < 1 || enrichFails > 500) {
            LOGGER.warn("Player2 config: modIntelligenceMaxEnrichmentFailuresPerLaunch out of range 1–500 (got {}); using 20.", enrichFails);
            c.setModIntelligenceMaxEnrichmentFailuresPerLaunch(20);
        }
        int capTopK = c.getModIntelligenceQueryTopK();
        if (capTopK < 1 || capTopK > 50) {
            c.setModIntelligenceQueryTopK(c.getModIntelligenceQueryTopKClamped());
        }
        double minConf = c.getModIntelligenceMinQueryConfidence();
        if (minConf < 0.0 || minConf > 1.0) {
            LOGGER.warn("Player2 config: modIntelligenceMinQueryConfidence out of range 0–1 (got {}); using 0.5.", minConf);
            c.setModIntelligenceMinQueryConfidence(0.5);
        }
        int obsCap = c.getModIntelligenceMaxObservedSamplesPerSubject();
        if (obsCap < 0 || obsCap > 50) {
            LOGGER.warn("Player2 config: modIntelligenceMaxObservedSamplesPerSubject out of range 0–50 (got {}); using 5.", obsCap);
            c.setModIntelligenceMaxObservedSamplesPerSubject(5);
        }
    }

    /** When true, companion controller tick may send /v1/health from the server for owner-paid mode. */
    public static boolean shouldSendServerControllerHeartbeat(Player2ServerRuntimeConfig c,
            boolean ownerTokenPresent, boolean ownerClientOnline) {
        if (c.getPayerMode() != Player2PayerMode.OWNER_PAYS_ALL) {
            return false;
        }
        if (c.isDedicatedClientProxy()) {
            return false;
        }
        if (ownerClientOnline) {
            return true;
        }
        return c.isOwnerOfflineServerContinuation() && ownerTokenPresent;
    }

    /** True when each player's client should periodically send /v1/health for usage attribution. */
    public static boolean shouldClientSendPlayerHeartbeat(Player2ServerRuntimeConfig c) {
        return c.isDedicatedClientProxy() || c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS;
    }

    public static boolean serverHasPlayer2ConnectivityGuess(MinecraftServer server) {
        return com.player2.playerengine.player2api.utils.LocalAPIDiscovery.getLocalApiUrl() != null
                || true;
    }
}
