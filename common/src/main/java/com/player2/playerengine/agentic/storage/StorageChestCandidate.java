package com.player2.playerengine.agentic.storage;

import com.player2.playerengine.agentic.StorageTargetSource;
import java.util.List;
import net.minecraft.core.BlockPos;

public record StorageChestCandidate(
        BlockPos pos,
        String blockId,
        StorageTargetSource source,
        double score,
        List<String> warnings
) {}
