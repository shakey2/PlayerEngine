package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread state seam shared by scan-time discovery and the immediate action gate. */
@FunctionalInterface
public interface FarmActionStateSource {
    FarmActionStateSource LIVE = LevelReader::getBlockState;

    BlockState read(LevelReader level, BlockPos pos);
}
