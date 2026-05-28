package com.player2.playerengine.retrieval.learning;

import java.util.List;

public record AliasLearnSuggestion(
        String toolId,
        List<String> addKeywords,
        List<String> addExamples,
        double confidence,
        String rationale
) {}
