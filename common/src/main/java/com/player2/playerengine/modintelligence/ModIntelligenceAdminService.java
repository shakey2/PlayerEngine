package com.player2.playerengine.modintelligence;

import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentQueue;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceEnrichmentClient;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceSpendSafety;
import com.player2.playerengine.modintelligence.enrich.ModelBlacklist;
import com.player2.playerengine.modintelligence.query.CapabilityHit;
import com.player2.playerengine.modintelligence.query.CapabilityQuery;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.List;

/**
 * Permission-neutral service boundary for operator UIs. Callers must enforce permission level 2.
 */
public final class ModIntelligenceAdminService {
    public static final int MAX_UI_QUERY_LENGTH = 256;
    public static final int MAX_UI_QUERY_RESULTS = 20;

    public enum Setting {
        ENABLED,
        ENRICHMENT_ENABLED,
        MAX_ENRICHMENT_CALLS,
        BYPASS_LARGE_QUEUE_BUDGET_GATE
    }

    public enum SettingUpdateResult {
        UPDATED,
        INVALID_VALUE,
        SINGLEPLAYER_ONLY,
        LOAD_FAILED,
        SAVE_FAILED
    }

    public enum EnrichmentRequestResult {
        STARTED,
        QUEUED_AFTER_CURRENT,
        NOTHING_QUEUED,
        QUEUE_UNAVAILABLE,
        DISABLED,
        BILLING_UNAVAILABLE,
        EXECUTOR_UNAVAILABLE,
        HARD_BUDGET_LIMIT,
        MODEL_BLACKLIST_INVALID,
        LARGE_QUEUE_BUDGET_REQUIRED
    }

    public record SettingsSnapshot(boolean enabled,
                                   boolean enrichmentEnabled,
                                   int maxEnrichmentCalls,
                                   boolean bypassLargeQueueBudgetGate,
                                   boolean bypassSupported) {
    }

    private ModIntelligenceAdminService() {
    }

    public static SettingsSnapshot settings(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return new SettingsSnapshot(
                cfg.isModIntelligenceEnabled(),
                cfg.isModIntelligenceEnrichmentEnabled(),
                Math.max(0, cfg.getModIntelligenceMaxEnrichmentCallsPerLaunch()),
                cfg.isModIntelligenceBypassLargeQueueBudgetGate(),
                server != null && ModIntelligenceSpendSafety.bypassFlagsApply(server));
    }

    public static synchronized SettingUpdateResult updateSetting(MinecraftServer server, Setting setting, int value) {
        if (server == null || setting == null) {
            return SettingUpdateResult.INVALID_VALUE;
        }
        if (setting != Setting.MAX_ENRICHMENT_CALLS && value != 0 && value != 1) {
            return SettingUpdateResult.INVALID_VALUE;
        }
        if (setting == Setting.MAX_ENRICHMENT_CALLS && (value < 0 || value > 500)) {
            return SettingUpdateResult.INVALID_VALUE;
        }
        if (setting == Setting.BYPASS_LARGE_QUEUE_BUDGET_GATE
                && !ModIntelligenceSpendSafety.bypassFlagsApply(server)) {
            return SettingUpdateResult.SINGLEPLAYER_ONLY;
        }

        Player2ServerConfigHolder.FreshLoadResult fresh = Player2ServerConfigHolder.freshLoadResult();
        if (fresh.loadFailed()) {
            return SettingUpdateResult.LOAD_FAILED;
        }
        Player2ServerRuntimeConfig cfg = fresh.config();
        if (cfg == null) {
            return SettingUpdateResult.SAVE_FAILED;
        }
        boolean oldEnabled = cfg.isModIntelligenceEnabled();
        boolean oldEnrichmentEnabled = cfg.isModIntelligenceEnrichmentEnabled();
        apply(cfg, setting, value);
        if (!Player2ServerConfigHolder.setAndSaveChecked(cfg)) {
            ModIntelligenceService.refreshCachedStatus();
            return SettingUpdateResult.SAVE_FAILED;
        }

        ModIntelligenceService.refreshCachedStatus();
        boolean enabledNow = cfg.isModIntelligenceEnabled();
        boolean enrichmentEnabledNow = cfg.isModIntelligenceEnrichmentEnabled();
        if ((setting == Setting.ENABLED && !oldEnabled && enabledNow)
                || (setting == Setting.ENRICHMENT_ENABLED
                && !oldEnrichmentEnabled && enrichmentEnabledNow && enabledNow)) {
            ModIntelligenceService.requestIngestion(server, false);
        }
        return SettingUpdateResult.UPDATED;
    }

