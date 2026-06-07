package com.player2.playerengine.agentic.steps;

import java.util.List;

public record GatherLooseItemsParams(
        double radius,
        int maxItems,
        int maxStacks,
        double settleSeconds,
        double timeoutSeconds,
        boolean freeInventoryIfFull,
        List<String> itemIdFilters
) {}
