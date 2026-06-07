package com.player2.playerengine.tasks.crafting;

import net.minecraft.core.BlockPos;

public record CraftingTableTarget(BlockPos pos, boolean placedByThisTask) {
}
