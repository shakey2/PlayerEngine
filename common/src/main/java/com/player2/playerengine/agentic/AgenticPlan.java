package com.player2.playerengine.agentic;

import java.util.List;

public record AgenticPlan(
        int schemaVersion,
        String goalSummary,
        List<AgenticStepSpec> steps,
        String plannerNote
) {}
