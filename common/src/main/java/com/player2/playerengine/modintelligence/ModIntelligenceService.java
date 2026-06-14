package com.player2.playerengine.modintelligence;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentQueue;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentService;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceEnrichmentClient;
import com.player2.playerengine.modintelligence.ingest.CapabilityStore;
import com.player2.playerengine.modintelligence.ingest.ModMetadataIngestionPipeline;
import com.player2.playerengine.modintelligence.query.CapabilityIndex;
import com.player2.playerengine.modintelligence.query.CapabilityQueryService;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.MinecraftServer;
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
    /**
     * Single pending-override slot for an explicit {@code /playerengine capability enrich} command that
     * arrived while a batch was in flight. Latest explicit invocation wins; auto-schedulers never
     * populate it; consuming it clears it. A {@code null} limit (bare {@code enrich}, config governs)
     * is still an explicit request, hence the wrapper record rather than a raw Integer.
     */
    private static final AtomicReference<PendingExplicitRequest> pendingExplicitRequest = new AtomicReference<>();

    private record PendingExplicitRequest(Integer limitOverride) {}

    /** Outcome of a schedule attempt, so explicit command callers can report truthfully. */
    public enum ScheduleResult {
        /** Batch submitted to the worker executor. */
        STARTED,
        /** A batch is in flight; for explicit callers the request was stored in the pending slot. */
        ALREADY_RUNNING,
        /** Enrichment queue is empty. */
        NOTHING_QUEUED,
        /** ModIntelligence or enrichment disabled in config. */
        DISABLED,
        /** No online player or stored owner token to bill against yet. */
        BILLING_UNAVAILABLE
    }
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
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server != null) {
                scheduleEnrichmentBatch(server);
            }
        });
    }

    /** Auto-scheduled batch (init, player join, post-ingestion) — guard outcomes intentionally ignored. */
    public static void scheduleEnrichmentBatch(MinecraftServer server) {
        schedule(server, null, false);
    }

    /**
     * Explicit {@code /playerengine capability enrich [limit]} invocation. Never silently dropped: if a
     * batch is already in flight the request is stored in the single pending-override slot and started
     * by {@link #finishEnrichment} when that batch completes.
     *
     * @param limitOverride per-batch call cap — overrides the config cap for this batch (0 = unlimited);
     *        {@code null} = command given without an argument (config governs, still explicit).
     * @return what actually happened, so the command can report truthfully.
     */
    public static ScheduleResult scheduleEnrichmentBatch(MinecraftServer server, Integer limitOverride) {
        return schedule(server, limitOverride, true);
    }

    private static ScheduleResult schedule(MinecraftServer server, Integer limitOverride, boolean explicit) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
            return ScheduleResult.DISABLED;
        }
        if (enriching.get()) {
            if (explicit) {
                LOGGER.info("ModIntelligence enrichment: batch in flight — stored explicit follow-up request (limit={})",
                        describeLimit(limitOverride));
                parkExplicit(server, limitOverride);
            }
            return ScheduleResult.ALREADY_RUNNING;
        }
        if (CapabilityEnrichmentQueue.countQueuedLines() == 0) {
            return ScheduleResult.NOTHING_QUEUED;
        }
        if (!ModIntelligenceEnrichmentClient.isBillingAvailable(server)) {
            LOGGER.debug("ModIntelligence enrichment: waiting for billing (player join or stored token)");
            return ScheduleResult.BILLING_UNAVAILABLE;
        }
        PlayerEngine.getExecutor().execute(() -> CapabilityEnrichmentService.runBatch(server, limitOverride, explicit));
        return ScheduleResult.STARTED;
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

    /**
     * Atomically claims the single-batch enrichment lock — called by
     * {@link CapabilityEnrichmentService#runBatch} before doing any work. Replaces the old plain
     * {@code setEnriching(true)}, which allowed two near-simultaneously scheduled batches to run
     * concurrently.
     *
     * @return {@code true} if this caller now owns the batch; {@code false} if another batch is running.
     */
    public static boolean tryBeginEnrichment() {
        return enriching.compareAndSet(false, true);
    }

    /**
     * Parks an explicit batch request that arrived while another batch holds the lock (latest explicit
     * request wins; auto batches that lose are simply skipped). After stashing, re-checks the lock: if
     * the in-flight batch finished between the caller's check and the stash, its
     * {@link #finishEnrichment} already ran and would never see this slot — drain it here so the
     * request cannot be stranded until some unrelated future batch completes. Called from both
     * {@code schedule()}'s ALREADY_RUNNING branch and {@code runBatch}'s lost-lock-race branch; the
     * {@code getAndSet(null)} in {@link #drainPendingExplicit} keeps consumption single even if this
     * re-check races a concurrent {@code finishEnrichment}.
     */
    public static void parkExplicit(MinecraftServer server, Integer limitOverride) {
        pendingExplicitRequest.set(new PendingExplicitRequest(limitOverride));
        if (!enriching.get()) {
            drainPendingExplicit(server);
        }
    }

    /**
     * Batch completion (success, failure, or early stop): releases the lock, then starts the pending
     * explicit follow-up batch if one was requested mid-flight. Called from {@code runBatch}'s finally.
     */
    public static void finishEnrichment(MinecraftServer server) {
        enriching.set(false);
        drainPendingExplicit(server);
    }

    /**
     * Consumes (and clears) the pending explicit slot and submits the follow-up batch directly —
     * deliberately not via {@link #schedule}: a pending explicit batch must run even if the queue
     * drained meanwhile (it exits normally with calls=0), and {@code runBatch} re-checks
     * disabled/billing itself. No endless chaining: the slot holds at most one request, only explicit
     * commands populate it, and {@code getAndSet(null)} consumption clears it.
     */
    private static void drainPendingExplicit(MinecraftServer server) {
        PendingExplicitRequest pending = pendingExplicitRequest.getAndSet(null);
        if (pending == null) {
            return;
        }
        Integer limit = pending.limitOverride();
        LOGGER.info("ModIntelligence enrichment: starting pending explicit follow-up batch (limit={})",
                describeLimit(limit));
        PlayerEngine.getExecutor().execute(() -> CapabilityEnrichmentService.runBatch(server, limit, true));
    }

    private static String describeLimit(Integer limit) {
        if (limit == null) {
            return "config";
        }
        return limit <= 0 ? "unlimited" : String.valueOf(limit);
    }
}
