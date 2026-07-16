package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;

/** Stable behavior for the crop state that transforms into a mature torchflower block. */
public final class TorchflowerHarvestBehavior implements HarvestBehavior {
    @Override
    public boolean recognizes(BlockState state) {
        Objects.requireNonNull(state, "state");
        return state.is(Blocks.TORCHFLOWER_CROP) || state.is(Blocks.TORCHFLOWER);
    }

    @Override
    public boolean isMature(BlockState state) {
        Objects.requireNonNull(state, "state");
        return state.is(Blocks.TORCHFLOWER);
    }

    @Override
    public HarvestAction action() {
        return HarvestAction.BREAK;
    }

    @Override
    public Optional<ResourceLocation> canonicalCropId(BlockState state) {
        Objects.requireNonNull(state, "state");
        return recognizes(state)
                ? Optional.of(BuiltInRegistries.BLOCK.getKey(Blocks.TORCHFLOWER_CROP))
                : Optional.empty();
    }

    @Override
    public Optional<ResourceLocation> plantingItemId(
            LevelReader level,
            BlockPos pos,
            BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        return recognizes(state)
                ? Optional.of(BuiltInRegistries.ITEM.getKey(Items.TORCHFLOWER_SEEDS))
                : Optional.empty();
    }
}
