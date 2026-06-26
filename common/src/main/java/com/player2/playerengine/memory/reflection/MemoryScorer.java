package com.player2.playerengine.memory.reflection;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure, zero-LLM, server-thread-safe retrieval scorer (Phase D, W6) — the <b>only</b> W6
 * component that runs on the per-turn hot path. It re-ranks W5's candidate {@link MemoryNode}s by a
 * blended score and returns the top-{@code k}.
 *
 * <p><b>Score model</b> (each component min-max normalized to {@code [0,1]} across the candidate set,
 * then summed equally):
 * <pre>
 *   score = recency + importance + relevance
 *   recency    = exp(-hoursSinceLastRetrieval / halfLife)   // halfLife default 72 GAME-hours
 *   importance = storedImportance / 10                       // 1..10 rubric; 0 = unscored
 *   relevance  = W5's RRF-fused score (passed in, already cross-retriever-fused)
 * </pre>
 * Recency uses {@link MemoryNode#lastRetrievedTick()} (W5 stamps it on retrieval). Each raw component
 * is min-max normalized across the candidate set <em>before</em> summing so no single axis dominates
 * by scale; ties break deterministically by node id so ordering is reproducible (prefix-cache /
 * test-stability requirement).
 *
 * <p><b>No LLM, no Minecraft, no loader, no I/O.</b> It reads only immutable {@link MemoryNode}
 * fields and a relevance double supplied by the caller. It never reads a log, captures a stream, or
 * concatenates a {@code Throwable}. It is safe to call on the server tick (this is the hot path; the
 * LLM W6 components — {@link ImportanceScorer}, {@link ReflectionService} — are off-tick and gated).
 */
public final class MemoryScorer {

    /**
     * Default recency half-life in <b>game-hours</b> since last retrieval (plan placeholder; the
     * config key {@code memoryRetrievalHalfLifeGameHours} overrides at the call site). After one
     * half-life the recency component has decayed to 0.5.
     */
    public static final double DEFAULT_HALF_LIFE_GAME_HOURS = 72.0;

    /** Default number of scored hits returned (plan {@code memoryRetrievalTopK}). */
    public static final int DEFAULT_TOP_K = 5;

    /** The rubric ceiling used to normalize stored importance into [0,1]. */
    private static final double IMPORTANCE_RUBRIC_MAX = 10.0;

    private MemoryScorer() {}

    /**
     * One W5 candidate: a graph node plus its RRF-fused relevance score (higher = more relevant).
     * W5 produces these from its ego-graph traversal + RRF fusion; the scorer blends in recency and
     * importance and re-ranks.
     */
    public static final class Candidate {
        private final MemoryNode node;
        private final double relevance;

        public Candidate(MemoryNode node, double relevance) {
            this.node = node;
            this.relevance = relevance;
        }

        public MemoryNode node()    { return node; }
        public double relevance()   { return relevance; }
    }

    /** A scored, re-ranked candidate (descending {@link #score()}). */
    public static final class Scored {
        private final MemoryNode node;
        private final double score;

        Scored(MemoryNode node, double score) {
            this.node = node;
            this.score = score;
        }

        public MemoryNode node()  { return node; }
        public double score()     { return score; }
    }

    /**
     * Scores and re-ranks the candidates with the default half-life and top-k. See
     * {@link #score(List, long, double, int)}.
     */
    public static List<Scored> score(List<Candidate> candidates, long nowTick) {
        return score(candidates, nowTick, DEFAULT_HALF_LIFE_GAME_HOURS, DEFAULT_TOP_K);
    }

    /**
     * Scores and re-ranks the candidates, returning the top-{@code k} by blended score (descending),
     * ties broken by node id (ascending) for determinism.
     *
     * @param candidates        the W5 candidate set (node + RRF relevance); null/empty → empty list
     * @param nowTick           the current server game tick (recency basis)
     * @param halfLifeGameHours recency half-life in game-hours (&gt; 0; falls back to default if not)
     * @param topK              max hits to return (&le; 0 → default)
     * @return the top-k scored nodes, descending by score; never null
     */
    public static List<Scored> score(List<Candidate> candidates,
                                     long nowTick,
                                     double halfLifeGameHours,
                                     int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        double halfLife = (halfLifeGameHours > 0) ? halfLifeGameHours : DEFAULT_HALF_LIFE_GAME_HOURS;
        int k = (topK > 0) ? topK : DEFAULT_TOP_K;

        int n = candidates.size();
        double[] recencyRaw = new double[n];
        double[] importanceRaw = new double[n];
        double[] relevanceRaw = new double[n];

        for (int i = 0; i < n; i++) {
            Candidate c = candidates.get(i);
            MemoryNode node = c.node();
            recencyRaw[i] = recencyComponent(node, nowTick, halfLife);
            importanceRaw[i] = importanceComponent(node);
            relevanceRaw[i] = c.relevance();
        }

        minMaxNormalizeInPlace(recencyRaw);
        minMaxNormalizeInPlace(importanceRaw);
        minMaxNormalizeInPlace(relevanceRaw);

        List<Scored> scored = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double total = recencyRaw[i] + importanceRaw[i] + relevanceRaw[i];
            scored.add(new Scored(candidates.get(i).node(), total));
        }

        scored.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing(s -> idOf(s.node())));

        if (scored.size() > k) {
            return new ArrayList<>(scored.subList(0, k));
        }
        return scored;
    }

    // -------------------------------------------------------------------------
    // Component computation
    // -------------------------------------------------------------------------

    /**
     * Raw recency = {@code exp(-hoursSinceLastRetrieval / halfLife)}, where hours are game-hours
     * derived from {@link MemoryCaps#TICKS_PER_GAME_HOUR}. A node never retrieved
     * ({@code lastRetrievedTick <= 0}) is treated as maximally stale on the retrieval axis (0.0
     * before normalization) so it leans on importance/relevance instead.
     */
    private static double recencyComponent(MemoryNode node, long nowTick, double halfLifeGameHours) {
        long lastRetrieved = node.lastRetrievedTick();
        if (lastRetrieved <= 0L) {
            return 0.0;
        }
        long ticksSince = Math.max(0L, nowTick - lastRetrieved);
        double hoursSince = (double) ticksSince / (double) MemoryCaps.TICKS_PER_GAME_HOUR;
        return Math.exp(-hoursSince / halfLifeGameHours);
    }

    /** Raw importance = {@code storedImportance / 10}, clamped into {@code [0,1]}. */
    private static double importanceComponent(MemoryNode node) {
        double v = node.importance() / IMPORTANCE_RUBRIC_MAX;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    // -------------------------------------------------------------------------
    // Normalization
    // -------------------------------------------------------------------------

    /**
     * Min-max normalizes {@code values} into {@code [0,1]} in place. When all values are equal
     * (zero range) every entry becomes 0.0 so a flat axis contributes nothing rather than a constant
     * (keeps the blend driven by the axes that actually discriminate).
     */
    private static void minMaxNormalizeInPlace(double[] values) {
        if (values.length == 0) return;
        double min = values[0];
        double max = values[0];
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range <= 0.0) {
            for (int i = 0; i < values.length; i++) values[i] = 0.0;
            return;
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = (values[i] - min) / range;
        }
    }

    private static String idOf(MemoryNode node) {
        String id = node.id();
        return id == null ? "" : id;
    }
}
