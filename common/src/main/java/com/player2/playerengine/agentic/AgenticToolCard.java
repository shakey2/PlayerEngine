package com.player2.playerengine.agentic;

import java.util.List;

public record AgenticToolCard(
        String toolId,
        String summary,
        String whenToUse,
        List<String> examples,
        List<String> categories,
        double retrievalScore
) {}
