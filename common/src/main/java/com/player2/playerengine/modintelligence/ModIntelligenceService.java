package com.player2.playerengine.modintelligence;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentQueue;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentService;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceEnrichmentClient;
import com.player2.playerengine.modintelligence.enrich.ModelBlacklist;
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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ModIntelligenceService {
    private static final Logger LOGGER = PlayerEngine.LOGGER;

    private static final AtomicReference<CapabilityStore> storeRef = new AtomicReference<>(new CapabilityStore());
    private static final AtomicReference<CapabilityQueryService> queryRef =
            new AtomicReference<>(new CapabilityQueryService(storeRef.get()));
    private enum MutationOperation { INGESTING, ENRICHING }
    public static final class OperationClaim {
        private final long generation;
        private final MutationOperation operation;

        private OperationClaim(long generation, MutationOperation operation) {
            this.generation = generation;
            this.operation = operation;
        }
    }
    private static final AtomicReference<OperationClaim> activeOperation = new AtomicReference<>();
    private static final AtomicLong sessionGeneration = new AtomicLong();
    private static final Object lifecycleLock = new Object();
    private static final AtomicBoolean inspecting = new AtomicBoolean(false);
    private static final AtomicBoolean enriching = new AtomicBoolean(false);
    private static final AtomicReference<ModIntelligenceStatus> cachedStatus = new AtomicReference<>(
            new ModIntelligenceStatus(true, false, false,
                    0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0,
                    "idle", "", null));
    /**
     * Single pending-override slot for an explicit {@code /playerengine capability enrich} command that
     * arrived while a batch was in flight. Latest explicit invocation wins; auto-schedulers never
     * populate it; consuming it clears it. A {@code null} limit (bare {@code enrich}, config governs)
     * is still an explicit request, hence the wrapper record rather than a raw Integer.
     */
    private static final AtomicReference<PendingExplicitRequest> pendingExplicitRequest = new AtomicReference<>();

    private record PendingExplicitRequest(long generation, MinecraftServer server, Integer limitOverride) {}

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
        BILLING_UNAVAILABLE,
        /** Background worker pool is unavailable or rejected the batch. */
        EXECUTOR_UNAVAILABLE
    }
    public enum IngestionRequestResult {
        STARTED,
        ALREADY_RUNNING,
        DISABLED,
        EXECUTOR_UNAVAILABLE
    }
    public enum OperationStatus {
        IDLE("idle"),
        INGESTION_RUNNING("ingestion_running"),
        INGESTION_COMPLETE("ingestion_complete"),
        INGESTION_FAILED("ingestion_failed"),
        INGESTION_EXECUTOR_UNAVAILABLE("ingestion_executor_unavailable"),
        ENRICHMENT_RUNNING("enrichment_running"),
        ENRICHMENT_COMPLETE("enrichment_complete"),
        ENRICHMENT_PARTIAL("enrichment_partial"),
        ENRICHMENT_DEFERRED("enrichment_deferred"),
        ENRICHMENT_EMPTY("enrichment_empty"),
        ENRICHMENT_FAILED("enrichment_failed"),
        ENRICHMENT_EXECUTOR_UNAVAILABLE("enrichment_executor_unavailable");

        private final String code;

        OperationStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
    private static volatile String lastError = null;
    private static volatile OperationStatus lastOperationStatus = OperationStatus.IDLE;
    private static volatile String packFingerprint = "";
    private static volatile boolean playerJoinHookRegistered = false;
    private static volatile int lastBatchValidated = 0;
    private static volatile int lastBatchFailures = 0;
    private static volatile int lastBatchRemaining = 0;

    private ModIntelligenceService() {}

    /** Register Architectury listeners during mod init - never from tick or other event callbacks. */
    public static void registerEventHandlers() {
        registerPlayerJoinHook();
    }

    public static void initialize(MinecraftServer server) {
        synchronized (lifecycleLock) {
            sessionGeneration.incrementAndGet();
            resetRuntimeState();
        }
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled()) {
            LOGGER.info("ModIntelligence: disabled by config");
            refreshCachedStatus();
            return;
        }
        try {
            CapabilityStore store = new CapabilityStore();
            store.loadFromDisk();

            String expectedFp = store.getManifest() == null ? "" : store.getManifest().getPackFingerprint();
            packFingerprint = expectedFp;
            if (!CapabilityIndex.loadIfMatches(expectedFp)) {
                CapabilityIndex.rebuildFromMaps(store.getActiveMaps().values(), expectedFp);
            }
            storeRef.set(store);
            queryRef.set(new CapabilityQueryService(store));
            refreshCachedStatus();

            if (cfg.isModIntelligenceInspectOnLaunch()) {
                requestIngestion(server, false);
            } else if (cfg.isModIntelligenceEnrichmentEnabled()) {
                scheduleEnrichmentBatch(server);
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            lastOperationStatus = OperationStatus.INGESTION_FAILED;
            LOGGER.error("ModIntelligence: initialize failed (non-fatal): {}", e.getMessage());
            refreshCachedStatus();
        }
    }

    private static void resetRuntimeState() {
        pendingExplicitRequest.set(null);
        activeOperation.set(null);
        inspecting.set(false);
        enriching.set(false);
        lastOperationStatus = OperationStatus.IDLE;
        lastError = null;
        packFingerprint = "";
        lastBatchValidated = 0;
        lastBatchFailures = 0;
        lastBatchRemaining = 0;
        ModelBlacklist.clearBatchSnapshot();
        CapabilityStore emptyStore = new CapabilityStore();
        storeRef.set(emptyStore);
        queryRef.set(new CapabilityQueryService(emptyStore));
    }

    public static long currentGeneration() {
        return sessionGeneration.get();
    }

    public static boolean isCurrentGeneration(long generation) {
        return generation == sessionGeneration.get();
    }

    public static boolean isCurrentOperation(OperationClaim claim) {
        return claim != null
                && isCurrentGeneration(claim.generation)
                && activeOperation.get() == claim;
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

    /** Auto-scheduled batch (init, player join, post-ingestion) - guard outcomes intentionally ignored. */
    public static void scheduleEnrichmentBatch(MinecraftServer server) {
        schedule(server, null, false);
    }

    /**
     * Explicit {@code /playerengine capability enrich [limit]} invocation. Never silently dropped: if a
     * batch is already in flight the request is stored in the single pending-override slot and started
     * by {@link #finishEnrichment} when that batch completes.
     *
     * @param limitOverride per-batch call cap - overrides the config cap for this batch (0 = unlimited);
     *        {@code null} = command given without an argument (config governs, still explicit).
     * @return what actually happened, so the command can report truthfully.
     */
    public static ScheduleResult scheduleEnrichmentBatch(MinecraftServer server, Integer limitOverride) {
        return schedule(server, limitOverride, true);
    }

    private static ScheduleResult schedule(MinecraftServer server, Integer limitOverride, boolean explicit) {
        long generation = currentGeneration();
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled() || !cfg.isModIntelligenceEnrichmentEnabled()) {
            return ScheduleResult.DISABLED;
        }
        if (activeOperation.get() != null) {
            if (explicit) {
                LOGGER.info("ModIntelligence enrichment: batch in flight - stored explicit follow-up request (limit={})",
                        describeLimit(limitOverride));
                parkExplicit(server, limitOverride, generation);
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
        var executor = PlayerEngine.getExecutor();
        if (executor == null) {
            recordOperationStatus(generation, OperationStatus.ENRICHMENT_EXECUTOR_UNAVAILABLE);
            return ScheduleResult.EXECUTOR_UNAVAILABLE;
        }
        try {
            executor.execute(() -> CapabilityEnrichmentService.runBatch(
                    server, limitOverride, explicit, generation));
            return ScheduleResult.STARTED;
        } catch (RejectedExecutionException e) {
            recordOperationStatus(generation, OperationStatus.ENRICHMENT_EXECUTOR_UNAVAILABLE);
            LOGGER.warn("ModIntelligence enrichment: background executor rejected batch: {}", e.getMessage());
            return ScheduleResult.EXECUTOR_UNAVAILABLE;
        }
    }

    public static IngestionRequestResult requestIngestion(MinecraftServer server, boolean forceRebuild) {
        if (server == null || !Player2ServerConfigHolder.get().isModIntelligenceEnabled()) {
            return IngestionRequestResult.DISABLED;
        }
        long generation = currentGeneration();
        OperationClaim claim = new OperationClaim(generation, MutationOperation.INGESTING);
        if (!activeOperation.compareAndSet(null, claim)) {
            return IngestionRequestResult.ALREADY_RUNNING;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentOperation(claim)) {
                activeOperation.compareAndSet(claim, null);
                return IngestionRequestResult.ALREADY_RUNNING;
            }
            lastOperationStatus = OperationStatus.INGESTION_RUNNING;
            lastError = null;
            inspecting.set(true);
            publishRuntimeStatus();
        }
        var executor = PlayerEngine.getExecutor();
        if (executor == null) {
            synchronized (lifecycleLock) {
                if (activeOperation.compareAndSet(claim, null)) {
                    inspecting.set(false);
                    lastOperationStatus = OperationStatus.INGESTION_EXECUTOR_UNAVAILABLE;
                    publishRuntimeStatus();
                }
            }
            return IngestionRequestResult.EXECUTOR_UNAVAILABLE;
        }
        try {
            executor.execute(() -> runClaimedIngestion(server, forceRebuild, claim));
            return IngestionRequestResult.STARTED;
        } catch (RejectedExecutionException e) {
            synchronized (lifecycleLock) {
                if (activeOperation.compareAndSet(claim, null)) {
                    inspecting.set(false);
                    lastOperationStatus = OperationStatus.INGESTION_EXECUTOR_UNAVAILABLE;
                    publishRuntimeStatus();
                }
            }
            LOGGER.warn("ModIntelligence: ingestion executor unavailable: {}", e.getMessage());
            return IngestionRequestResult.EXECUTOR_UNAVAILABLE;
        }
    }

    public static void runIngestion(MinecraftServer server, boolean forceRebuild) {
        OperationClaim claim = new OperationClaim(currentGeneration(), MutationOperation.INGESTING);
        if (!activeOperation.compareAndSet(null, claim)) {
            return;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentOperation(claim)) {
                activeOperation.compareAndSet(claim, null);
                return;
            }
            lastOperationStatus = OperationStatus.INGESTION_RUNNING;
            lastError = null;
            inspecting.set(true);
            publishRuntimeStatus();
        }
        runClaimedIngestion(server, forceRebuild, claim);
    }

    private static void runClaimedIngestion(MinecraftServer server, boolean forceRebuild, OperationClaim claim) {
        if (!isCurrentOperation(claim)) {
            activeOperation.compareAndSet(claim, null);
            return;
        }
        boolean scheduleEnrichmentAfterIngestion = false;
        try {
            ModMetadataIngestionPipeline.IngestSummary summary =
                    new ModMetadataIngestionPipeline(server).run(forceRebuild);
            if (!isCurrentOperation(claim)) {
                return;
            }
            CapabilityStore store = new CapabilityStore();
            store.loadFromDisk();
            synchronized (lifecycleLock) {
                if (!isCurrentOperation(claim)) {
                    return;
                }
                storeRef.set(store);
                queryRef.set(new CapabilityQueryService(store));
                packFingerprint = store.getManifest() == null ? "" : store.getManifest().getPackFingerprint();
                scheduleEnrichmentAfterIngestion = summary.queuedEnrichment() > 0;
                lastOperationStatus = OperationStatus.INGESTION_COMPLETE;
                lastError = null;
            }
        } catch (Exception e) {
            synchronized (lifecycleLock) {
                if (isCurrentOperation(claim)) {
                    lastError = e.getMessage();
                    lastOperationStatus = OperationStatus.INGESTION_FAILED;
                    LOGGER.error("ModIntelligence: ingestion failed: {}", e.getMessage());
                }
            }
        } finally {
            synchronized (lifecycleLock) {
                if (activeOperation.compareAndSet(claim, null)) {
                    inspecting.set(false);
                    refreshCachedStatus();
                    boolean explicitPending = pendingExplicitRequest.get() != null;
                    if (explicitPending) {
                        drainPendingExplicit(server);
                    } else if (scheduleEnrichmentAfterIngestion) {
                        scheduleEnrichmentBatch(server);
                    }
                }
            }
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

    public static ModIntelligenceStatus cachedStatus() {
        return cachedStatus.get();
    }

    public static ModIntelligenceStatus statusFromStore(CapabilityStore store) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        int ready = 0, partial = 0, unknown = 0, failed = 0, tomb = 0, enriched = 0;
        int total;
        synchronized (store) {
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
            total = store.getActiveMaps().size();
        }
        int queued = CapabilityEnrichmentQueue.countQueuedLines();
        int enrichFailures = CapabilityEnrichmentQueue.countFailureLogLines();
        return new ModIntelligenceStatus(
                cfg.isModIntelligenceEnabled(),
                inspecting.get(),
                enriching.get(),
                total,
                ready, partial, unknown, failed, tomb,
                queued, enriched, enrichFailures,
                lastBatchValidated, lastBatchFailures, lastBatchRemaining,
                lastOperationStatus.code(),
                packFingerprint,
                lastError
        );
    }

    static void refreshCachedStatus() {
        try {
            cachedStatus.set(statusFromStore(storeRef.get()));
        } catch (RuntimeException e) {
            LOGGER.debug("ModIntelligence: cached status refresh skipped: {}", e.getMessage());
            publishRuntimeStatus();
        }
    }

    private static void publishRuntimeStatus() {
        ModIntelligenceStatus previous = cachedStatus.get();
        cachedStatus.set(new ModIntelligenceStatus(
                Player2ServerConfigHolder.get().isModIntelligenceEnabled(),
                inspecting.get(),
                enriching.get(),
                previous.getTotalEntries(),
                previous.getReadyEntries(),
                previous.getPartialEntries(),
                previous.getUnknownEntries(),
                previous.getFailedEntries(),
                previous.getTombstonedEntries(),
                previous.getQueuedEnrichments(),
                previous.getEnrichedEntries(),
                previous.getEnrichmentFailures(),
                lastBatchValidated,
                lastBatchFailures,
                lastBatchRemaining,
                lastOperationStatus.code(),
                packFingerprint,
                lastError));
    }

    public static void recordLastEnrichmentBatch(int validated, int failures, int remaining) {
        lastBatchValidated = validated;
        lastBatchFailures = failures;
        lastBatchRemaining = remaining;
    }

    public static void recordLastEnrichmentBatch(OperationClaim claim, int validated, int failures, int remaining) {
        synchronized (lifecycleLock) {
            if (isCurrentOperation(claim)) {
                recordLastEnrichmentBatch(validated, failures, remaining);
            }
        }
    }

    public static void recordOperationStatus(OperationStatus status) {
        lastOperationStatus = status == null ? OperationStatus.IDLE : status;
        refreshCachedStatus();
    }

    public static void recordOperationStatus(long generation, OperationStatus status) {
        synchronized (lifecycleLock) {
            if (isCurrentGeneration(generation)) {
                recordOperationStatus(status);
            }
        }
    }

    public static void recordOperationStatus(OperationClaim claim, OperationStatus status) {
        synchronized (lifecycleLock) {
            if (isCurrentOperation(claim)) {
                recordOperationStatus(status);
            }
        }
    }

    /**
     * Atomically claims the single-batch enrichment lock - called by
     * {@link CapabilityEnrichmentService#runBatch} before doing any work. Replaces the old plain
     * {@code setEnriching(true)}, which allowed two near-simultaneously scheduled batches to run
     * concurrently.
     *
     * @return the ownership claim, or {@code null} if another batch is running or this session is stale.
     */
    public static OperationClaim tryBeginEnrichment(long generation) {
        if (!isCurrentGeneration(generation)) {
            return null;
        }
        OperationClaim claim = new OperationClaim(generation, MutationOperation.ENRICHING);
        if (!activeOperation.compareAndSet(null, claim)) {
            return null;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentOperation(claim) || !enriching.compareAndSet(false, true)) {
                activeOperation.compareAndSet(claim, null);
                return null;
            }
            lastOperationStatus = OperationStatus.ENRICHMENT_RUNNING;
            lastError = null;
            publishRuntimeStatus();
            return claim;
        }
    }

    /**
     * Parks an explicit batch request that arrived while another batch holds the lock (latest explicit
     * request wins; auto batches that lose are simply skipped). After stashing, re-checks the lock: if
     * the in-flight batch finished between the caller's check and the stash, its
     * {@link #finishEnrichment} already ran and would never see this slot - drain it here so the
     * request cannot be stranded until some unrelated future batch completes. Called from both
     * {@code schedule()}'s ALREADY_RUNNING branch and {@code runBatch}'s lost-lock-race branch; the
     * compare-and-set consumption in {@link #drainPendingExplicit} keeps consumption single even if this
     * re-check races a concurrent {@code finishEnrichment}.
     */
    public static void parkExplicit(MinecraftServer server, Integer limitOverride) {
        parkExplicit(server, limitOverride, currentGeneration());
    }

    public static void parkExplicit(MinecraftServer server, Integer limitOverride, long generation) {
        synchronized (lifecycleLock) {
            if (!isCurrentGeneration(generation)) {
                return;
            }
            pendingExplicitRequest.set(new PendingExplicitRequest(generation, server, limitOverride));
            if (activeOperation.get() == null) {
                drainPendingExplicit(server);
            }
        }
    }

    /**
     * Batch completion (success, failure, or early stop): releases the lock, then starts the pending
     * explicit follow-up batch if one was requested mid-flight. Called from {@code runBatch}'s finally.
     */
    public static void finishEnrichment(MinecraftServer server, OperationClaim claim) {
        synchronized (lifecycleLock) {
            if (!activeOperation.compareAndSet(claim, null)) {
                return;
            }
            enriching.set(false);
            refreshCachedStatus();
            drainPendingExplicit(server);
        }
    }

    /**
     * Consumes (and clears) the pending explicit slot and submits the follow-up batch directly -
     * deliberately not via {@link #schedule}: a pending explicit batch must run even if the queue
     * drained meanwhile (it exits normally with calls=0), and {@code runBatch} re-checks
     * disabled/billing itself. No endless chaining: the slot holds at most one request, only explicit
     * commands populate it, and generation-checked compare-and-set consumption clears it.
     */
    private static void drainPendingExplicit(MinecraftServer server) {
        PendingExplicitRequest pending = pendingExplicitRequest.get();
        if (pending == null) {
            return;
        }
        if (!isCurrentGeneration(pending.generation()) || pending.server() != server) {
            pendingExplicitRequest.compareAndSet(pending, null);
            return;
        }
        if (!pendingExplicitRequest.compareAndSet(pending, null)) {
            return;
        }
        Integer limit = pending.limitOverride();
        LOGGER.info("ModIntelligence enrichment: starting pending explicit follow-up batch (limit={})",
                describeLimit(limit));
        var executor = PlayerEngine.getExecutor();
        if (executor == null) {
            if (isCurrentGeneration(pending.generation())) {
                pendingExplicitRequest.compareAndSet(null, pending);
                recordOperationStatus(pending.generation(), OperationStatus.ENRICHMENT_EXECUTOR_UNAVAILABLE);
            }
            LOGGER.warn("ModIntelligence enrichment: pending explicit batch retained; background executor unavailable");
            return;
        }
        try {
            executor.execute(() -> CapabilityEnrichmentService.runBatch(
                    server, limit, true, pending.generation()));
        } catch (RejectedExecutionException e) {
            if (isCurrentGeneration(pending.generation())) {
                pendingExplicitRequest.compareAndSet(null, pending);
                recordOperationStatus(pending.generation(), OperationStatus.ENRICHMENT_EXECUTOR_UNAVAILABLE);
            }
            LOGGER.warn("ModIntelligence enrichment: pending explicit batch retained after executor rejection: {}",
                    e.getMessage());
        }
    }

    private static String describeLimit(Integer limit) {
        if (limit == null) {
            return "config";
        }
        return limit <= 0 ? "unlimited" : String.valueOf(limit);
    }
}
