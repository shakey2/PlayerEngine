package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MergePlan;
import com.player2.playerengine.memory.ingest.MemoryExplicitFactExtractor;
import com.player2.playerengine.memory.retrieval.EgoGraphTraversal.EgoNode;
import com.player2.playerengine.memory.retrieval.MemoryRetrievalConfidence.BoundaryVerdict;
import com.player2.playerengine.retrieval.RetrievalHit;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight self-test for memory retrieval of explicit durable facts/preferences.
 *
 * <p>Run manually with assertions enabled: {@code java -ea ...MemoryRetrieverSelfTest}.
 */
public final class MemoryRetrieverSelfTest {
    private MemoryRetrieverSelfTest() {}

    public static void main(String[] args) {
        querySynonymsNormalize();
        durablePreferenceRetrievesThroughNormalizedQuery();
        System.out.println("MemoryRetrieverSelfTest: PASS");
    }

    private static void querySynonymsNormalize() {
        String normalized = MemoryRetriever.normalizeQueryText("What is my favourite colour and videogame?");
        assert normalized.equals("What is my favorite color and video game?") : normalized;
    }

    private static void durablePreferenceRetrievesThroughNormalizedQuery() {
        MemoryGraph graph = new MemoryGraph();
        MergePlan plan = MemoryExplicitFactExtractor.buildPlan(MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favorite color is green",
                "owner", "Ellie", "Ellie"), 100L);
        assert plan != null;
        String nodeId = plan.upserts().get(0).id;
        graph.apply(plan);

        MemoryRetriever retriever = new MemoryRetriever(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        List<String> seedIds = retriever.entityLinkSeeds(
                graph, "Do you remember my favourite colour?", deadline, null);
        assert seedIds.contains(nodeId) : seedIds;

        MemoryRetriever.Thresholds thresholds = MemoryRetriever.Thresholds.defaults();
        List<EgoNode> ego = EgoGraphTraversal.traverse(
                graph,
                seedIds,
                101L,
                thresholds.maxHops,
                thresholds.maxEgoNodes,
                thresholds.decayBase,
                thresholds.gameTimeUnit,
                deadline);
        List<RetrievalHit> ranked = EpisodicReranker.rerank(ego, 101L, thresholds.topK, null);
        int specificSeedMatches = MemoryRetriever.countSpecificSeedMatches(graph, seedIds, "Ellie", "shakey2");
        double confidence = MemoryRetrievalConfidence.confidence(
                normalizedTopScore(ranked), ranked.size(), seedIds.size());
        BoundaryVerdict verdict = MemoryRetrievalConfidence.verdict(
                false, confidence, seedIds.size(), specificSeedMatches, thresholds.minConfidence);
        assert verdict == BoundaryVerdict.HAS_MEMORY : verdict;

        Optional<String> block = MemoryBlockSerializer.serialize(
                verdict, ranked, graph, thresholds.blockCharCap);
        assert block.isPresent();
        assert block.get().contains("shakey2 favorite color") : block.get();
        assert block.get().contains("green") : block.get();
    }

    private static double normalizedTopScore(List<RetrievalHit> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return 0.0;
        }
        double max = 3.0 / (EpisodicReranker.RRF_K + 1.0);
        return Math.min(1.0, ranked.get(0).score() / max);
    }
}
