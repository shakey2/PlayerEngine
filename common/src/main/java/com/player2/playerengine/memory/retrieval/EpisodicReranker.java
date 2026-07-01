package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.dense.DenseIndexState;
import com.player2.playerengine.memory.dense.EmbeddingModel;
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
 * <p>This is the <b>embeddings seam (W8d, site 2)</b>: when a non-null {@code turnVector} is supplied,
 * the relevance list's score source swaps from the graph-walk score to <b>cosine similarity</b> with
 * the turn vector for every node that {@code hasVector()}; a null {@code turnVector} (feature off /
 * turn-embed degraded) or a vectorless node falls back to the graph-walk {@code EgoNode::relevance},
 * <b>byte-identical</b> to the pre-W8 path. The reranker still fuses three ranked lists.
 *
 * <p>Zero-LLM, pure. No Minecraft / loader / network I/O dependency (the turn vector is computed once
 * upstream in {@code MemoryRetriever} and threaded in; this method never embeds).
 */
public final class EpisodicReranker {

    private EpisodicReranker() {}

    /** RRF constant (matches the tool-RAG default; suppresses top-of-list score gaps). */
    public static final double RRF_K = 60.0;

    /**
     * Re-ranks the ego nodes by fusing recency / importance / relevance.
     *
     * @param egoNodes   the BFS-reached nodes (each carries its graph-walk relevance score)
     * @param nowTick    current server game time (recency basis)
     * @param topK       maximum fused hits to return
     * @param turnVector the shared per-turn embedding (W8d, site 2), or null. When non-null, the
     *                   relevance list scores by cosine(turnVector, node.vector()) for nodes that
     *                   {@code hasVector()} in the frozen model; null / vectorless falls back to the
     *                   graph-walk relevance (byte-identical to the pre-W8 path).
     * @return fused {@link RetrievalHit}s ({@link RetrievalHit#id()} = node id), highest first
     */
    public static List<RetrievalHit> rerank(List<EgoNode> egoNodes, long nowTick, int topK, float[] turnVector) {
        if (egoNodes == null || egoNodes.isEmpty()) {
            return List.of();
        }
        // Precompute the turn-vector norm once for the cosine relevance swap (null => graph-walk fallback).
        final double turnNorm = (turnVector != null && turnVector.length == EmbeddingModel.FROZEN_DIMS)
                ? cosineNorm(turnVector) : -1.0;
        final boolean useCosine = turnNorm > 0.0;

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

        // --- list 3: relevance (cosine vs the turn vector for vectored nodes; else graph-walk) ---
        // The scorer swaps to cosine ONLY when a non-null turn vector was supplied AND the node carries
        // a frozen-model vector; otherwise it degrades to the graph-walk relevance (byte-identical).
        java.util.function.ToDoubleFunction<EgoNode> relevanceScorer =
                e -> relevanceScore(e, useCosine ? turnVector : null, turnNorm);
        List<EgoNode> byRelevance = new ArrayList<>(egoNodes);
        byRelevance.sort(Comparator
                .comparingDouble(relevanceScorer).reversed()
                .thenComparing(e -> e.node().id()));
        List<RetrievalHit> relevanceList = toHits(byRelevance, relevanceScorer);

        return RrfFusion.fuse(List.of(recencyList, importanceList, relevanceList), topK, RRF_K);
    }

    /**
     * Per-node relevance score: cosine(turnVector, node.vector()) when {@code turnVector != null} and the
     * node carries a frozen-model vector; otherwise the graph-walk {@code EgoNode::relevance} (pre-W8).
     */
    private static double relevanceScore(EgoNode e, float[] turnVector, double turnNorm) {
        if (turnVector != null) {
            MemoryNode n = e.node();
            if (n != null && n.hasVector()
                    && EmbeddingModel.vectorModelToken().equals(n.vectorModel())) {
                float[] v = n.vector();
                if (v != null && v.length == EmbeddingModel.FROZEN_DIMS) {
                    return DenseIndexState.cosine(turnVector, turnNorm, v);
                }
            }
        }
        return e.relevance();
    }

    /** L2 norm of a vector (for the cosine denominator). */
    private static double cosineNorm(float[] v) {
        double s = 0.0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
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
