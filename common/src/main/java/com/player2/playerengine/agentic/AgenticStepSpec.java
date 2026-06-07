package com.player2.playerengine.agentic;

import java.util.Map;

public record AgenticStepSpec(
        String id,
        String kind,
        Map<String, String> args,
        String rationale
) {}
