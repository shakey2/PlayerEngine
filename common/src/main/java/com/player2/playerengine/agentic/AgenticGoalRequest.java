package com.player2.playerengine.agentic;

import java.util.UUID;

public record AgenticGoalRequest(
        int schemaVersion,
        UUID ownerUuid,
        UUID botUuid,
        String goalText,
        String source,
        long createdAtEpochMillis
) {}
