package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryScope;
import com.player2.playerengine.memory.MemoryStore;
import com.player2.playerengine.memory.MergePlan;
import com.player2.playerengine.memory.budget.Layer3Context;
import com.player2.playerengine.memory.budget.MemoryExtractionBudgetGate;
import com.player2.playerengine.memory.budget.MemoryGate;
import com.player2.playerengine.memory.budget.MemoryGateDecision;
import com.player2.playerengine.memory.budget.MemoryLlmClient;
import com.player2.playerengine.memory.reflection.ReflectionService;
import com.player2.playerengine.memory.reflection.ReflectionTrigger;
import com.player2.playerengine.memory.retrieval.MemoryRetriever;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.mood.CompanionMood;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Phase D memory ingestion entry point (W3): turns curated conversation turns into graph
 * mutations, end to end, behind the W7 patron gate. This is the PRIMARY data-egress boundary owner
 * on the write side — the only ingestion input is curated {@link ConversationHistory}, and every
 * persisted string is hard-capped at write time by {@link MemoryExtractionValidator} /
 * {@link com.player2.playerengine.memory.MemoryCaps}.
 *
 * <h3>Flow</h3>
 * {@link #onCuratedTurnsReady} runs on the SERVER THREAD and returns promptly:
 * <ol>
 *   <li><b>Patron gate FIRST.</b> Resolve the OWNER's billing context and call
 *       {@link MemoryGate#preflight}; not {@code allowed} → complete NO-OP (zero calls, zero buffer
 *       growth, zero writes). This is the fail-closed cost + privacy boundary.</li>
 *   <li>Master flag is already covered by the gate ({@code enableGraphRagMemory}).</li>
 *   <li>{@link MemoryExplicitFactExtractor} captures explicit first-person durable facts/preferences
 *       immediately; these graph writes do not wait for the LLM batch threshold.</li>
 *   <li>{@link MemoryExtractionGate#isEligible} per remaining turn (length / known-entity / proper-noun;
 *       GoG junk dropped).</li>
 *   <li>{@link MemoryTurnBatcher} per {@code (ownerUuid|entityUuid, companionId)} scope; on a
 *       {@code batchMin..batchMax} flush, schedule exactly ONE async extraction.</li>
 *   <li><b>Async</b> on the {@code "memory-ingestion"} daemon executor (never the tick): W7 budget
 *       preflight → build a {@link ConversationHistory} from the BATCHED CURATED TURNS ONLY →
 *       reserve slot → {@link MemoryLlmClient#completeConversationToString} (SUMMARIZATION /
 *       cheapest Default) → validate/cap → emotion+keyword enrich → build a {@link MergePlan}
 *       (with one episodic provenance vertex) → marshal back via {@code server.execute} →
 *       {@link MemoryStore#mergeCandidates} → token bump.</li>
 * </ol>
 *
 * <h3>Store access seam</h3>
 * W3 does not own the per-server store registry (the lifecycle workstream does). The active store
 * for a scope is obtained through the {@link MemoryStoreProvider} hook the integration pass wires
 * via {@link #setStoreProvider}. If the hook is unset (or returns {@code null}), ingestion is a
 * complete no-op — fail-closed, never a crash.
 *
 * <h3>Egress / threading invariants</h3>
 * No {@code *.log}/crash/{@code printStackTrace}-to-string/{@code getStackTrace}/raw {@code Throwable}
 * anywhere; background failures log a bounded, distilled reason (WARN) only, never a stack or the
 * raw reply. The LLM call is NEVER on the tick thread. Store mutations happen only on the server
 * thread: direct for deterministic explicit facts, and via {@code server.execute(...)} for async
 * extraction results. A non-patron / null snapshot makes the whole method a no-op.
 */
public final class MemoryIngestionService {

    /**
     * Seam by which the lifecycle/integration pass supplies the active {@link MemoryStore} for a
     * scope (looked up on the SERVER THREAD inside {@code server.execute}). W3 owns the interface;
     * the integration pass owns the implementation + registration.
     */
    @FunctionalInterface
    public interface MemoryStoreProvider {
        /** The active store for the scope, or {@code null} if none (world unloaded / not loaded). */
        MemoryStore storeFor(MinecraftServer server, MemoryScope scope);
    }

    /** Dedicated daemon executor for async extraction — never the server tick (mirrors INGESTION_EXECUTOR). */
    private static final ExecutorService INGESTION_EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "memory-ingestion");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Per-companion buffer; server-thread only (the entry point is server-thread). */
    private static final MemoryTurnBatcher BATCHER = new MemoryTurnBatcher();

    /**
     * W6 mid-session reflection: one in-flight latch per scope, so a reflection that crosses the
     * cumulative-importance threshold cannot overlap a prior reflection for the same companion.
     * {@link ReflectionService#scheduleReflection} sets it on start and clears it on finish.
     */
    private static final ConcurrentHashMap<MemoryScope, AtomicBoolean> REFLECTION_IN_FLIGHT =
            new ConcurrentHashMap<>();

    /** Store-lookup seam; null until the integration pass wires it (then ingestion is live). */
    private static volatile MemoryStoreProvider storeProvider;

    private MemoryIngestionService() {}

    /** Integration-pass wiring: install the active-store lookup. Idempotent; null disables ingestion. */
    public static void setStoreProvider(MemoryStoreProvider provider) {
        storeProvider = provider;
    }

    /**
     * Drops all per-session static state so the next server session starts clean. MUST be called on
     * {@code SERVER_STOPPING} (after {@code MemoryStoreRegistry.clearAll} and before
     * {@code shutdownBackgroundExecutors}), because both buffers below outlive the per-world store
     * registry on a same-JVM (integrated) restart:
     * <ul>
     *   <li><b>{@link #BATCHER}</b> — stale buffered turns from a prior session would otherwise combine
     *       with fresh turns on the next session and be extracted into the newly-reloaded graph
     *       (cross-session data-integrity + egress bug).</li>
     *   <li><b>{@link #REFLECTION_IN_FLIGHT}</b> — a latch left at {@code true} (e.g. a reflection
     *       scheduled just before stop whose {@code finally} never cleared it) would permanently
     *       suppress reflection for that scope on the next session. By stop time all reflections have
     *       completed or been killed, so a blanket clear is correct.</li>
     * </ul>
     */
    public static void clearSessionState() {
        BATCHER.clear();
        REFLECTION_IN_FLIGHT.clear();
    }

    // -------------------------------------------------------------------------
    // Server-thread entry point
    // -------------------------------------------------------------------------

    /**
     * Notifies the memory pipeline that curated turns were just committed for a companion. SERVER
     * THREAD; returns promptly (any LLM work is scheduled async). Safe to call unconditionally — it
     * self-gates on patron status and the master flag and is a complete no-op when memory is off.
     *
     * @param controller the companion controller (owner, character, API service, server)
     * @param newTurns   the curated turn strings just committed (caller-curated; read-only here)
     */
    public static void onCuratedTurnsReady(PlayerEngineController controller, List<String> newTurns) {
        try {
            if (controller == null || newTurns == null || newTurns.isEmpty()) {
                return;
            }

            // (1) Patron gate FIRST. Resolve the OWNER's billing context (never the prompter's).
            MinecraftServer server = resolveServer(controller);
            if (server == null) {
                return; // no server context (e.g. integrated server not yet started) → no-op
            }
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            String ownerUsername = controller.getOwnerUsername();
            String clientId = cfg.getHeartbeatClientId();
            Player2PayerResolution.ApiBillingContext ownerBilling =
                    Player2PayerResolution.resolve(controller, ownerUsername, clientId);

            MemoryGateDecision decision = MemoryGate.preflight(server, ownerBilling);
            if (!decision.allowed()) {
                return; // not a patron / disabled / over budget → complete no-op
            }

            // (2)/(3) Per-companion scope + eligibility gate per turn.
            MemoryScope scope = resolveScope(controller);
            if (scope == null) {
                return;
            }
            MemoryStore store = lookupStore(server, scope);
            // For the eligibility dictionary we use the current snapshot's graph (may be empty/null).
            com.player2.playerengine.memory.MemoryGraph graph =
                    (store != null && store.snapshot() != null) ? store.snapshot().graph() : null;

            int lengthThreshold = cfg.getMemoryExtractionLengthThreshold();
            int batchMin = cfg.getMemoryExtractionBatchMin();
            int batchMax = cfg.getMemoryExtractionBatchMax();
            long nowTick = currentGameTime(server);
            Character companion = controller.getAIPersistantData() != null
                    ? controller.getAIPersistantData().getCharacter()
                    : null;
            String companionName = companion != null ? companion.name() : null;
            String companionShortName = companion != null ? companion.shortName() : null;

            List<String> batchToExtract = null;
            for (String turn : newTurns) {
                List<MemoryExplicitFactExtractor.ExplicitMemory> explicit =
                        MemoryExplicitFactExtractor.extract(turn, ownerUsername, companionName, companionShortName);
                if (!explicit.isEmpty() && store != null) {
                    MergePlan explicitPlan = MemoryExplicitFactExtractor.buildPlan(explicit, nowTick);
                    if (explicitPlan != null && !explicitPlan.isEmpty()) {
                        applyMerge(controller, server, ownerBilling, scope, store, explicitPlan);
                        continue; // already captured deterministically; do not spend an extraction batch slot
                    }
                }
                if (!MemoryExtractionGate.isEligible(turn, lengthThreshold, graph)) {
                    continue;
                }
                List<String> flushed = BATCHER.offer(scope, turn, batchMin, batchMax);
                if (flushed != null) {
                    batchToExtract = flushed; // last flush of this commit wins (rare to flush twice)
                }
            }

            if (batchToExtract == null) {
                return; // buffered, not yet at batchMin
            }

            // (4) Schedule exactly one async extraction for the flushed batch.
            scheduleExtraction(controller, server, ownerBilling, scope, batchToExtract);
        } catch (RuntimeException e) {
            // Defensive: ingestion must NEVER crash the server thread. Bounded, distilled reason.
            PlayerEngine.LOGGER.warn("Memory ingestion: server-thread entry failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Direct mood-EVENT ingestion (server thread; no LLM — WS4)
    // -------------------------------------------------------------------------

    /**
     * Mints a single {@link MemoryNodeType#EVENT} node for a meaningful mood transition. SERVER
     * THREAD; returns promptly (the graph mutation is dispatched via {@code server.execute}). This is
     * a complete no-op when the owner is not a patron, memory is disabled, or the budget gate
     * refuses.
     *
     * <p><b>Patron gate (release blocker).</b> {@link MemoryGate#preflight} is called before any
     * graph write; non-patron / memory-off → complete no-op (same gate as
     * {@link #onCuratedTurnsReady}).
     *
     * <p><b>Egress (release blocker).</b> The EVENT content is assembled code-side from a short
     * fixed template using only the already-capped {@link CompanionMood#cause()} stored on
     * {@code newMood} (never the raw model reply). {@link MemoryCaps#capContent} is applied to the
     * assembled content as the final backstop before the graph write. No log/stack/unbounded string
     * reaches the graph.
     *
     * <p><b>No LLM.</b> Importance is derived code-side from {@link CompanionMood#intensity()}; no
     * {@code ImportanceScorer} or extraction call is made.
     *
     * @param controller the companion controller (owner, character, server)
     * @param prevMood   the companion's mood before this turn (pre-transition)
     * @param newMood    the newly declared mood (already normalized + capped by {@code CompanionMood})
     */
    public static void ingestMoodEventDirectly(PlayerEngineController controller,
                                               CompanionMood prevMood,
                                               CompanionMood newMood) {
        try {
            if (controller == null || prevMood == null || newMood == null) {
                return;
            }

            // (1) Resolve server; bail if unavailable.
            MinecraftServer server = resolveServer(controller);
            if (server == null) {
                return;
            }

            // (2) Patron gate FIRST — same billing resolution as onCuratedTurnsReady.
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            String ownerUsername = controller.getOwnerUsername();
            String clientId = cfg.getHeartbeatClientId();
            Player2PayerResolution.ApiBillingContext ownerBilling =
                    Player2PayerResolution.resolve(controller, ownerUsername, clientId);

            MemoryGateDecision decision = MemoryGate.preflight(server, ownerBilling);
            if (!decision.allowed()) {
                return; // not a patron / disabled / over budget → complete no-op
            }

            // (3) Resolve per-companion scope and store.
            MemoryScope scope = resolveScope(controller);
            if (scope == null) {
                return;
            }
            MemoryStore store = lookupStore(server, scope);
            if (store == null) {
                return; // world not loaded / provider unset → fail-closed
            }

            // (4) Build a single-node MergePlan. Content is a bounded, templated phrase assembled
            //     code-side; cause comes from the already-capped newMood.cause() (never raw JSON).
            //     MemoryCaps.capContent is applied as the final backstop.
            String prevLabelName = prevMood.label().name().toLowerCase(java.util.Locale.ROOT);
            String newLabelName  = newMood.label().name().toLowerCase(java.util.Locale.ROOT);
            // newMood.cause() was already capped to CAUSE_MAX (120) when CompanionMood was constructed.
            String cause = newMood.cause();
            // Build the EVENT content as a short, bounded templated phrase.
            String rawContent;
            if (cause != null && !cause.isBlank()) {
                rawContent = "Felt " + prevLabelName + " then became " + newLabelName + ": " + cause;
            } else {
                rawContent = "Felt " + prevLabelName + " then became " + newLabelName;
            }
            // Final backstop: capContent so no string exceeds CONTENT_MAX even if future edits drop
            // the upstream cause cap.
            String content = com.player2.playerengine.memory.MemoryCaps.capContent(rawContent);

            // Importance code-side from intensity (no ImportanceScorer): intensity 1-5 → importance 1-5.
            int importance = newMood.intensity();

            long nowMs = System.currentTimeMillis();
            long nowTick = server.getTickCount();

            // Node id keyed on the transition PLUS the timestamp so each distinct transition mints a
            // distinct EVENT node. Without the timestamp segment every same-direction transition (e.g.
            // neutral→happy) would share one id and the graph upsert would overwrite the prior event's
            // content with the newest cause, silently discarding earlier mood history.
            String nodeId = "mood_event_" + prevLabelName + "_to_" + newLabelName + "_" + nowMs;

            MergePlan plan = MergePlan.builder()
                    .upsert(new MergePlan.NodeUpsert(
                            nodeId,
                            content,
                            com.player2.playerengine.memory.MemoryNodeType.EVENT.wire(),
                            com.player2.playerengine.memory.MemoryCaps.capName("mood: " + prevLabelName + " → " + newLabelName),
                            List.of(),   // no aliases
                            List.of(),   // no tags
                            importance,
                            nowMs,
                            nowTick))
                    .build();

            // (5) Apply on the server thread; dispatch reflection if threshold crossed.
            server.execute(() -> {
                try {
                    MemoryStore applyStore = lookupStore(server, scope);
                    if (applyStore == null) {
                        return; // world unloaded between dispatch and apply
                    }
                    applyStore.mergeCandidates(plan);
                    PlayerEngine.LOGGER.info(
                            "Mood memory: EVENT node minted ({} -> {}) for {}.",
                            prevLabelName, newLabelName, scope);
                    // (W8c) On-ingest embed for the new EVENT node (off-tick, console-only on failure).
                    enqueueEmbedBackfill(controller, server, ownerBilling, scope);
                    maybeDispatchReflection(controller, server, ownerBilling, scope, applyStore);
                } catch (RuntimeException applyEx) {
                    PlayerEngine.LOGGER.warn("Mood memory: EVENT apply failed ({})",
                            applyEx.getClass().getSimpleName());
                }
            });

        } catch (RuntimeException e) {
            // Defensive: must never crash the server thread. Bounded, distilled reason only.
            PlayerEngine.LOGGER.warn("Mood memory: ingestMoodEventDirectly failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // Async extraction (daemon executor — never the tick)
    // -------------------------------------------------------------------------

    private static void scheduleExtraction(PlayerEngineController controller,
                                           MinecraftServer server,
                                           Player2PayerResolution.ApiBillingContext ownerBilling,
                                           MemoryScope scope,
                                           List<String> batch) {
        final Player2APIService api = controller.getPlayer2APIService();
        if (api == null) {
            return;
        }
        final List<String> turns = List.copyOf(batch);
        final long nowTick = server.getTickCount();

        INGESTION_EXECUTOR.submit(() -> {
            try {
                // W7 budget preflight + slot reservation at FIRE time (two-phase with the gate peek).
                if (MemoryExtractionBudgetGate.peekCap(ownerBilling)
                        == com.player2.playerengine.memory.budget.MemoryBudgetResult.CAP_REACHED) {
                    return; // window cap reached between schedule and fire
                }
                if (!MemoryExtractionBudgetGate.reserveSlot(ownerBilling)) {
                    return; // lost the reservation race → do not fire
                }

                // Build the prompt history from the BATCHED CURATED TURNS ONLY. addUserMessage(...,api)
                // routes through LogEgressGuard; MemoryLlmClient re-caps every element again at the edge.
                ConversationHistory history = new ConversationHistory(MemoryExtractionPrompt.systemPrompt());
                for (String turn : turns) {
                    history.addUserMessage(turn, api);
                }
                history.addUserMessage(MemoryExtractionPrompt.extractionInstruction(), api);

                String reply = MemoryLlmClient.completeConversationToString(
                        server, ownerBilling, history, AiTaskClass.SUMMARIZATION);

                MemoryExtractionResponse validated = MemoryExtractionValidator.validate(reply);
                if (validated == null) {
                    return; // malformed / nothing durable — keep graph unchanged (no false success)
                }
                MemoryExtractionResponse enriched = MemoryEmotionEnricher.enrich(validated);
                MergePlan plan = MemoryMergePlanBuilder.build(enriched, nowTick);
                if (plan == null || plan.isEmpty()) {
                    return;
                }

                // Marshal the store mutation back onto the server thread (Decision: mutations are
                // server-thread only). The store is looked up again at apply time (world may unload).
                server.execute(() -> {
                    try {
                        MemoryStore store = lookupStore(server, scope);
                        if (store == null) {
                            return; // world unloaded between dispatch and apply
                        }
                        store.mergeCandidates(plan); // applies plan, marks dirty, republishes (token bump)

                        // (W8c) On-ingest embed: enqueue an off-tick embed job for nodes this merge
                        // created/updated (now vectorless or stale-model). Batched, on MEMORY_EXECUTOR.
                        // On-ingest embed failure is CONSOLE-ONLY (no live turn to attach feedback to —
                        // plan A.8 async-no-turn carve-out).
                        enqueueEmbedBackfill(controller, server, ownerBilling, scope);

                        // Bounded INFO (disk/console only — never model-facing): record the merge size +
                        // resulting graph dimensions. Only counts and the scope token are logged; no
                        // entity/relation content or reply text (egress hard rule).
                        com.player2.playerengine.memory.MemoryStore.Snapshot snap = store.snapshot();
                        int nodes = (snap != null && snap.graph() != null) ? snap.graph().nodeCount() : 0;
                        int edges = (snap != null && snap.graph() != null) ? snap.graph().edgeCount() : 0;
                        PlayerEngine.LOGGER.info(
                                "Memory: merged {} entities / {} relations; nodes={} edges={} cumImportance={} for {}.",
                                plan.upserts().size(), plan.edges().size(), nodes, edges,
                                store.cumulativeImportanceSinceLastReflection(), scope);

                        // (W6) Mid-session reflection dispatch. On the SERVER THREAD, the owner was
                        // already patron-confirmed for this batch, and the merge just bumped the
                        // cumulative-importance counter. If it crossed the configured threshold, fire
                        // exactly one off-tick reflection — fully fail-closed behind the owner gate.
                        maybeDispatchReflection(controller, server, ownerBilling, scope, store);
                    } catch (RuntimeException applyEx) {
                        PlayerEngine.LOGGER.warn("Memory ingestion: merge apply failed ({})",
                                applyEx.getClass().getSimpleName());
                    }
                });
            } catch (Exception e) {
                // Budget hard-limit / network / invalid response — bounded, distilled reason only.
                // NEVER the raw reply, message, or stack (DESIGN.md §3 egress hard rule).
                PlayerEngine.LOGGER.warn("Memory ingestion: extraction failed ({})",
                        e.getClass().getSimpleName());
            }
        });
    }

    private static void applyMerge(PlayerEngineController controller,
                                   MinecraftServer server,
                                   Player2PayerResolution.ApiBillingContext ownerBilling,
                                   MemoryScope scope,
                                   MemoryStore store,
                                   MergePlan plan) {
        if (store == null || plan == null || plan.isEmpty()) {
            return;
        }
        store.mergeCandidates(plan);
        enqueueEmbedBackfill(controller, server, ownerBilling, scope);

        com.player2.playerengine.memory.MemoryStore.Snapshot snap = store.snapshot();
        int nodes = (snap != null && snap.graph() != null) ? snap.graph().nodeCount() : 0;
        int edges = (snap != null && snap.graph() != null) ? snap.graph().edgeCount() : 0;
        PlayerEngine.LOGGER.info(
                "Memory: merged {} entities / {} relations; nodes={} edges={} cumImportance={} for {}.",
                plan.upserts().size(), plan.edges().size(), nodes, edges,
                store.cumulativeImportanceSinceLastReflection(), scope);

        maybeDispatchReflection(controller, server, ownerBilling, scope, store);
    }

    // -------------------------------------------------------------------------
    // W6 mid-session reflection dispatch (server thread; fail-closed behind the owner gate)
    // -------------------------------------------------------------------------

    /**
     * Fires at most ONE off-tick reflection when, after a successful merge, the store's cumulative
     * unreflected importance crossed the configured threshold. SERVER THREAD only.
     *
     * <p><b>Patron gate (release blocker).</b> The reflection is UNREACHABLE for non-patrons: we
     * re-run {@link MemoryGate#preflight} keyed on the OWNER's billing context (never the prompter's)
     * and build a fail-closed {@link Layer3Context#fromGate}; if the gate is not {@code allowed}
     * ({@code layer3Enabled()==false}) we do not dispatch. {@link ReflectionService#scheduleReflection}
     * is itself fail-closed on the same context, so this is a defense-in-depth double gate.
     *
     * <p><b>Egress (release blocker).</b> The retriever adapter is zero-LLM (immutable snapshot); the
     * reflection's LLM steps all route through {@link MemoryLlmClient} (SUMMARIZATION / cheapest),
     * which re-caps every history element. No log/stack/{@code Throwable}/unbounded string reaches a
     * model-facing surface here.
     *
     * <p><b>Degradation.</b> An autonomous (non-user-triggered) reflection has no turn to attach
     * model feedback to, so the {@link ReflectionService.DegradationSink} is log-only/no-op
     * (autonomous skips are silent — no chat spam, no fabricated model feedback).
     */
    private static void maybeDispatchReflection(PlayerEngineController controller,
                                                MinecraftServer server,
                                                Player2PayerResolution.ApiBillingContext ownerBilling,
                                                MemoryScope scope,
                                                MemoryStore store) {
        try {
            if (controller == null || server == null || scope == null || store == null
                    || ownerBilling == null || ownerBilling.billingKey() == null) {
                return; // anything null/uncertain → no dispatch (fail-closed)
            }

            // (1) Threshold check (deterministic, no LLM).
            Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
            // Use the CLAMPED accessor (floor 1) so zeroing/negating the config does not silently fall
            // back to ReflectionTrigger's DEFAULT_IMPORTANCE_THRESHOLD — matches the rest of the config
            // surface and honors the operator's intent (a value of 0 means "every batch", not default).
            long threshold = (cfg != null) ? cfg.getReflectionImportanceThresholdClamped() : 0L;
            if (!ReflectionTrigger.shouldReflect(store, threshold)) {
                return; // not enough unreflected importance yet → no-op
            }

            // (2) Fail-closed OWNER gate. Fresh preflight keyed on the OWNER billing context (never the
            //     prompter's), built the same way AgentConversationData does (Layer3Context.fromGate).
            MemoryGateDecision decision = MemoryGate.preflight(server, ownerBilling);
            Layer3Context layer3Context = Layer3Context.fromGate(ownerBilling, decision);
            if (!layer3Context.layer3Enabled()) {
                return; // not a patron / disabled / over budget → UNREACHABLE for non-patrons
            }

            // (4) One reflection at a time per scope.
            AtomicBoolean inFlight =
                    REFLECTION_IN_FLIGHT.computeIfAbsent(scope, s -> new AtomicBoolean(false));

            // (3) Zero-LLM retriever adapter over the immutable snapshot: graph hits → MemoryNode.
            ReflectionService.Retriever retriever = (q, k) -> retrieveNodes(store, q, k);

            // (5) Server-thread summary push: rebuild the companion's system block through the same
            //     central combine helper the integration installed (AIPersistantData#updateSystemPrompt
            //     re-routes the freshly-built base prompt through withRelationshipSuffix, which reads the
            //     just-persisted summary from the store). We do NOT thread a new param through Prompts.*.
            java.util.function.Consumer<String> onSummaryUpdated =
                    newSummary -> refreshSystemBlock(controller);

            // (6) Autonomous skips are silent: log-only/no-op degradation sink (no chat spam, no fake
            //     model feedback — there is no user turn to attach it to).
            ReflectionService.DegradationSink degradation = (playerMsg, modelToken) -> { /* silent */ };

            // (7) nowTick from the server thread.
            long nowTick = server.getTickCount();

            ReflectionService.scheduleReflection(
                    server, store, layer3Context, inFlight, retriever, nowTick,
                    onSummaryUpdated, degradation);
        } catch (RuntimeException e) {
            // Reflection dispatch must never crash the server thread. Bounded, distilled reason only.
            PlayerEngine.LOGGER.warn("Memory reflection: dispatch failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Zero-LLM retriever adapter: runs the W5 {@link MemoryRetriever#query} (immutable snapshot, off-tick
     * safe) and maps each {@link RetrievalHit} back to its {@link MemoryNode} via the snapshot graph,
     * dropping nulls and capping at {@code k}.
     */
    private static List<MemoryNode> retrieveNodes(MemoryStore store, String question, int k) {
        List<MemoryNode> out = new ArrayList<>();
        if (store == null || question == null || question.isBlank()) {
            return out;
        }
        MemoryStore.Snapshot snap = store.snapshot();
        if (snap == null || snap.graph() == null) {
            return out;
        }
        List<RetrievalHit> hits = new MemoryRetriever(store).query(question, k);
        if (hits == null) {
            return out;
        }
        for (RetrievalHit hit : hits) {
            if (k > 0 && out.size() >= k) break;
            if (hit == null || hit.id() == null) continue;
            MemoryNode node = snap.graph().node(hit.id());
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * Server-thread system-block refresh after a reflection persisted a new summary. Routes through the
     * controller's existing {@link com.player2.playerengine.player2api.AIPersistantData#updateSystemPrompt()}
     * rebuild path, whose central combine helper re-appends the now-updated {@code [Relationship]} suffix
     * — keeping the suffix byte-stable per {@code summaryVersion}. Best-effort and never throws.
     */
    private static void refreshSystemBlock(PlayerEngineController controller) {
        try {
            if (controller != null && controller.getAIPersistantData() != null) {
                controller.getAIPersistantData().updateSystemPrompt();
            }
        } catch (RuntimeException e) {
            PlayerEngine.LOGGER.warn("Memory reflection: system-block refresh failed ({})",
                    e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // W8c dense embed lifecycle (on-ingest + backfill + re-embed) — off-tick only
    // -------------------------------------------------------------------------

    /** Max node texts embedded per {@code /v1/embeddings} pass (endpoint hard cap). */
    private static final int EMBED_BATCH_MAX =
            com.player2.playerengine.memory.dense.EmbeddingBatcher.MAX_BATCH;

    /** Scopes with a backfill pass currently in flight (avoid overlapping backfills for one companion). */
    private static final java.util.Set<MemoryScope> EMBED_IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Enqueues an off-tick embed pass (W8c) for one companion: embeds up to {@link #EMBED_BATCH_MAX}
     * vectorless / stale-model nodes per pass, applies the vectors back on the server thread, and
     * re-schedules itself until the graph is fully embedded (backfill complete). Also serves the one-time
     * world-load backfill and the model/dim re-embed (both flow through
     * {@link MemoryStore#denseBackfillCandidates}). Runs on {@link MemoryLlmClient#MEMORY_EXECUTOR}; NEVER
     * the tick. On-ingest / backfill embed failures are CONSOLE-ONLY (no live turn — plan A.8 carve-out).
     *
     * <p>Fail-safe in {@code dedicatedClientProxy} mode: a background job has no outbound path, so embed
     * degrades to a bounded token and this simply leaves nodes vectorless (retrieval falls back to
     * lexical/MinHash/graph). No player/model surface.
     */
    static void enqueueEmbedBackfill(PlayerEngineController controller,
                                     MinecraftServer server,
                                     Player2PayerResolution.ApiBillingContext ownerBilling,
                                     MemoryScope scope) {
        if (controller == null || server == null || scope == null
                || ownerBilling == null || ownerBilling.billingKey() == null) {
            return;
        }
        if (!com.player2.playerengine.memory.dense.EmbeddingProvider.isAvailable()) {
            return;
        }
        if (!EMBED_IN_FLIGHT.add(scope)) {
            return; // a pass is already running for this scope
        }
        MemoryLlmClient.MEMORY_EXECUTOR.submit(() -> {
            try {
                runEmbedPass(controller, server, ownerBilling, scope);
            } catch (RuntimeException e) {
                PlayerEngine.LOGGER.warn("Memory embed: pass failed ({})", e.getClass().getSimpleName());
            } finally {
                EMBED_IN_FLIGHT.remove(scope);
            }
        });
    }

    /**
     * One off-tick embed pass: collect a bounded batch of node texts on the server thread, embed them,
     * apply the vectors back on the server thread, and re-enqueue if more remain. Console-only on failure.
     */
    private static void runEmbedPass(PlayerEngineController controller,
                                     MinecraftServer server,
                                     Player2PayerResolution.ApiBillingContext ownerBilling,
                                     MemoryScope scope) {
        // (1) Collect candidate ids + their embeddable text on the SERVER THREAD (graph reads are
        //     server-thread; run() blocks this executor thread until the collection completes).
        java.util.concurrent.CompletableFuture<List<String[]>> collected =
                new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                MemoryStore store = lookupStore(server, scope);
                if (store == null) {
                    collected.complete(List.of());
                    return;
                }
                List<String> ids = store.denseBackfillCandidates(EMBED_BATCH_MAX);
                if (ids.isEmpty()) {
                    store.setDenseBackfillComplete(true); // nothing left → mark complete (avoids re-scan)
                    collected.complete(List.of());
                    return;
                }
                List<String[]> batch = new ArrayList<>(ids.size());
                for (String id : ids) {
                    String text = store.denseEmbeddableText(id);
                    if (text != null && !text.isBlank()) {
                        batch.add(new String[]{id, text});
                    }
                }
                collected.complete(batch);
            } catch (RuntimeException e) {
                collected.complete(List.of());
            }
        });

        List<String[]> batch;
        try {
            // Bounded wait: never block the shared single-thread MEMORY_EXECUTOR indefinitely. On a
            // draining/stopping server, server.execute(...) may never run the collection task, leaving
            // this future uncompleted — a timeout frees the executor for other memory tasks instead of
            // starving it for the session.
            batch = collected.get(5, TimeUnit.SECONDS); // wait for the server-thread collection
        } catch (Exception e) {
            // interrupted / timeout / server-thread failure → drop this pass silently (console-only path)
            return;
        }
        if (batch.isEmpty()) {
            return; // nothing to embed (or backfill complete)
        }

        // (2) Embed off-tick (this executor thread). Console-only on failure (no live turn).
        List<String> texts = new ArrayList<>(batch.size());
        for (String[] pair : batch) texts.add(pair[1]);
        com.player2.playerengine.memory.dense.EmbeddingResult result =
                com.player2.playerengine.memory.dense.EmbeddingBatcher.embedAll(texts, controller, ownerBilling);
        if (!result.ok()) {
            // Bounded token already logged to console by the provider; leave nodes vectorless this pass.
            PlayerEngine.LOGGER.info("Memory embed: batch degraded ({}) for {}.",
                    result.degradedToken(), scope);
            return;
        }
        float[][] vecs = result.vectors();

        // (3) Apply vectors back on the SERVER THREAD, then re-enqueue if a full batch was consumed
        //     (there may be more vectorless nodes).
        final boolean maybeMore = batch.size() >= EMBED_BATCH_MAX;
        server.execute(() -> {
            try {
                MemoryStore store = lookupStore(server, scope);
                if (store == null) return;
                int applied = 0;
                for (int i = 0; i < batch.size() && i < vecs.length; i++) {
                    if (vecs[i] != null) {
                        store.applyNodeVector(batch.get(i)[0], vecs[i]);
                        applied++;
                    }
                }
                PlayerEngine.LOGGER.info("Memory embed: applied {} vector(s) for {}.", applied, scope);
                if (!maybeMore) {
                    store.setDenseBackfillComplete(true);
                }
            } catch (RuntimeException e) {
                PlayerEngine.LOGGER.warn("Memory embed: apply failed ({})", e.getClass().getSimpleName());
            }
        });

        // Re-enqueue another pass if this one filled a whole batch (more may remain). The in-flight latch
        // for this scope is released by the caller's finally, so re-adding is safe.
        if (maybeMore) {
            enqueueEmbedBackfill(controller, server, ownerBilling, scope);
        }
    }

    /**
     * One-time backfill / re-embed entry point (W8c) for the world-load path: kicks an embed pass that
     * fills vectorless nodes and re-embeds nodes whose {@code vectorModel} no longer matches the frozen
     * model. Idempotent + resumable (a node already carrying the frozen-token vector is skipped). Safe to
     * call unconditionally — self-gates on patron/master-flag via the same gate as ingestion is not
     * re-run here, so callers should only invoke it for scopes already known live (the integration pass
     * wires this to world/companion load). No-op when the store is fully embedded.
     */
    public static void backfillDenseVectors(PlayerEngineController controller,
                                            MinecraftServer server,
                                            Player2PayerResolution.ApiBillingContext ownerBilling,
                                            MemoryScope scope) {
        enqueueEmbedBackfill(controller, server, ownerBilling, scope);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long currentGameTime(MinecraftServer server) {
        return server != null && server.overworld() != null ? server.overworld().getGameTime() : 0L;
    }

    private static MemoryStore lookupStore(MinecraftServer server, MemoryScope scope) {
        MemoryStoreProvider provider = storeProvider;
        if (provider == null) {
            return null;
        }
        try {
            return provider.storeFor(server, scope);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Resolves the per-companion scope (owner-keyed, with entity fallback like conversation history). */
    private static MemoryScope resolveScope(PlayerEngineController controller) {
        Character character = controller.getAIPersistantData() != null
                ? controller.getAIPersistantData().getCharacter() : null;
        String companionId = character != null ? character.id() : null;
        if (companionId == null || companionId.isBlank()) {
            return null;
        }
        UUID ownerUuid = (controller.getOwner() != null) ? controller.getOwner().getUUID() : null;
        if (ownerUuid != null) {
            return MemoryScope.of(ownerUuid, companionId);
        }
        // Owner unresolvable → entity-fallback layout (mirrors conversation-history fallback).
        LivingEntity entity = controller.getPlayer();
        if (entity == null) {
            return null;
        }
        return MemoryScope.ofEntityFallback(entity.getUUID(), companionId);
    }

    /** Resolves the {@link MinecraftServer} from the controller; {@code null} when unavailable. */
    private static MinecraftServer resolveServer(PlayerEngineController controller) {
        try {
            LivingEntity entity = controller.getPlayer();
            if (entity != null && entity.getServer() != null) {
                return entity.getServer();
            }
        } catch (RuntimeException ignored) {
            // fall through to null
        }
        return null;
    }
}
