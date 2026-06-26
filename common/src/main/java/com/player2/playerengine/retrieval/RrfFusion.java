package com.player2.playerengine.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) combiner.
 *
 * <p>For each document {@code d} appearing in one or more ranked lists:
 * <pre>
 *   score(d) = Σ_i  1 / (k + rank_i(d))
 * </pre>
 * where {@code rank_i(d)} is the 1-indexed position of {@code d} in list {@code i}
 * (documents absent from a list contribute 0 to that term).
 *
 * <p>The canonical default {@code k=60} suppresses the large score gaps at the top of
 * individual ranked lists, making fusion more robust to one retriever dominating.
 *
 * <p>Fusion is N-way: {@link #fuse(List, int, double)} accepts an arbitrary number of
 * ranked input lists, each mapped to a stable retriever slot by its position in the outer
 * list (slot 0 = first list, slot 1 = second, …). The legacy 2-list overload
 * {@link #fuse(List, List, int, double)} is a thin delegate preserving the historical
 * {@code (bm25 = slot 0, minHash = slot 1)} attribution.
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * Fuses two ranked lists into a single ranked list of up to {@code topK} results.
     *
     * <p>Thin back-compat delegate: list A maps to slot 0, list B maps to slot 1 — exactly
     * the attribution this method has always produced. Existing callers
     * ({@code ToolRetriever}, {@code EllieGPSWaypointIndex}, {@code CapabilityIndex})
     * bind to this overload byte-unchanged.
     *
     * @param listA  ranked list from retriever A (index 0 = highest-ranked) → slot 0
     * @param listB  ranked list from retriever B (index 0 = highest-ranked) → slot 1
     * @param topK   maximum number of results to return
     * @param k      RRF constant, typically 60
     */
    public static List<RetrievalHit> fuse(List<RetrievalHit> listA,
                                          List<RetrievalHit> listB,
                                          int topK,
                                          double k) {
        return fuse(List.of(listA, listB), topK, k);
    }

    /**
     * N-way fusion. Each list in {@code lists} is attributed to a retriever slot equal to
     * its index in the outer list; the produced {@link RetrievalHit#retrieverRanks()} array
     * is sized to {@link RetrieverRegistry#WIDTH} with unused slots left at the absent
     * sentinel {@link Integer#MAX_VALUE}.
     *
     * @param lists  ranked input lists, one per retriever slot (outer index = slot)
     * @param topK   maximum number of results to return
     * @param k      RRF constant, typically 60
     */
    public static List<RetrievalHit> fuse(List<List<RetrievalHit>> lists,
                                          int topK,
                                          double k) {
        // score accumulator and per-retriever rank tracker (width = registry slot count)
        Map<String, double[]> scores = new HashMap<>();
        Map<String, int[]>    ranks  = new HashMap<>();

        for (int slot = 0; slot < lists.size(); slot++) {
            addList(lists.get(slot), scores, ranks, slot, k);
        }

        List<Map.Entry<String, double[]>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        List<RetrievalHit> result = new ArrayList<>();
        int limit = Math.min(topK, entries.size());
        for (int i = 0; i < limit; i++) {
            String id    = entries.get(i).getKey();
            double score = entries.get(i).getValue()[0];
            int[]  r     = ranks.getOrDefault(id, RetrieverRegistry.emptyRanks());
            result.add(new RetrievalHit(id, score, r));
        }
        return result;
    }

    private static void addList(List<RetrievalHit> list,
                                Map<String, double[]> scores,
                                Map<String, int[]> ranks,
                                int listIdx,
                                double k) {
        for (int i = 0; i < list.size(); i++) {
            String id              = list.get(i).toolId();
            int    oneIndexedRank  = i + 1;
            double contribution    = 1.0 / (k + oneIndexedRank);
            scores.computeIfAbsent(id, x -> new double[]{0.0})[0] += contribution;
            int[]  r = ranks.computeIfAbsent(id, x -> RetrieverRegistry.emptyRanks());
            r[listIdx] = oneIndexedRank;
        }
    }
}
