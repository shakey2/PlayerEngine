package com.player2.playerengine.agentic.storage;

import java.util.List;
import net.minecraft.core.BlockPos;

public record ChestPlacementCandidate(
        BlockPos pos,
        double score,
        List<String> warnings
) {}
