package com.player2.playerengine.retrieval.learning;

import com.player2.playerengine.retrieval.RetrievalConfidence;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.ToolMetadataRegistry;

import java.util.List;
import java.util.stream.Collectors;

public final class DeepCheckPrompt {

    public static final int SCHEMA_VERSION = 1;

    private DeepCheckPrompt() {}

    public static String buildUserPrompt(
            String ownerUtterance,
            List<RetrievalHit> firstPassHits,
            ToolMetadataRegistry registry,
            RetrievalConfidence confidence) {
        String toolSummary = firstPassHits.stream()
                .limit(8)
                .map(h -> {
                    ToolDocument doc = registry.getDocument(h.toolId());
                    String name = doc != null ? doc.name() : h.toolId();
                    return h.toolId() + " (" + name + ")";
                })
                .collect(Collectors.joining(", "));

        return """
                You help repair weak Minecraft NPC command retrieval. Reply with JSON only, no markdown.
                Schema:
                {
                  "schemaVersion": 1,
                  "retryQueries": ["up to 5 short alternative search phrases"],
                  "learn": {
                    "toolId": "registered_command_id",
                    "addKeywords": ["optional keywords"],
                    "addExamples": ["optional example phrases"],
                    "confidence": 0.0,
                    "rationale": "brief audit note"
                  }
                }
                Rules:
                - retryQueries is required (1-5 strings, 3-80 chars each, single line).
                - learn is optional; toolId must be a real command id from first pass if unsure.
                - confidence must be 0.0 to 0.7. Never invent new commands.
                - Do not include command prefixes like @ in examples.

                Owner utterance: %s
                Retrieval confidence: %s (top=%.4f gap=%.4f coverage=%.2f)
                First-pass tools: %s
                """
                .formatted(
                        ownerUtterance,
                        confidence.reason(),
                        confidence.topScore(),
                        confidence.scoreGapRatio(),
                        confidence.queryTokenCoverage(),
                        toolSummary.isEmpty() ? "(none)" : toolSummary);
    }
}
