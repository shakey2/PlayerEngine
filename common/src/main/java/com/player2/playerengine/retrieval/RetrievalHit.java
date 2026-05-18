package com.player2.playerengine.retrieval;

/**
 * A single result returned by {@link ToolRetriever#retrieve}.
 *
 * <p>{@code bm25Rank} and {@code minHashRank} are 1-indexed ranks from each individual
 * retriever (or {@link Integer#MAX_VALUE} when the document was not returned by that
 * retriever). {@code score} is the fused RRF score.
 */
public final class RetrievalHit {

    private final String toolId;
    private final double score;
    private final int bm25Rank;
    private final int minHashRank;

    public RetrievalHit(String toolId, double score, int bm25Rank, int minHashRank) {
        this.toolId = toolId;
        this.score = score;
        this.bm25Rank = bm25Rank;
        this.minHashRank = minHashRank;
    }

    public String toolId()    { return toolId; }
    public double score()     { return score; }
    public int bm25Rank()     { return bm25Rank; }
    public int minHashRank()  { return minHashRank; }

    @Override
    public String toString() {
        String bm25  = bm25Rank     == Integer.MAX_VALUE ? "—" : String.valueOf(bm25Rank);
        String mh    = minHashRank  == Integer.MAX_VALUE ? "—" : String.valueOf(minHashRank);
        return String.format("RetrievalHit{id='%s', score=%.4f, bm25=%s, minHash=%s}",
                toolId, score, bm25, mh);
    }
}
