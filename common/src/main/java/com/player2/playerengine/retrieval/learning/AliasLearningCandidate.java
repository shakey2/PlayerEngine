package com.player2.playerengine.retrieval.learning;

import java.util.List;
import java.util.UUID;

public record AliasLearningCandidate(
        String candidateId,
        UUID ownerUuid,
        UUID botUuid,
        String ownerUtterance,
        String toolId,
        List<String> addKeywords,
        List<String> addExamples,
        double confidence,
        String triggerReason,
        long createdAtEpochMillis
) {}
