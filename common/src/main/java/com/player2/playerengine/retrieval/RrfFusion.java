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
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * Fuses two ranked lists into a single ranked list of up to {@code topK} results.
     *
     * @param listA  ranked list from retriever A (index 0 = highest-ranked)
     * @param listB  ranked list from retriever B (index 0 = highest-ranked)
     * @param topK   maximum number of results to return
     * @param k      RRF constant, typically 60
     */
    public static List<RetrievalHit> fuse(List<RetrievalHit> listA,
                                          List<RetrievalHit> listB,
                                          int topK,
                                          double k) {
        // score accumulator and per-retriever rank tracker
        Map<String, double[]>  scores = new HashMap<>();
        Map<String, int[]>     ranks  = new HashMap<>();

        addList(listA, scores, ranks, 0, k);
        addList(listB, scores, ranks, 1, k);

        List<Map.Entry<String, double[]>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        List<RetrievalHit> result = new ArrayList<>();
        int limit = Math.min(topK, entries.size());
        for (int i = 0; i < limit; i++) {
            String id    = entries.get(i).getKey();
            double score = entries.get(i).getValue()[0];
            int[]  r     = ranks.getOrDefault(id, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE});
            result.add(new RetrievalHit(id, score, r[0], r[1]));
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
            int[]  r = ranks.computeIfAbsent(id,
                    x -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE});
            r[listIdx] = oneIndexedRank;
        }
    }
}
