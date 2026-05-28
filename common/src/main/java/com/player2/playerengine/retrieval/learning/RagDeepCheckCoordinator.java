package com.player2.playerengine.retrieval.learning;

import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.retrieval.RetrievalConfidenceThresholds;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RetrievalResult;
import com.player2.playerengine.retrieval.ToolRetriever;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Merges deep-check retry queries into local retrieval results.
 */
public final class RagDeepCheckCoordinator {

    private static final Logger LOGGER = LogManager.getLogger(RagDeepCheckCoordinator.class);

    private RagDeepCheckCoordinator() {}

    public record RetryMergeResult(
            RetrievalResult activeResult,
            String promptSource,
            Optional<AliasLearnSuggestion> learnSuggestion,
            boolean deepCheckAttempted
    ) {}

    public static RetryMergeResult maybeImproveRetrieval(
            ToolRetriever retriever,
            String goalText,
            int topK,
            Set<String> categoryFilter,
            RetrievalConfidenceThresholds thresholds,
            RetrievalResult firstPass,
            Player2APIService apiService,
            CommandExecutor commandExecutor,
            Player2PayerResolution.ApiBillingContext billing,
            boolean allowDeepCheck) {
        if (!allowDeepCheck || !firstPass.confidence().weak()) {
            return new RetryMergeResult(firstPass, "first_pass", Optional.empty(), false);
        }

        RagDeepCheckPipeline.ApplyResult applied = RagDeepCheckPipeline.apply(
                retriever,
                goalText,
                topK,
                categoryFilter,
                thresholds,
                firstPass,
                apiService,
                commandExecutor,
                billing,
                true,
                "heuristic_weak");
        if (!applied.success()) {
            return new RetryMergeResult(
                    applied.activeResult(), applied.promptSource(), applied.learnSuggestion(), applied.deepCheckAttempted());
        }
        LOGGER.info("[B5] retrieval prompt source={} weak={}",
                applied.promptSource(), firstPass.confidence().reason());
        return new RetryMergeResult(
                applied.activeResult(), applied.promptSource(), applied.learnSuggestion(), true);
    }

    public static List<RetrievalHit> mergeHitsByBestScore(List<RetrievalHit>... lists) {
        Map<String, RetrievalHit> byId = new LinkedHashMap<>();
        for (List<RetrievalHit> list : lists) {
            if (list == null) continue;
            for (RetrievalHit hit : list) {
                RetrievalHit existing = byId.get(hit.toolId());
                if (existing == null || hit.score() > existing.score()) {
                    byId.put(hit.toolId(), hit);
                }
            }
        }
        List<RetrievalHit> merged = new ArrayList<>(byId.values());
        merged.sort((a, b) -> Double.compare(b.score(), a.score()));
        return merged;
    }
}
