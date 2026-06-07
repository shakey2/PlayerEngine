package com.player2.playerengine.agentic;

import net.minecraft.core.BlockPos;

/** Typed storage chest target for later C3 deposit (in-memory per agentic run). */
public record AgenticStorageTarget(
        BlockPos pos,
        String dimension,
        String blockId,
        StorageTargetSource source,
        boolean placedByBot,
        long resolvedAtGameTime
) {}
