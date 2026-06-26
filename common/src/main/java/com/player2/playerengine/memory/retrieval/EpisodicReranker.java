package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.retrieval.EgoGraphTraversal.EgoNode;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RrfFusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Episodic re-ranking of the ego-graph candidates (Phase D, W5 step 5).
 *
 * <p>Ranks the BFS-reached nodes three independent ways — <b>recency</b>, <b>importance</b>, and
 * <b>relevance</b> (the graph-walk score) — and fuses the three ranked lists with W1's N-way
 * Reciprocal Rank Fusion ({@link RrfFusion#fuse(List, int, double)}). Each list maps to a distinct
 * RRF input slot; the fused score balances "talked about recently", "intrinsically important", and
 * "graph-proximate to what the turn mentioned" without any single signal dominating.
 *
 * <p>This is the <b>embeddings-additive seam</b>: when W8 lands dense vectors, the relevance list's
 * score source swaps from the graph-walk score to cosine similarity with <em>no signature change</em>
 * here — the reranker still fuses three ranked lists.
 *
 * <p>Zero-LLM, pure. No Minecraft / loader / I/O dependency.
 */
public final class EpisodicReranker {

    private EpisodicReranker() {}

    /** RRF constant (matches the tool-RAG default; suppresses top-of-list score gaps). */
    public static final double RRF_K = 60.0;

    /**
     * Re-ranks the ego nodes by fusing recency / importance / relevance.
     *
     * @param egoNodes the BFS-reached nodes (each carries its graph-walk relevance score)
     * @param nowTick  current server game time (recency basis)
     * @param topK     maximum fused hits to return
     * @return fused {@link RetrievalHit}s ({@link RetrievalHit#id()} = node id), highest first
     */
    public static List<RetrievalHit> rerank(List<EgoNode> egoNodes, long nowTick, int topK) {
        if (egoNodes == null || egoNodes.isEmpty()) {
            return List.of();
        }

        // --- list 1: recency (most-recently-seen first) ---
        List<EgoNode> byRecency = new ArrayList<>(egoNodes);
        byRecency.sort(Comparator
                .comparingLong((EgoNode e) -> e.node().lastSeenTick()).reversed()
                .thenComparing(e -> e.node().id()));
        List<RetrievalHit> recencyList = toHits(byRecency, e -> recencyScore(e.node(), nowTick));

        // --- list 2: importance (highest importance first) ---
        List<EgoNode> byImportance = new ArrayList<>(egoNodes);
        byImportance.sort(Comparator
                .comparingInt((EgoNode e) -> e.node().importance()).reversed()
                .thenComparing(e -> e.node().id()));
        List<RetrievalHit> importanceList = toHits(byImportance, e -> (double) e.node().importance());

        // --- list 3: relevance (graph-walk score first; the dense-swap seam) ---
        List<EgoNode> byRelevance = new ArrayList<>(egoNodes);
        byRelevance.sort(Comparator
                .comparingDouble(EgoNode::relevance).reversed()
                .thenComparing(e -> e.node().id()));
        List<RetrievalHit> relevanceList = toHits(byRelevance, EgoNode::relevance);

        return RrfFusion.fuse(List.of(recencyList, importanceList, relevanceList), topK, RRF_K);
    }

    /** Per-node recency score: {@code DECAY_BASE ^ (ticksSinceLastSeen / TICKS_PER_GAME_HOUR)}. */
    static double recencyScore(MemoryNode node, long nowTick) {
        long delta = Math.max(0L, nowTick - node.lastSeenTick());
        double hours = (double) delta / (double) MemoryCaps.TICKS_PER_GAME_HOUR;
        return Math.pow(MemoryCaps.DECAY_BASE, hours);
    }

    /** Converts an already-sorted ego list into ranked {@link RetrievalHit}s (rank = list position). */
    private static List<RetrievalHit> toHits(List<EgoNode> sorted, java.util.function.ToDoubleFunction<EgoNode> score) {
        List<RetrievalHit> hits = new ArrayList<>(sorted.size());
        for (EgoNode e : sorted) {
            // RrfFusion attributes rank by list position; the carried score is informational only.
            hits.add(new RetrievalHit(e.node().id(), score.applyAsDouble(e), new int[0]));
        }
        return hits;
    }
}