    private static void apply(Player2ServerRuntimeConfig cfg, Setting setting, int value) {
        switch (setting) {
            case ENABLED -> cfg.setModIntelligenceEnabled(value == 1);
            case ENRICHMENT_ENABLED -> cfg.setModIntelligenceEnrichmentEnabled(value == 1);
            case MAX_ENRICHMENT_CALLS -> cfg.setModIntelligenceMaxEnrichmentCallsPerLaunch(value);
            case BYPASS_LARGE_QUEUE_BUDGET_GATE -> cfg.setModIntelligenceBypassLargeQueueBudgetGate(value == 1);
        }
    }

    public static ModIntelligenceService.IngestionRequestResult requestRebuild(MinecraftServer server) {
        return ModIntelligenceService.requestIngestion(server, true);
    }

    public static EnrichmentRequestResult requestEnrichment(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (server == null || !cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
            return EnrichmentRequestResult.DISABLED;
        }
        int queued;
        try {
            queued = CapabilityEnrichmentQueue.countQueuedLinesChecked();
        } catch (IOException e) {
            ModIntelligenceService.recordOperationStatus(ModIntelligenceService.OperationStatus.ENRICHMENT_FAILED);
            return EnrichmentRequestResult.QUEUE_UNAVAILABLE;
        }
        if (queued <= 0) {
            return EnrichmentRequestResult.NOTHING_QUEUED;
        }
        if (!ModIntelligenceEnrichmentClient.isBillingAvailable(server)) {
            return EnrichmentRequestResult.BILLING_UNAVAILABLE;
        }
        if (ModIntelligenceEnrichmentClient.wouldExceedHardBudget(server)) {
            return EnrichmentRequestResult.HARD_BUDGET_LIMIT;
        }
        ModelBlacklist.ModelBlacklistSnapshot blacklist = ModelBlacklist.load();
        var deferred = ModIntelligenceSpendSafety.preflight(server, queued, blacklist, false);
        if (deferred.isPresent()) {
            return deferred.get() == ModIntelligenceSpendSafety.DeferReason.BLACKLIST_INVALID
                    ? EnrichmentRequestResult.MODEL_BLACKLIST_INVALID
                    : EnrichmentRequestResult.LARGE_QUEUE_BUDGET_REQUIRED;
        }
        return switch (ModIntelligenceService.scheduleEnrichmentBatch(server, null)) {
            case STARTED -> EnrichmentRequestResult.STARTED;
            case ALREADY_RUNNING -> EnrichmentRequestResult.QUEUED_AFTER_CURRENT;
            case NOTHING_QUEUED -> EnrichmentRequestResult.NOTHING_QUEUED;
            case DISABLED -> EnrichmentRequestResult.DISABLED;
            case BILLING_UNAVAILABLE -> EnrichmentRequestResult.BILLING_UNAVAILABLE;
            case EXECUTOR_UNAVAILABLE -> EnrichmentRequestResult.EXECUTOR_UNAVAILABLE;
        };
    }

    public static List<CapabilityHit> query(String rawQuery, int requestedLimit) {
        String query = normalizeQuery(rawQuery);
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query is blank");
        }
        int limit = Math.max(1, Math.min(MAX_UI_QUERY_RESULTS, requestedLimit));
        double minConfidence = Player2ServerConfigHolder.get().getModIntelligenceMinQueryConfidence();
        List<CapabilityHit> hits = ModIntelligenceService.queryService().query(
                CapabilityQuery.defaults().setText(query).setMinConfidence(minConfidence), limit);
        return List.copyOf(hits);
    }

    public static String normalizeQuery(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        String query = rawQuery.replace('\r', ' ').replace('\n', ' ').trim();
        for (int i = 0; i < query.length(); i++) {
            if (java.lang.Character.isISOControl(query.charAt(i))) {
                throw new IllegalArgumentException("query contains control characters");
            }
        }
        if (query.length() > MAX_UI_QUERY_LENGTH) {
            throw new IllegalArgumentException("query is too long");
        }
        return query;
    }
}
