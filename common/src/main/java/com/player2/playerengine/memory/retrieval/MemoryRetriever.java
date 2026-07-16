package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryScope;
import com.player2.playerengine.memory.MemoryStore;
import com.player2.playerengine.memory.dense.EmbeddingProvider;
import com.player2.playerengine.memory.dense.TurnEmbeddingCache;
import com.player2.playerengine.memory.retrieval.EgoGraphTraversal.EgoNode;
import com.player2.playerengine.memory.retrieval.MemoryRetrievalConfidence.BoundaryVerdict;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.retrieval.LexicalIndex;
import com.player2.playerengine.retrieval.MinHashIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.Retriever;
import com.player2.playerengine.retrieval.RetrieverRegistry;
import com.player2.playerengine.retrieval.RrfFusion;
import com.player2.playerengine.retrieval.ToolDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The zero-LLM memory-retrieval hot path (Phase D, W5). Occupies retriever slot
 * {@link RetrieverRegistry#SLOT_GRAPH} (= 2).
 *
 * <p>Per turn, deterministically. Retrieval is on-device; the ONLY Player2 call is the optional W8d
 * single turn-embedding (issued once, off-tick, when a {@link DenseContext} is wired — it degrades to
 * null/no-dense on any failure, leaving the read path byte-identical to the pre-W8 build):
 * <ol>
 *   <li><b>Fast-exit</b> {@link BoundaryVerdict#STORE_ABSENT} if memory is disabled (the caller's gate
 *       flag {@code memoryEnabled} is false) or the
 *       published snapshot is empty — the caller then injects nothing (request byte-identical to a
 *       pre-W5 build).</li>
 *   <li><b>Deadline guard</b> ({@link #BUDGET_NANOS}, ~2 ms) — every stage degrades to a partial
 *       result rather than overrunning the per-tick budget.</li>
 *   <li><b>Entity-link:</b> rebuild a lexical ({@link LexicalIndex}) + MinHash ({@link MinHashIndex})
 *       index <b>in-memory from the snapshot</b> (no persisted {@code .bin} — open item 8 resolved)
 *       and fuse the two ranked lists with W1's RRF → seed node ids.</li>
 *   <li><b>Ego-graph BFS</b> ({@link EgoGraphTraversal}, 1–2 hops, capped nodes, recency-decayed
 *       edge weights).</li>
 *   <li><b>Episodic re-rank</b> ({@link EpisodicReranker}, three-list N-way RRF over recency /
 *       importance / relevance — the embeddings-additive seam).</li>
 *   <li><b>Confidence + boundary verdict</b> ({@link MemoryRetrievalConfidence}).</li>
 *   <li><b>Serialize</b> the bounded tail block ({@link MemoryBlockSerializer}).</li>
 * </ol>
 *
 * <p>Reads only a published, immutable {@link MemoryStore.Snapshot}; safe to run off the snapshot.
 * No Minecraft / loader / I/O dependency: the {@code MinecraftServer} is never touched here — the
 * caller supplies the resolved {@link MemoryStore} (or its snapshot) for the scope.
 */
public final class MemoryRetriever implements Retriever {

    /** Per-turn BFS/retrieval budget in nanoseconds (~2 ms). Degrade past it, never overrun. */
    public static final long BUDGET_NANOS = 2_000_000L;

    /** Seed candidates pulled from each lexical index before fusion. */
    static final int SEED_CANDIDATES = 16;

    /** Default scored hits injected (config {@code memoryRetrievalTopK}). */
    public static final int DEFAULT_TOP_K = 5;

    /** Tunable retrieval bounds resolved once per call from config (see {@code Player2ServerRuntimeConfig}). */
    public static final class Thresholds {
        public final int maxHops;
        public final int maxEgoNodes;
        public final int blockCharCap;
        public final double minConfidence;
        public final double decayBase;
        public final long gameTimeUnit;
        public final int topK;

        public Thresholds(int maxHops, int maxEgoNodes, int blockCharCap, double minConfidence,
                          double decayBase, long gameTimeUnit, int topK) {
            this.maxHops = maxHops;
            this.maxEgoNodes = maxEgoNodes;
            this.blockCharCap = blockCharCap;
            this.minConfidence = minConfidence;
            this.decayBase = decayBase;
            this.gameTimeUnit = gameTimeUnit;
            this.topK = topK;
        }

        /** Conservative defaults matching the plan's config defaults (used when config is unavailable). */
        public static Thresholds defaults() {
            return new Thresholds(
                    EgoGraphTraversal.DEFAULT_MAX_HOPS,
                    EgoGraphTraversal.DEFAULT_MAX_EGO_NODES,
                    MemoryBlockSerializer.DEFAULT_BLOCK_CHAR_CAP,
                    MemoryRetrievalConfidence.MIN_CONFIDENCE,
                    com.player2.playerengine.memory.MemoryCaps.DECAY_BASE,
                    com.player2.playerengine.memory.MemoryCaps.TICKS_PER_GAME_HOUR,
                    DEFAULT_TOP_K);
        }
    }

    /** The store this retriever reads (its published snapshot is used per call). */
    private final MemoryStore store;

    /**
     * Optional dense-retrieval context (W8d). When present, the full {@link #retrieve} path embeds the
     * turn ONCE (off-tick — {@link EmbeddingProvider#embed} hard-guards the server thread) and threads
     * the shared vector into both fusion sites; when null (the default, and every {@link Retriever}
     * adapter path), dense is OFF and both sites are byte-identical to the pre-W8 path.
     */
    private final DenseContext denseContext;

    public MemoryRetriever(MemoryStore store) {
        this(store, null);
    }

    /** Constructs a retriever with an optional dense-retrieval context (W8d); null = dense off. */
    public MemoryRetriever(MemoryStore store, DenseContext denseContext) {
        this.store = store;
        this.denseContext = denseContext;
    }

    /**
     * The route/billing context needed to embed the turn on the read path (W8d, site 1). Supplied by the
     * conversation-assembly caller (which already resolves the OWNER billing context for the patron gate)
     * so the retriever can embed without re-resolving. Null anywhere → dense off (byte-identical read path).
     *
     * @param controller the companion controller (route context)
     * @param billing    the OWNER's resolved billing context (never the prompter's)
     */
    public record DenseContext(PlayerEngineController controller,
                               Player2PayerResolution.ApiBillingContext billing) {
        /** True iff both the controller and a resolvable billing context are present. */
        public boolean usable() {
            return controller != null && billing != null && billing.billingKey() != null;
        }
    }

    /**
     * Full retrieval entry point.
     *
     * @param currentTurnText  the latest user turn text (the entity-link query)
     * @param ownerUuid        the companion owner UUID (scope; informational — store is already scoped)
     * @param companionId      the companion id (scope; informational — store is already scoped)
     * @param currentGameTime  the server game time (recency basis)
     * @param thresholds       resolved config bounds (null → {@link Thresholds#defaults()})
     * @param memoryEnabled    the caller's resolved memory-gate boolean (W9: patrons AND non-patrons,
     *                         budget-governed) — false short-circuits to STORE_ABSENT (zero cost)
     */
    public MemoryRetrievalResult retrieve(String currentTurnText,
                                          java.util.UUID ownerUuid,
                                          String companionId,
                                          long currentGameTime,
                                          Thresholds thresholds,
                                          boolean memoryEnabled) {
        return retrieve(currentTurnText, ownerUuid, companionId, null, null,
                currentGameTime, thresholds, memoryEnabled);
    }

    /**
     * Full retrieval entry point, knowledge-boundary aware. The {@code companionName} / {@code ownerName}
     * are the graph canonical names of the companion-self node and owner node — the two anchors that are
     * ALWAYS present in the graph. They are used ONLY to compute the "specific seed match" signal that
     * stops a turn which merely names the companion/owner (and links to nothing episodic) from confirming
     * a fabricated memory (knowledge-boundary hallucination fix). Either may be null/blank when unknown,
     * in which case no anchor is excluded (every seed counts as specific — pre-fix behavior).
     *
     * @param companionName the companion's display name (self-anchor canonical name); nullable
     * @param ownerName     the owner's display name (owner-anchor canonical name); nullable
     */
    public MemoryRetrievalResult retrieve(String currentTurnText,
                                          java.util.UUID ownerUuid,
                                          String companionId,
                                          String companionName,
                                          String ownerName,
                                          long currentGameTime,
                                          Thresholds thresholds,
                                          boolean memoryEnabled) {
        final long deadline = System.nanoTime() + BUDGET_NANOS;
        Thresholds t = thresholds != null ? thresholds : Thresholds.defaults();

        // (1) Fast-exit: memory disabled (gate) / no store / empty snapshot → emit nothing.
        if (!memoryEnabled || store == null) {
            return MemoryRetrievalResult.storeAbsent();
        }
        MemoryStore.Snapshot snap = store.snapshot();
        if (snap == null) {
            return MemoryRetrievalResult.storeAbsent();
        }
        MemoryGraph graph = snap.graph();
        if (graph == null || graph.nodeCount() == 0 || currentTurnText == null || currentTurnText.isBlank()) {
            return MemoryRetrievalResult.storeAbsent();
        }

        // (2b) Embed the turn ONCE (W8d) — shared by BOTH fusion sites. Off-tick: EmbeddingProvider.embed
        // hard-guards the server thread. Null when dense is off / unavailable / the embed degraded, in
        // which case both fusion sites fall back to the pre-W8 behavior (byte-identical read path).
        float[] turnVector = embedTurnOnce(currentTurnText);

        // (3) Entity-link: rebuild the lexical+minhash index in-memory from the snapshot, then fuse
        //     (with the dense seed list when the turn vector is present).
        List<String> seedIds = entityLinkSeeds(graph, currentTurnText, deadline, turnVector);
        int seedMatches = seedIds.size();
        // Specific-seed signal: seeds BEYOND the always-present self/owner anchors, plus any matched
        // EVENT/episodic node. If this is 0, the turn only named the companion/owner and referenced
        // nothing the graph actually holds — the verdict must be NO_MEMORY (knowledge-boundary fix).
        int specificSeedMatches = countSpecificSeedMatches(graph, seedIds, companionName, ownerName);

        // (4) Bounded ego-graph BFS (deadline-aware).
        List<EgoNode> ego = EgoGraphTraversal.traverse(
                graph, seedIds, currentGameTime, t.maxHops, t.maxEgoNodes,
                t.decayBase, t.gameTimeUnit, deadline);

        // (5) Episodic re-rank (three-list N-way RRF). The SAME shared turn vector drives the relevance
        //     list's cosine swap (W8d, site 2); null → graph-walk relevance (byte-identical).
        List<RetrievalHit> ranked = EpisodicReranker.rerank(ego, currentGameTime, t.topK, turnVector);

        // (6) Confidence + (7) boundary verdict.
        double normTop = normalizedTopScore(ranked);
        double confidence = MemoryRetrievalConfidence.confidence(normTop, ranked.size(), seedMatches);
        BoundaryVerdict verdict = MemoryRetrievalConfidence.verdict(
                /*storeAbsent*/ false, confidence, seedMatches, specificSeedMatches, t.minConfidence);

        // (8) Serialize the bounded tail block.
        Optional<String> block = MemoryBlockSerializer.serialize(verdict, ranked, graph, t.blockCharCap);

        // A HAS_MEMORY verdict whose serialized block is empty means every confident hit was a stable
        // profile/entity node with no episodic EVENT recall (the serializer filters profile nodes out of
        // the recall body — Phase D fixation fix). The profile fact still surfaces via the relationship
        // summary in the SYSTEM block, so nothing is injected this turn. Reclassify the RESULT to
        // STORE_ABSENT in that case so the public contract holds (verdict == HAS_MEMORY -> block present;
        // see MemoryRetrievalResult javadoc), debug logging reports a consistent verdict/block state, and
        // result.hits() does not advertise profile node ids that were never rendered. STORE_ABSENT (not
        // NO_MEMORY) is the right re-class: it is the byte-identical no-op path and emits NO decline note,
        // so it cannot contradict the relationship summary the model can already see. The anti-fabrication
        // gate is unaffected — an EVENT seed still drives a real HAS_MEMORY block with rendered hits, and
        // the genuine "linked to nothing specific" decline remains the separate NO_MEMORY verdict above.
        if (verdict == BoundaryVerdict.HAS_MEMORY && block.isEmpty()) {
            return new MemoryRetrievalResult(List.of(), confidence, BoundaryVerdict.STORE_ABSENT, block);
        }

        // HAS_MEMORY hits carry the actual recall; NO_MEMORY surfaces the decline note with no hits.
        List<RetrievalHit> resultHits = verdict == BoundaryVerdict.HAS_MEMORY ? ranked : List.of();
        return new MemoryRetrievalResult(resultHits, confidence, verdict, block);
    }

    /**
     * {@link Retriever} adapter: returns the graph-retrieval hits for {@code goal} in slot
     * {@link RetrieverRegistry#SLOT_GRAPH}, so the memory retriever can drop into the hybrid stack.
     * Non-patron / empty store yields no hits. The boundary gate is not applied here (this is the
     * raw-rank surface for fusion); the patron gate is applied by the full {@link #retrieve} path.
     */
    @Override
    public List<RetrievalHit> query(String goal, int k) {
        if (store == null) return List.of();
        MemoryStore.Snapshot snap = store.snapshot();
        if (snap == null || snap.graph() == null || snap.graph().nodeCount() == 0) return List.of();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        Thresholds t = Thresholds.defaults();
        // Adapter path: no dense (no billing context wired here). turnVector null → both fusion sites
        // fall back to graph-walk / 2-way behavior, byte-identical to pre-W8.
        List<String> seeds = entityLinkSeeds(snap.graph(), goal, deadline, null);
        List<EgoNode> ego = EgoGraphTraversal.traverse(
                snap.graph(), seeds, latestTick(), t.maxHops, t.maxEgoNodes,
                t.decayBase, t.gameTimeUnit, deadline);
        return EpisodicReranker.rerank(ego, latestTick(), k > 0 ? k : t.topK, null);
    }

    @Override
    public int slotIndex() {
        return RetrieverRegistry.SLOT_GRAPH;
    }

    // -------------------------------------------------------------------------
    // Entity-linking: in-memory lexical index rebuilt from the snapshot each call
    // -------------------------------------------------------------------------

    /**
     * Builds the lexical + MinHash indexes over the snapshot's nodes, queries both for the turn text,
     * fuses the two ranked lists with W1's RRF, and returns the fused seed node ids (deduped, order
     * preserved). Open item 8 resolution: the index is rebuilt in-memory per call from the published
     * snapshot — there is no persisted {@code .bin}. Each node becomes one {@link ToolDocument} whose
     * id is the node id and whose indexed text is {name + aliases + tags + content}.
     *
     * <p>The {@code deadline} guards only the ENTRY: if the per-call tick budget is already overrun we
     * skip seeding entirely (the downstream ego BFS still no-ops cleanly on empty seeds). The index
     * build itself ({@code LexicalIndex.build} + {@code MinHashIndex.build}) is monolithic and runs
     * uninterrupted, but it is bounded by {@link com.player2.playerengine.memory.MemoryCaps#MAX_NODES}
     * (≤500 docs) and is sub-ms in
     * practice — the deadline check before it is the cheap correctness floor.
     */
    List<String> entityLinkSeeds(MemoryGraph graph, String turnText, long deadline, float[] turnVector) {
        if (graph == null || turnText == null || turnText.isBlank() || graph.nodeCount() == 0) {
            return List.of();
        }
        if (System.nanoTime() >= deadline) {
            return List.of(); // tick budget already spent before entity-link → seed nothing
        }
        String queryText = normalizeQueryText(turnText);
        List<ToolDocument> docs = new ArrayList<>(graph.nodeCount());
        for (MemoryNode n : graph.nodes()) {
            docs.add(toDocument(n));
        }

        LexicalIndex lexical = LexicalIndex.build(docs);
        MinHashIndex minHash = MinHashIndex.build(docs);

        List<RetrievalHit> bm25 = lexical.query(queryText, SEED_CANDIDATES);
        List<RetrievalHit> fuzzy = minHash.query(queryText, SEED_CANDIDATES);

        // W8d, site 1: dense seed list (empty when turnVector is null → fusion is byte-identical to the
        // pre-W8 2-way fuse). Brute-force cosine over hasVector() nodes only (≤ MAX_NODES).
        List<RetrievalHit> dense = denseCosineHits(graph, turnVector, SEED_CANDIDATES);

        // N-way RRF: list 0 → lexical (slot 0), list 1 → minHash (slot 1), list 2 → dense. When `dense`
        // is empty the fused seed set is IDENTICAL to today's 2-way fuse (true no-op when dense is off).
        List<RetrievalHit> fused = RrfFusion.fuse(
                List.of(bm25, fuzzy, dense), SEED_CANDIDATES, EpisodicReranker.RRF_K);

        Set<String> seeds = new LinkedHashSet<>();
        for (RetrievalHit hit : fused) {
            if (hit.id() != null) seeds.add(hit.id());
        }
        return new ArrayList<>(seeds);
    }

    /**
     * Resolves the shared turn-query vector for the full retrieve path (W8d), <b>off-tick-safe</b>.
     *
     * <p>Bug-1 fix (2026-07-01): the {@code retrieve} path runs <b>on the MC server tick thread</b> (it is
     * called synchronously from {@code AgentConversationData.resolveMemoryBlock} during prompt assembly,
     * before the async LLM dispatch). {@link EmbeddingProvider#embed} hard-guards the tick
     * ({@code assertOffTick} throws on {@code "Server thread"}) — correctly, since a synchronous embed on
     * the tick would stall the server on the network. Calling {@code embed} directly here therefore threw
     * {@link IllegalStateException} <b>every turn</b>, was swallowed below, and dense never fired.
     *
     * <p>Instead this now consults the off-tick {@link TurnEmbeddingCache}: a non-blocking cache read that
     * returns the vector on a warm hit (dense contributes this turn) and, on a miss, schedules the embed
     * OFF-TICK on {@link com.player2.playerengine.memory.budget.MemoryLlmClient#MEMORY_EXECUTOR} and
     * returns null (dense OFF this turn → both fusion sites byte-identical to the pre-W8 read path). A
     * re-asked / rephrased query then hits warm on a later turn. The tick is <b>never</b> blocked and no
     * data race is introduced (the cache is concurrent). Never throws — any failure degrades to null.
     */
    private float[] embedTurnOnce(String turnText) {
        try {
            if (denseContext == null || !denseContext.usable()
                    || turnText == null || turnText.isBlank()
                    || !EmbeddingProvider.isAvailable()) {
                return null;
            }
            // Non-blocking read (tick-safe). Warm hit → dense fires this turn.
            float[] cached = TurnEmbeddingCache.lookup(turnText);
            if (cached != null) {
                return cached;
            }
            // Miss → compute off-tick for a future turn; degrade to lexical/MinHash/graph THIS turn.
            TurnEmbeddingCache.scheduleIfAbsent(
                    turnText, denseContext.controller(), denseContext.billing());
            return null;
        } catch (RuntimeException e) {
            // Read-path embed must never break retrieval — degrade to lexical/MinHash/graph.
            return null;
        }
    }

    /**
     * Brute-force cosine of {@code turnVector} against every {@code hasVector()} node in the snapshot
     * (no ANN — ≤ {@link com.player2.playerengine.memory.MemoryCaps#MAX_NODES} nodes), returning up to
     * {@code topK} node ids ranked by descending cosine as a dense seed list. Empty when
     * {@code turnVector} is null (dense off) → the read path is byte-identical.
     */
    private List<RetrievalHit> denseCosineHits(MemoryGraph graph, float[] turnVector, int topK) {
        if (graph == null || turnVector == null) {
            return List.of();
        }
        // Use the store's loaded (int8-quantized) .bin mirror so retrieval consumes the persisted index
        // rather than re-reading node.vector() from scratch. The cosineRankedIds helper already handles
        // the frozen-model gating + fallback to node.vector() for nodes not yet mirrored in the .bin, so
        // an empty/absent mirror still scans the snapshot's node vectors — a persisted mirror is an
        // optimization, not a correctness dependency.
        com.player2.playerengine.memory.dense.DenseIndexState index =
                (store != null) ? store.denseIndex()
                        : com.player2.playerengine.memory.dense.DenseIndexState.empty();
        List<String> rankedIds = index.cosineRankedIds(graph, turnVector, topK);
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        List<RetrievalHit> hits = new ArrayList<>(rankedIds.size());
        int rank = 1;
        for (String id : rankedIds) {
            // Carried score is informational; RrfFusion attributes by list position (rank).
            hits.add(new RetrievalHit(id, 1.0 / (EpisodicReranker.RRF_K + rank), new int[0]));
            rank++;
        }
        return hits;
    }

    static String normalizeQueryText(String turnText) {
        if (turnText == null || turnText.isBlank()) {
            return "";
        }
        String normalized = turnText;
        normalized = normalized.replaceAll("(?i)\\bfavourite\\b", "favorite");
        normalized = normalized.replaceAll("(?i)\\bfavourites\\b", "favorites");
        normalized = normalized.replaceAll("(?i)\\bcolour\\b", "color");
        normalized = normalized.replaceAll("(?i)\\bcolours\\b", "colors");
        normalized = normalized.replaceAll("(?i)\\bvideogames\\b", "video games");
        normalized = normalized.replaceAll("(?i)\\bvideogame\\b", "video game");
        return normalized;
    }

    /**
     * Counts the "specific" seed matches: seed nodes that are NOT one of the always-present self/owner
     * anchor nodes (matched by canonical name == companionName / ownerName, case-insensitive), PLUS any
     * matched EVENT/episodic node (which is always specific even if it shares a name). This is the
     * knowledge-boundary discriminator: a turn whose only seed matches are the self/owner anchors and
     * no episodic node referenced nothing the graph actually holds, so it must yield NO_MEMORY.
     *
     * <p>When neither anchor name is supplied, no node is excluded and the count equals the total seed
     * count (pre-fix behavior — fail-open to HAS_MEMORY paths so the existing seedMatches gate governs).
     */
    static int countSpecificSeedMatches(MemoryGraph graph, List<String> seedIds,
                                        String companionName, String ownerName) {
        if (graph == null || seedIds == null || seedIds.isEmpty()) {
            return 0;
        }
        String companionKey = anchorKey(companionName);
        String ownerKey = anchorKey(ownerName);
        if (companionKey == null && ownerKey == null) {
            return seedIds.size(); // no anchors known → every seed counts (fail-open)
        }
        int specific = 0;
        for (String id : seedIds) {
            MemoryNode node = graph.node(id);
            if (node == null) continue;
            // EVENT/episodic nodes are always specific (the actual shared-history provenance vertices).
            if (com.player2.playerengine.memory.MemoryNodeType.EVENT
                    == com.player2.playerengine.memory.MemoryNodeType.fromWire(node.type())) {
                specific++;
                continue;
            }
            String nameKey = anchorKey(node.canonicalName());
            boolean isAnchor = nameKey != null
                    && (nameKey.equals(companionKey) || nameKey.equals(ownerKey));
            if (!isAnchor) {
                specific++;
            }
        }
        return specific;
    }

    /** Normalizes an anchor name to a trimmed lower-case key, or null when blank. */
    private static String anchorKey(String name) {
        if (name == null) return null;
        String t = name.trim().toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    /** Maps a node to a retrieval document: id = node id; indexed text = name + aliases + tags + content. */
    private static ToolDocument toDocument(MemoryNode n) {
        // ToolDocument#indexedText concatenates name + description + whenToUse + examples + keywords.
        // We pack the node's searchable surfaces into those slots; categoryTags is not indexed.
        String name = safe(n.canonicalName());
        String content = safe(n.content());
        return new ToolDocument(
                n.id(),
                name,                 // name
                content,              // description
                safe(n.type()),      // whenToUse
                n.tags(),             // examples (indexed)
                n.aliases(),          // keywords (indexed — paraphrase/synonym coverage)
                List.of());           // categoryTags (not indexed)
    }

    /** Best-effort "now" for the {@link Retriever} adapter path: the snapshot has no clock, so use 0-relative
     *  recency (the reranker degrades gracefully — recency term saturates). The full {@link #retrieve}
     *  path always receives the real {@code currentGameTime}. */
    private long latestTick() {
        return 0L;
    }

    /**
     * Normalizes the top fused RRF score to {@code [0,1]} against the theoretical maximum: a node
     * ranked #1 in all three episodic lists contributes {@code 3 / (RRF_K + 1)}. Dividing the top
     * score by that max yields a deterministic dominance signal — a hit that tops every list scores
     * near 1.0; a hit that only appears low in one list scores near 0. Falls back gracefully when the
     * fused list is empty.
     */
    private static double normalizedTopScore(List<RetrievalHit> ranked) {
        if (ranked == null || ranked.isEmpty()) return 0.0;
        double top = ranked.get(0).score();
        if (top <= 0.0) return 0.0;
        double max = EPISODIC_LIST_COUNT / (EpisodicReranker.RRF_K + 1.0);
        if (max <= 0.0) return 0.0;
        return Math.min(1.0, top / max);
    }

    /** Number of ranked lists the episodic reranker fuses (recency / importance / relevance). */
    private static final double EPISODIC_LIST_COUNT = 3.0;

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** The scope this retriever is bound to (informational; the store is already scoped). */
    public MemoryScope scope() {
        return store == null ? null : store.scope();
    }
}
