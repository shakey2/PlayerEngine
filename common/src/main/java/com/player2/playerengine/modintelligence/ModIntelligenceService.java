package com.player2.playerengine.modintelligence;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentQueue;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentService;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceEnrichmentClient;
import com.player2.playerengine.modintelligence.ingest.CapabilityStore;
import com.player2.playerengine.modintelligence.ingest.ModIntelligencePaths;
import com.player2.playerengine.modintelligence.ingest.ModMetadataIngestionPipeline;
import com.player2.playerengine.modintelligence.query.CapabilityIndex;
import com.player2.playerengine.modintelligence.query.CapabilityQueryService;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ModIntelligenceService {
    private static final Logger LOGGER = PlayerEngine.LOGGER;

    private static final AtomicReference<CapabilityStore> storeRef = new AtomicReference<>(new CapabilityStore());
    private static final AtomicReference<CapabilityQueryService> queryRef =
            new AtomicReference<>(new CapabilityQueryService(storeRef.get()));
    private static final AtomicBoolean inspecting = new AtomicBoolean(false);
    private static final AtomicBoolean enriching = new AtomicBoolean(false);
    private static volatile String lastError = null;
    private static volatile String packFingerprint = "";
    private static volatile boolean playerJoinHookRegistered = false;
    private static volatile int lastBatchValidated = 0;
    private static volatile int lastBatchFailures = 0;
    private static volatile int lastBatchRemaining = 0;

    private ModIntelligenceService() {}

    /** Register Architectury listeners during mod init — never from tick or other event callbacks. */
    public static void registerEventHandlers() {
        registerPlayerJoinHook();
    }

    public static void initialize(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled()) {
            LOGGER.info("ModIntelligence: disabled by config");
            return;
        }
        try {
            CapabilityStore store = new CapabilityStore();
            store.loadFromDisk();
            storeRef.set(store);
            queryRef.set(new CapabilityQueryService(store));

            String expectedFp = store.getManifest() == null ? "" : store.getManifest().getPackFingerprint();
            packFingerprint = expectedFp;
            if (!CapabilityIndex.loadIfMatches(expectedFp)) {
                CapabilityIndex.rebuildFromMaps(store.getActiveMaps().values(), expectedFp);
            }

            if (cfg.isModIntelligenceInspectOnLaunch()) {
                PlayerEngine.getExecutor().execute(() -> runIngestion(server, false));
            } else if (cfg.isModIntelligenceEnrichmentEnabled()) {
                scheduleEnrichmentBatch(server);
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            LOGGER.error("ModIntelligence: initialize failed (non-fatal): {}", e.getMessage());
        }
    }

    private static void registerPlayerJoinHook() {
        if (playerJoinHookRegistered) {
            return;
        }
        playerJoinHookRegistered = true;
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!(player instanceof ServerPlayer sp)) {
                return;
            }
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
                return;
            }
            MinecraftServer server = sp.getServer();
            if (server != null) {
                scheduleEnrichmentBatch(server);
            }
        });
    }

    /**
     * Runs enrichment when billing is available and the queue is non-empty. Safe to call repeatedly.
     */
    public static void scheduleEnrichmentBatch(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
            return;
        }
        if (enriching.get()) {
            return;
        }
        if (CapabilityEnrichmentQueue.countQueuedLines() == 0) {
            return;
        }
        if (!ModIntelligenceEnrichmentClient.isBillingAvailable(server)) {
            LOGGER.debug("ModIntelligence enrichment: waiting for billing (player join or stored token)");
            return;
        }
        PlayerEngine.getExecutor().execute(() -> CapabilityEnrichmentService.runBatch(server));
    }

    public static void runIngestion(MinecraftServer server, boolean forceRebuild) {
        if (!inspecting.compareAndSet(false, true)) {
            return;
        }
        try {
            ModMetadataIngestionPipeline.IngestSummary summary =
                    new ModMetadataIngestionPipeline(server).run(forceRebuild);
            CapabilityStore store = new CapabilityStore();
            store.loadFromDisk();
            storeRef.set(store);
            queryRef.set(new CapabilityQueryService(store));
            packFingerprint = store.getManifest() == null ? "" : store.getManifest().getPackFingerprint();

            if (summary.queuedEnrichment() > 0) {
                scheduleEnrichmentBatch(server);
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            LOGGER.error("ModIntelligence: ingestion failed: {}", e.getMessage());
        } finally {
            inspecting.set(false);
        }
    }

    public static CapabilityQueryService queryService() {
        return queryRef.get();
    }

    public static CapabilityStore store() {
        return storeRef.get();
    }

    public static ModIntelligenceStatus status() {
        return statusFromStore(storeRef.get());
    }

    public static ModIntelligenceStatus statusFromStore(CapabilityStore store) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        int ready = 0, partial = 0, unknown = 0, failed = 0, tomb = 0, enriched = 0;
        for (var map : store.getActiveMaps().values()) {
            if (map.getEnrichment() != null) {
                enriched++;
            }
            switch (map.getStatus()) {
                case READY -> ready++;
                case PARTIAL -> partial++;
                case UNKNOWN -> unknown++;
                case FAILED -> failed++;
                case TOMBSTONED -> tomb++;
            }
        }
        int queued = CapabilityEnrichmentQueue.countQueuedLines();
        int enrichFailures = CapabilityEnrichmentQueue.countFailureLogLines();
        return new ModIntelligenceStatus(
                cfg.isModIntelligenceEnabled(),
                inspecting.get(),
                enriching.get(),
                store.getActiveMaps().size(),
                ready, partial, unknown, failed, tomb,
                queued, enriched, enrichFailures,
                lastBatchValidated, lastBatchFailures, lastBatchRemaining,
                packFingerprint,
                lastError
        );
    }

    public static void recordLastEnrichmentBatch(int validated, int failures, int remaining) {
        lastBatchValidated = validated;
        lastBatchFailures = failures;
        lastBatchRemaining = remaining;
    }

    public static void setEnriching(boolean value) {
        enriching.set(value);
    }
}
