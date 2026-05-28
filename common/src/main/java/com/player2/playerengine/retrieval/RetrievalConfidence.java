package com.player2.playerengine.retrieval;

/**
 * Deterministic confidence signals for a single retrieval pass (Phase B5).
 */
public record RetrievalConfidence(
        double topScore,
        double secondScore,
        double scoreGapRatio,
        double queryTokenCoverage,
        boolean empty,
        boolean weak,
        String reason
) {
    public static final String REASON_EMPTY = "empty";
    public static final String REASON_LOW_SCORE = "low_score";
    public static final String REASON_SMALL_GAP = "small_gap";
    public static final String REASON_LOW_COVERAGE = "low_coverage";
    public static final String REASON_STRONG = "strong";
}
