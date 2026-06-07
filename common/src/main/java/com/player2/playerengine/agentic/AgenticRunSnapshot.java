package com.player2.playerengine.agentic;

public record AgenticRunSnapshot(
        String runId,
        String goalSummary,
        int activeStepIndex,
        String activeStepKind,
        String state,
        String lastMessage,
        String planningSource,
        String storageProgress,
        String depositProgress,
        String labelProgress,
        String storageTargetSummary
) {}
