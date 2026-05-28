package com.player2.playerengine.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Computes {@link RetrievalConfidence} from fused hits and query text.
 */
final class RetrievalConfidenceCalculator {

    private static final double EPSILON = 1e-9;

    private RetrievalConfidenceCalculator() {}

    static RetrievalConfidence compute(
            String goal,
            List<RetrievalHit> hits,
            ToolMetadataRegistry registry,
            RetrievalConfidenceThresholds thresholds) {
        if (hits == null || hits.isEmpty()) {
            boolean weak = thresholds.forceDeepCheckOnEmpty();
            return new RetrievalConfidence(
                    0.0, 0.0, 0.0, 0.0, true, weak, RetrievalConfidence.REASON_EMPTY);
        }

        double topScore = hits.get(0).score();
        double secondScore = hits.size() > 1 ? hits.get(1).score() : 0.0;
        double gapRatio = (topScore - secondScore) / Math.max(topScore, EPSILON);
        double coverage = queryTokenCoverage(goal, hits.get(0).toolId(), registry);

        String reason = RetrievalConfidence.REASON_STRONG;
        boolean weak = false;

        if (topScore < thresholds.weakBelowScore()) {
            weak = true;
            reason = RetrievalConfidence.REASON_LOW_SCORE;
        } else if (hits.size() > 1 && gapRatio < thresholds.weakGapRatio()) {
            weak = true;
            reason = RetrievalConfidence.REASON_SMALL_GAP;
        } else if (coverage < thresholds.weakTokenCoverage()) {
            weak = true;
            reason = RetrievalConfidence.REASON_LOW_COVERAGE;
        }

        return new RetrievalConfidence(topScore, secondScore, gapRatio, coverage, false, weak, reason);
    }

    private static double queryTokenCoverage(String goal, String topToolId, ToolMetadataRegistry registry) {
        String[] queryTokens = RetrievalTokenizer.tokenize(goal);
        if (queryTokens.length == 0) {
            return 1.0;
        }
        ToolDocument doc = registry.getDocument(topToolId);
        if (doc == null) {
            return 0.0;
        }
        String searchable = doc.indexedText().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (String t : queryTokens) {
            if (searchable.contains(t)) {
                seen.add(t);
            }
        }
        return (double) seen.size() / queryTokens.length;
    }
}
