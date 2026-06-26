package com.player2.playerengine.memory.retrieval;

/**
 * Deterministic, zero-LLM confidence proxy for one memory-retrieval pass (Phase D, W5).
 *
 * <p>This is the knowledge-boundary lever (RoleRAG §3.4 deterministic analogue): it converts the
 * fused retrieval signals into a single confidence in {@code [0,1]} and a {@link BoundaryVerdict}.
 * It is <b>named distinctly</b> from the existing {@code retrieval.RetrievalConfidence} (the tool-RAG
 * confidence calculator this mirrors) so the two are never shadowed or imported in place of one
 * another — they live in different packages and answer different questions.
 *
 * <p>Formula (all terms in {@code [0,1]}, weights sum to 1):
 * <pre>
 *   confidence = W_TOP·normTopScore
 *              + W_HITS·min(1, hits / HIT_TARGET)
 *              + W_SEED·min(1, seedMatches / SEED_TARGET)
 * </pre>
 * with two hard overrides that force {@link BoundaryVerdict#NO_MEMORY} regardless of the score:
 * <ul>
 *   <li>{@code seedNodeMatches == 0} — the turn linked to nothing in the graph; the companion has
 *       no memory of the subject, so it must decline rather than fabricate.</li>
 *   <li>{@code confidence < MIN_CONFIDENCE} ({@value #MIN_CONFIDENCE}) — too weak to trust.</li>
 * </ul>
 *
 * <p>Zero-LLM, no Minecraft / loader / I/O dependency. All constants are playtest-tunable placeholders.
 */
public final class MemoryRetrievalConfidence {

    private MemoryRetrievalConfidence() {}

    /** Verdict of the knowledge-boundary gate. */
    public enum BoundaryVerdict {
        /** Store empty / not patron-enabled — emit nothing (request byte-identical to pre-W5). */
        STORE_ABSENT,
        /** Linked but below confidence — emit a templated decline note (do not fabricate). */
        NO_MEMORY,
        /** Confident recall — emit the serialized subgraph block. */
        HAS_MEMORY
    }

    // -------------------------------------------------------------------------
    // Tunable constants (playtest placeholders)
    // -------------------------------------------------------------------------

    /** Weight on the normalized top fused score. */
    public static final double W_TOP = 0.5;
    /** Weight on the saturating hit-count term. */
    public static final double W_HITS = 0.25;
    /** Weight on the saturating seed-match term. */
    public static final double W_SEED = 0.25;

    /** Hit count at which the hit-count term saturates to 1. */
    public static final int HIT_TARGET = 5;
    /** Seed-match count at which the seed term saturates to 1. */
    public static final int SEED_TARGET = 3;

    /** Below this confidence the verdict is {@link BoundaryVerdict#NO_MEMORY}. */
    public static final double MIN_CONFIDENCE = 0.20;

    /**
     * Computes the deterministic confidence in {@code [0,1]}.
     *
     * @param normTopScore     the top fused hit's score normalized to {@code [0,1]}
     *                         (already max-normalized by the reranker; clamped defensively here)
     * @param hits             the number of merged hits surfaced
     * @param seedNodeMatches  the number of distinct seed nodes the turn linked to
     */
    public static double confidence(double normTopScore, int hits, int seedNodeMatches) {
        double top = clamp01(normTopScore);
        double hitTerm = Math.min(1.0, HIT_TARGET <= 0 ? 0.0 : (double) Math.max(0, hits) / HIT_TARGET);
        double seedTerm = Math.min(1.0, SEED_TARGET <= 0 ? 0.0 : (double) Math.max(0, seedNodeMatches) / SEED_TARGET);
        return clamp01(W_TOP * top + W_HITS * hitTerm + W_SEED * seedTerm);
    }

    /**
     * Maps a confidence + signal counts to a {@link BoundaryVerdict}, applying the two hard
     * overrides. {@code storeAbsent} short-circuits to {@link BoundaryVerdict#STORE_ABSENT}.
     *
     * @param minConfidence the threshold below which the verdict is {@code NO_MEMORY}
     *                      (config {@code memoryMinConfidence}; falls back to {@link #MIN_CONFIDENCE}
     *                      when non-positive)
     */
    public static BoundaryVerdict verdict(boolean storeAbsent, double confidence,
                                          int seedNodeMatches, double minConfidence) {
        if (storeAbsent) {
            return BoundaryVerdict.STORE_ABSENT;
        }
        if (seedNodeMatches <= 0) {
            return BoundaryVerdict.NO_MEMORY;
        }
        double floor = minConfidence > 0.0 ? minConfidence : MIN_CONFIDENCE;
        return confidence < floor ? BoundaryVerdict.NO_MEMORY : BoundaryVerdict.HAS_MEMORY;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }
}
