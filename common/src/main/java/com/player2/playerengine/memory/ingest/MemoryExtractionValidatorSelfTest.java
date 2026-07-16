package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryNodeType;

/**
 * Tiny assertion self-test for durable fact/preference validation.
 *
 * <p>Run manually with assertions enabled: {@code java -ea ...MemoryExtractionValidatorSelfTest}.
 */
public final class MemoryExtractionValidatorSelfTest {
    private MemoryExtractionValidatorSelfTest() {}

    public static void main(String[] args) {
        keepsExplicitPreference();
        normalizesPreferenceMisclassifiedAsFact();
        keepsStableFact();
        rejectsEpisodicFact();
        System.out.println("MemoryExtractionValidatorSelfTest: PASS");
    }

    private static void keepsExplicitPreference() {
        MemoryExtractionResponse response = MemoryExtractionValidator.validate("""
                {"entities":[{"name":"shakey2 favorite color","type":"preference","content":"shakey2's favorite color is green.","tags":["favorite","color","green"],"importance":6}],"relations":[],"keywords":["color"],"importance":4}
                """);
        assert response != null;
        assert response.entities().size() == 1;
        assert response.entities().get(0).type.equals(MemoryNodeType.PREFERENCE.wire());
    }

    private static void normalizesPreferenceMisclassifiedAsFact() {
        MemoryExtractionResponse response = MemoryExtractionValidator.validate("""
                {"entities":[{"name":"shakey2 favorite game","type":"fact","content":"shakey2's favorite game is Minecraft.","tags":["favorite","game","minecraft"],"importance":6}],"relations":[],"keywords":["game"],"importance":4}
                """);
        assert response != null;
        assert response.entities().size() == 1;
        assert response.entities().get(0).type.equals(MemoryNodeType.PREFERENCE.wire());
    }

    private static void keepsStableFact() {
        MemoryExtractionResponse response = MemoryExtractionValidator.validate("""
                {"entities":[{"name":"shakey2 birthday","type":"fact","content":"shakey2's birthday is May 4.","tags":["birthday"],"importance":6}],"relations":[],"keywords":["birthday"],"importance":4}
                """);
        assert response != null;
        assert response.entities().size() == 1;
        assert response.entities().get(0).type.equals(MemoryNodeType.FACT.wire());
    }

    private static void rejectsEpisodicFact() {
        MemoryExtractionResponse response = MemoryExtractionValidator.validate("""
                {"entities":[{"name":"Ellie death test","type":"fact","content":"An AI companion who was slain by shakey2 and automatically revived.","tags":["death","test"],"importance":4}],"relations":[],"keywords":["death"],"importance":3}
                """);
        assert response == null;
    }
}
