package com.player2.playerengine.retrieval;

import java.util.Arrays;

/**
 * A single result returned by a retriever / produced by {@link RrfFusion#fuse}.
 *
 * <p>{@code retrieverRanks} holds the 1-indexed rank this document received from each
 * retriever slot (see {@link RetrieverRegistry}: slot 0 = lexical/BM25, slot 1 = MinHash,
 * slot 2 = graph, slot 3 = dense), or {@link Integer#MAX_VALUE} when the document was not
 * returned by that retriever. {@code score} is the fused RRF score.
 *
 * <p>The id accessor is {@link #toolId()} (historical, tool-oriented); {@link #id()} is a
 * generic alias delegating to it — non-tool retrievers (e.g. the memory graph retriever)
 * read {@link #id()}. The legacy {@link #bm25Rank()} / {@link #minHashRank()} accessors read
 * slots 0 / 1 so existing readers compile unchanged.
 */
public final class RetrievalHit {

    private final String toolId;
    private final double score;
    /** Per-slot 1-indexed ranks; width {@link RetrieverRegistry#WIDTH}; absent = MAX_VALUE. */
    private final int[] retrieverRanks;

    /**
     * Primary constructor. The supplied array is copied/normalized to
     * {@link RetrieverRegistry#WIDTH}; slots beyond the array length are filled with the
     * absent sentinel {@link Integer#MAX_VALUE}.
     */
    public RetrievalHit(String toolId, double score, int[] retrieverRanks) {
        this.toolId = toolId;
        this.score = score;
        this.retrieverRanks = normalize(retrieverRanks);
    }

    /**
     * Back-compat 4-arg constructor: {@code (bm25Rank → slot 0, minHashRank → slot 1)},
     * remaining slots left absent. Existing callers
     * ({@code LexicalIndex}, {@code MinHashIndex}, …) bind to this unchanged.
     */
    public RetrievalHit(String toolId, double score, int bm25Rank, int minHashRank) {
        this.toolId = toolId;
        this.score = score;
        int[] r = RetrieverRegistry.emptyRanks();
        r[RetrieverRegistry.SLOT_LEXICAL] = bm25Rank;
        r[RetrieverRegistry.SLOT_MINHASH] = minHashRank;
        this.retrieverRanks = r;
    }

    private static int[] normalize(int[] src) {
        int[] r = RetrieverRegistry.emptyRanks();
        if (src != null) {
            int n = Math.min(src.length, r.length);
            System.arraycopy(src, 0, r, 0, n);
        }
        return r;
    }

    public String toolId() { return toolId; }

    /** Generic id alias for all retrievers; delegates to {@link #toolId()}. */
    public String id() { return toolId; }

    public double score() { return score; }

    /** The full per-slot rank array (width {@link RetrieverRegistry#WIDTH}). */
    public int[] retrieverRanks() { return retrieverRanks; }

    /** 1-indexed rank from the given retriever slot, or {@link Integer#MAX_VALUE} if absent. */
    public int rank(int slot) {
        return (slot >= 0 && slot < retrieverRanks.length)
                ? retrieverRanks[slot] : Integer.MAX_VALUE;
    }

    /** Number of retriever slots tracked (== {@link RetrieverRegistry#WIDTH}). */
    public int retrieverCount() { return retrieverRanks.length; }

    public int bm25Rank()    { return rank(RetrieverRegistry.SLOT_LEXICAL); }
    public int minHashRank() { return rank(RetrieverRegistry.SLOT_MINHASH); }

    @Override
    public String toString() {
        String bm25 = bm25Rank()    == Integer.MAX_VALUE ? "—" : String.valueOf(bm25Rank());
        String mh   = minHashRank() == Integer.MAX_VALUE ? "—" : String.valueOf(minHashRank());
        return String.format("RetrievalHit{id='%s', score=%.4f, bm25=%s, minHash=%s, ranks=%s}",
                toolId, score, bm25, mh, Arrays.toString(retrieverRanks));
    }
}
