package com.player2.playerengine.agentic.steps;

public record ResolveStorageChestParams(
        double searchRadius,
        double placementRadius,
        boolean preferExisting,
        boolean allowPlacement,
        boolean avoidLootChests,
        double timeoutSeconds
) {}
