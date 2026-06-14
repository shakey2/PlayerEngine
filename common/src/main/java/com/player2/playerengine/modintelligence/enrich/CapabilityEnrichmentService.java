package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.ModIntelligenceService;
import com.player2.playerengine.modintelligence.capability.CapabilityGson;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.ingest.CapabilityStore;
import com.player2.playerengine.modintelligence.ingest.CapabilityStoreWriter;
import com.player2.playerengine.modintelligence.ingest.EntryFingerprint;
import com.player2.playerengine.modintelligence.ingest.ModIntelligenceManifest;
import com.player2.playerengine.modintelligence.ingest.ModIntelligencePaths;
import com.player2.playerengine.modintelligence.query.CapabilityIndex;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CapabilityEnrichmentService {
    private static final Logger LOGGER = PlayerEngine.LOGGER;

    private CapabilityEnrichmentService() {}

    public static void runBatch(MinecraftServer server) {
        runBatch(server, null, false);
    }

    public static void runBatch(MinecraftServer server, Integer limitOverride) {
        runBatch(server, limitOverride, limitOverride != null);
    }

    /**
     * @param limitOverride per-batch call cap from an explicit {@code /playerengine capability enrich
     *        <limit>} invocation — overrides the config cap for this batch (0 = unlimited) and skips
     *        the B4.5 large-queue budget gate (informed consent); {@code null} = config governs.
     *        The joules budget hard/soft limits always remain enforced.
     * @param explicit {@code true} when this batch came from an explicit operator command (even with a
     *        {@code null} limit); explicit batches that lose the single-batch lock race are stashed in
     *        the pending-override slot instead of being dropped.
     */
    public static void runBatch(MinecraftServer server, Integer limitOverride, boolean explicit) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
            return;
        }
        if (!ModIntelligenceEnrichmentClient.isBillingAvailable(server)) {
            LOGGER.info("ModIntelligence enrichment: deferred — no online player or stored owner token for billing");
            return;
        }
        if (ModIntelligenceEnrichmentClient.wouldExceedHardBudget(server)) {
            LOGGER.info("ModIntelligence enrichment: deferred — budget hard limit would block calls");
            return;
        }

        if (!ModIntelligenceService.tryBeginEnrichment()) {
            // Lost the lock race to a batch scheduled in the same window. An explicit batch must not
            // vanish: park it in the pending slot; the running batch's finishEnrichment() starts it
            // (parkExplicit re-checks the lock so a winner finishing in this window can't strand it).
            if (explicit) {
                ModIntelligenceService.parkExplicit(server, limitOverride);
                LOGGER.info("ModIntelligence enrichment: another batch already running — "
                        + "explicit batch parked as pending follow-up");
            } else {
                LOGGER.debug("ModIntelligence enrichment: another batch already running — auto batch skipped");
            }
            return;
        }
        int calls = 0;
        int failures = 0;
        int remainingCount = 0;
        ModelBlacklist.ModelBlacklistSnapshot blacklist = ModelBlacklist.load();
        ModelBlacklist.setBatchSnapshot(blacklist);
        try {
            List<CapabilityMap> queue = CapabilityEnrichmentQueue.loadQueued();
            if (queue.isEmpty()) {
                // Visible (info) so a pending explicit follow-up that finds nothing left is verifiable.
                LOGGER.info("ModIntelligence enrichment: queue empty — nothing to enrich (calls=0)");
                ModIntelligenceService.recordLastEnrichmentBatch(0, 0, 0);
                return;
            }

            Optional<ModIntelligenceSpendSafety.DeferReason> defer =
                    ModIntelligenceSpendSafety.preflight(server, queue.size(), blacklist, limitOverride != null);
            if (defer.isPresent()) {
                ModIntelligenceSpendSafety.notifyPlayer(
                        server, ModIntelligenceSpendSafety.messageFor(defer.get(), queue.size(), blacklist));
                remainingCount = queue.size();
                ModIntelligenceService.recordLastEnrichmentBatch(0, 0, remainingCount);
                return;
            }

            int configuredMax = cfg.getModIntelligenceMaxEnrichmentCallsPerLaunch();
            int maxCalls = limitOverride != null ? limitOverride : configuredMax;
            boolean unlimited = maxCalls <= 0;
            int maxFailures = cfg.getModIntelligenceMaxEnrichmentFailuresPerLaunch();
            LOGGER.info("ModIntelligence enrichment: starting batch queued={} maxCalls={}{} maxFailures={}",
                    queue.size(), unlimited ? "unlimited" : maxCalls,
                    limitOverride != null
                            ? " (command override; config " + (configuredMax <= 0 ? "unlimited" : configuredMax) + ")"
                            : "",
                    maxFailures);

            CapabilityStore store = ModIntelligenceService.store();
            List<CapabilityMap> pending = new ArrayList<>(queue);

            for (int i = 0; i < queue.size(); i++) {
                if ((!unlimited && calls >= maxCalls) || failures >= maxFailures) {
                    break;
                }

                CapabilityMap map = queue.get(i);
                String key = CapabilityStore.entryKey(map);
                try {
                    ConversationHistory history = new ConversationHistory(CapabilityEnrichmentPrompt.systemPrompt());
                    JsonObject userMsg = new JsonObject();
                    userMsg.addProperty("role", "user");
                    userMsg.addProperty("content", CapabilityEnrichmentPrompt.buildUserJson(map));
                    history.addHistory(userMsg, false, null);
                    String response = ModIntelligenceEnrichmentClient.complete(server, history);
                    CapabilityEnrichmentResult parsed =
                            CapabilityEnrichmentValidator.parseAndValidate(response, map.getSubjectKind());
                    CapabilityEnrichmentMerger.merge(map, parsed);
                    store.getActiveMaps().put(key, map);
                    markManifestEnriched(key);
                    calls++;
                    pending.removeIf(m -> CapabilityStore.entryKey(m).equals(key));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if ("billing_unavailable".equals(msg) || "budget_hard_limit".equals(msg)) {
                        LOGGER.info("ModIntelligence enrichment: stopping batch ({})", msg);
                        break;
                    }
                    if ("model_blacklisted".equals(msg)
                            || "model_missing".equals(msg)
                            || "model_blacklist_invalid".equals(msg)) {
                        ModIntelligenceSpendSafety.notifyBatchAbort(server, msg, null);
                        logFailure(map, "EnrichmentAbort", msg);
                        break;
                    }
                    if (msg.contains("422") || msg.contains("user input rejected")) {
                        LOGGER.warn("ModIntelligence enrichment: stopping batch — Player2 rejected request "
                                + "(check response_format / prompt). First error: {}", msg);
                        break;
                    }
                    failures++;
                    logFailure(map, e.getClass().getSimpleName(), msg);
                    LOGGER.debug("ModIntelligence enrichment failed for {}: {}",
                            map.getSubjectId(), msg);
                }

                persistPendingQueue(pending);
            }

            remainingCount = pending.size();
            persistPendingQueue(pending);
            if (calls > 0) {
                CapabilityStoreWriter.writeJsonl(ModIntelligencePaths.capabilityMapsFile(),
                        store.getActiveMaps().values());
                String fp = store.getManifest() == null ? "" : store.getManifest().getPackFingerprint();
                CapabilityIndex.rebuildFromMaps(store.getActiveMaps().values(), fp);
            }
            LOGGER.info("ModIntelligence enrichment: calls={} failures={} remaining={}",
                    calls, failures, remainingCount);
        } catch (Exception e) {
            LOGGER.warn("ModIntelligence enrichment batch failed: {}", e.getMessage());
        } finally {
            ModelBlacklist.clearBatchSnapshot();
            ModIntelligenceService.recordLastEnrichmentBatch(calls, failures, remainingCount);
            // Releases the lock AND starts the pending explicit follow-up batch, if one arrived
            // mid-flight — on every completion path (success, failure, early stop).
            ModIntelligenceService.finishEnrichment(server);
        }
    }

    private static void persistPendingQueue(List<CapabilityMap> pending) {
        try {
            CapabilityEnrichmentQueue.saveQueued(pending);
        } catch (Exception e) {
            LOGGER.warn("ModIntelligence enrichment: could not persist queue: {}", e.getMessage());
        }
    }

    private static void markManifestEnriched(String entryKey) {
        try {
            if (!ModIntelligencePaths.manifestFile().exists()) {
                return;
            }
            ModIntelligenceManifest manifest;
            try (FileReader reader = new FileReader(ModIntelligencePaths.manifestFile(), StandardCharsets.UTF_8)) {
                manifest = CapabilityGson.instance().fromJson(reader, ModIntelligenceManifest.class);
            }
            if (manifest == null || manifest.getEntries() == null) {
                return;
            }
            EntryFingerprint fp = manifest.getEntries().get(entryKey);
            if (fp != null) {
                fp.setEnriched(true);
                CapabilityStoreWriter.writeJson(ModIntelligencePaths.manifestFile(), manifest);
            }
        } catch (Exception e) {
            LOGGER.debug("ModIntelligence: could not update manifest enriched flag for {}: {}",
                    entryKey, e.getMessage());
        }
    }

    private static void logFailure(CapabilityMap map, String failureClass, String message) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("timestamp", System.currentTimeMillis());
            o.addProperty("subjectKind", map.getSubjectKind().name());
            o.addProperty("subjectId", map.getSubjectId());
            o.addProperty("failureClass", failureClass);
            o.addProperty("message", message == null ? "" : message.substring(0, Math.min(200, message.length())));
            try (BufferedWriter w = new BufferedWriter(new FileWriter(
                    ModIntelligencePaths.enrichmentFailuresFile(), StandardCharsets.UTF_8, true))) {
                w.write(CapabilityGson.instance().toJson(o));
                w.newLine();
            }
        } catch (Exception ignored) {
        }
    }
}
