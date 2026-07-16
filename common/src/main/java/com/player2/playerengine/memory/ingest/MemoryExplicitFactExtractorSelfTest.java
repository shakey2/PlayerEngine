package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryNodeType;
import com.player2.playerengine.memory.MergePlan;

import java.util.List;

/**
 * Lightweight self-test for explicit durable memory extraction.
 *
 * <p>Run manually with assertions enabled: {@code java -ea ...MemoryExplicitFactExtractorSelfTest}.
 */
public final class MemoryExplicitFactExtractorSelfTest {
    private MemoryExplicitFactExtractorSelfTest() {}

    public static void main(String[] args) {
        favoriteColorAddressedToCompanion();
        likedColorAddressedToCompanion();
        likedNamedColorWithoutColorWord();
        colorPhrasingsShareCanonicalNode();
        favoriteVideoGameTopicNormalizes();
        rememberFact();
        questionDoesNotBecomeFact();
        correctedFavoriteReplacesPriorValue();
        legacyColorPreferenceCanonicalMigrates();
        System.out.println("MemoryExplicitFactExtractorSelfTest: PASS");
    }

    private static void favoriteColorAddressedToCompanion() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> facts = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: ellie my favorite color is green",
                "owner", "Ellie", "Ellie");
        assert facts.size() == 1;
        assert facts.get(0).type().equals(MemoryNodeType.PREFERENCE.wire());
        assert facts.get(0).content().equals("shakey2's favorite color is green.");
        MergePlan plan = MemoryExplicitFactExtractor.buildPlan(facts, 42L);
        assert plan != null;
        assert plan.upserts().size() == 1;
        assert plan.edges().size() == 1;
    }

    private static void likedColorAddressedToCompanion() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> facts = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: Ellie, I like the color green.",
                "owner", "Ellie", "Ellie");
        assert facts.size() == 1;
        assert facts.get(0).canonicalName().equals("shakey2 favorite color");
        assert facts.get(0).content().equals("shakey2 likes the color green.");
    }

    private static void likedNamedColorWithoutColorWord() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> facts = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: I like green.",
                "owner", "Ellie", "Ellie");
        assert facts.size() == 1;
        assert facts.get(0).canonicalName().equals("shakey2 favorite color");
        assert facts.get(0).content().equals("shakey2 likes the color green.");
    }

    private static void colorPhrasingsShareCanonicalNode() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> liked = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: I like the color green.",
                "owner", "Ellie", "Ellie");
        List<MemoryExplicitFactExtractor.ExplicitMemory> favorite = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favourite colour is blue",
                "owner", "Ellie", "Ellie");
        assert liked.size() == 1;
        assert favorite.size() == 1;
        assert liked.get(0).canonicalName().equals("shakey2 favorite color");
        assert favorite.get(0).canonicalName().equals(liked.get(0).canonicalName());

        MemoryGraph graph = new MemoryGraph();
        MergePlan first = MemoryExplicitFactExtractor.buildPlan(liked, 10L);
        MergePlan second = MemoryExplicitFactExtractor.buildPlan(favorite, 20L);
        assert first != null;
        assert second != null;
        String id = first.upserts().get(0).id;
        assert second.upserts().get(0).id.equals(id);
        graph.apply(first);
        graph.setNodeVector(id, new float[] { 1.0f, 0.0f }, "test-model");
        graph.apply(second);

        MemoryNode corrected = graph.node(id);
        assert graph.nodeCount() == 1;
        assert corrected != null;
        assert corrected.content().equals("shakey2's favorite color is blue.") : corrected.content();
        assert corrected.tags().contains("blue") : corrected.tags();
        assert !corrected.tags().contains("green") : corrected.tags();
        assert !corrected.hasVector();
    }

    private static void favoriteVideoGameTopicNormalizes() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> compact = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favorite videogame is Minecraft",
                "owner", "Ellie", "Ellie");
        List<MemoryExplicitFactExtractor.ExplicitMemory> spaced = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favorite video game is Minecraft",
                "owner", "Ellie", "Ellie");
        assert compact.size() == 1;
        assert spaced.size() == 1;
        assert compact.get(0).canonicalName().equals("shakey2 favorite video game");
        assert spaced.get(0).canonicalName().equals(compact.get(0).canonicalName());
    }

    private static void rememberFact() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> facts = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: remember that my birthday is May 4",
                "owner", "Ellie", "Ellie");
        assert facts.size() == 1;
        assert facts.get(0).type().equals(MemoryNodeType.FACT.wire());
        assert facts.get(0).content().equals("shakey2's birthday is May 4.");
    }

    private static void questionDoesNotBecomeFact() {
        List<MemoryExplicitFactExtractor.ExplicitMemory> facts = MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: do you remember my favorite color?",
                "owner", "Ellie", "Ellie");
        assert facts.isEmpty();
    }

    private static void correctedFavoriteReplacesPriorValue() {
        MemoryGraph graph = new MemoryGraph();

        MergePlan first = MemoryExplicitFactExtractor.buildPlan(MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favorite color is green",
                "owner", "Ellie", "Ellie"), 10L);
        assert first != null;
        String id = first.upserts().get(0).id;
        graph.apply(first);
        graph.setNodeVector(id, new float[] { 1.0f, 0.0f }, "test-model");

        MergePlan correction = MemoryExplicitFactExtractor.buildPlan(MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: actually my favorite color is blue",
                "owner", "Ellie", "Ellie"), 20L);
        assert correction != null;
        graph.apply(correction);

        MemoryNode corrected = graph.node(id);
        assert graph.nodeCount() == 1;
        assert corrected != null;
        assert corrected.content().equals("shakey2's favorite color is blue.") : corrected.content();
        assert corrected.tags().contains("blue") : corrected.tags();
        assert !corrected.tags().contains("green") : corrected.tags();
        assert !corrected.hasVector();
        assert corrected.mentionCount() == 2 : corrected.mentionCount();
    }

    private static void legacyColorPreferenceCanonicalMigrates() {
        MemoryGraph graph = new MemoryGraph();
        graph.mergeNode(new MemoryNode(
                "legacy-color",
                "shakey2 likes the color green.",
                MemoryNodeType.PREFERENCE.wire(),
                "shakey2 color preference",
                new String[0],
                new String[] { "preference", "color", "green" },
                6,
                1L,
                1L,
                1L,
                0L,
                1,
                new float[] { 1.0f, 0.0f },
                "test-model",
                MemoryNode.CURRENT_SCHEMA_VERSION));

        MemoryNode migrated = graph.node("legacy-color");
        assert migrated != null;
        assert migrated.canonicalName().equals("shakey2 favorite color") : migrated.canonicalName();

        MergePlan correction = MemoryExplicitFactExtractor.buildPlan(MemoryExplicitFactExtractor.extract(
                "User Message: [shakey2]: my favourite colour is blue",
                "owner", "Ellie", "Ellie"), 20L);
        assert correction != null;
        graph.apply(correction);

        MemoryNode corrected = graph.node("legacy-color");
        assert graph.nodeCount() == 1;
        assert corrected != null;
        assert corrected.content().equals("shakey2's favorite color is blue.") : corrected.content();
        assert corrected.tags().contains("blue") : corrected.tags();
        assert !corrected.tags().contains("green") : corrected.tags();
        assert !corrected.hasVector();
    }
}
