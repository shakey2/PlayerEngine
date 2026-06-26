package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryScope;
import com.player2.playerengine.memory.MemoryStore;
import com.player2.playerengine.memory.retrieval.EgoGraphTraversal.EgoNode;
import com.player2.playerengine.memory.retrieval.MemoryRetrievalConfidence.BoundaryVerdict;
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
 * <p>Per turn, deterministically and with <b>no Player2 call</b>:
 * <ol>
 *   <li><b>Fast-exit</b> {@link BoundaryVerdict#STORE_ABSENT} if memory is not patron-enabled or the
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

    public MemoryRetriever(MemoryStore store) {
        this.store = store;
    }

    /**
     * Full retrieval entry point.
     *
     * @param currentTurnText  the latest user turn text (the entity-link query)
     * @param ownerUuid        the companion owner UUID (scope; informational — store is already scoped)
     * @param companionId      the companion id (scope; informational — store is already scoped)
     * @param currentGameTime  the server game time (recency basis)
     * @param thresholds       resolved config bounds (null → {@link Thresholds#defaults()})
     * @param patronEnabled    W7's gate boolean — false short-circuits to STORE_ABSENT (zero cost)
     */
    public MemoryRetrievalResult retrieve(String currentTurnText,
                                          java.util.UUID ownerUuid,
                                          String companionId,
                                          long currentGameTime,
                                          Thresholds thresholds,
                                          boolean patronEnabled) {
        final long deadline = System.nanoTime() + BUDGET_NANOS;
        Thresholds t = thresholds != null ? thresholds : Thresholds.defaults();

        // (1) Fast-exit: disabled / non-patron / no store / empty snapshot → emit nothing.
        if (!patronEnabled || store == null) {
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

        // (3) Entity-link: rebuild the lexical+minhash index in-memory from the snapshot, then fuse.
        List<String> seedIds = entityLinkSeeds(graph, currentTurnText, deadline);
        int seedMatches = seedIds.size();

        // (4) Bounded ego-graph BFS (deadline-aware).
        List<EgoNode> ego = EgoGraphTraversal.traverse(
                graph, seedIds, currentGameTime, t.maxHops, t.maxEgoNodes,
                t.decayBase, t.gameTimeUnit, deadline);

        // (5) Episodic re-rank (three-list N-way RRF).
        List<RetrievalHit> ranked = EpisodicReranker.rerank(ego, currentGameTime, t.topK);

        // (6) Confidence + (7) boundary verdict.
        double normTop = normalizedTopScore(ranked);
        double confidence = MemoryRetrievalConfidence.confidence(normTop, ranked.size(), seedMatches);
        BoundaryVerdict verdict = MemoryRetrievalConfidence.verdict(
                /*storeAbsent*/ false, confidence, seedMatches, t.minConfidence);

        // (8) Serialize the bounded tail block.
        Optional<String> block = MemoryBlockSerializer.serialize(verdict, ranked, graph, t.blockCharCap);

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
        List<String> seeds = entityLinkSeeds(snap.graph(), goal, deadline);
        List<EgoNode> ego = EgoGraphTraversal.traverse(
                snap.graph(), seeds, latestTick(), t.maxHops, t.maxEgoNodes,
                t.decayBase, t.gameTimeUnit, deadline);
        return EpisodicReranker.rerank(ego, latestTick(), k > 0 ? k : t.topK);
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
    List<String> entityLinkSeeds(MemoryGraph graph, String turnText, long deadline) {
        if (graph == null || turnText == null || turnText.isBlank() || graph.nodeCount() == 0) {
            return List.of();
        }
        if (System.nanoTime() >= deadline) {
            return List.of(); // tick budget already spent before entity-link → seed nothing
        }
        List<ToolDocument> docs = new ArrayList<>(graph.nodeCount());
        for (MemoryNode n : graph.nodes()) {
            docs.add(toDocument(n));
        }

        LexicalIndex lexical = LexicalIndex.build(docs);
        MinHashIndex minHash = MinHashIndex.build(docs);

        List<RetrievalHit> bm25 = lexical.query(turnText, SEED_CANDIDATES);
        List<RetrievalHit> fuzzy = minHash.query(turnText, SEED_CANDIDATES);

        // W1 RRF: list A → slot 0 (lexical), list B → slot 1 (minHash).
        List<RetrievalHit> fused = RrfFusion.fuse(bm25, fuzzy, SEED_CANDIDATES, EpisodicReranker.RRF_K);

        Set<String> seeds = new LinkedHashSet<>();
        for (RetrievalHit hit : fused) {
            if (hit.id() != null) seeds.add(hit.id());
        }
        return new ArrayList<>(seeds);
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
