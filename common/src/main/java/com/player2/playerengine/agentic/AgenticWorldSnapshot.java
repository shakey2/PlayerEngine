package com.player2.playerengine.agentic;

import java.util.List;

public record AgenticWorldSnapshot(
        String dimension,
        String position,
        int nearbyDropCount,
        List<String> sampleDropItems,
        int freeInventorySlots,
        int nearbyValidChestCount,
        String nearestChestHint,
        boolean hasChestItemInInventory,
        boolean craftMacroSupportsChest,
        boolean quickPlacementPossible,
        boolean hasDepositableItems,
        boolean signItemInInventory,
        boolean storageTargetResolvable
) {}
