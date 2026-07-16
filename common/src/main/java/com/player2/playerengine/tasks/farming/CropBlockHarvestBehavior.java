package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;

/** Namespace-neutral vanilla-compatible behavior for every {@link CropBlock} subclass. */
public final class CropBlockHarvestBehavior implements HarvestBehavior {
    @FunctionalInterface
    interface CloneStackSource {
        ItemStack resolve(LevelReader level, BlockPos pos, BlockState state);
    }

    @FunctionalInterface
    interface ItemIdSource {
        ResourceLocation key(net.minecraft.world.item.Item item);
    }

    @Override
    public boolean recognizes(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (!(state.getBlock() instanceof CropBlock crop)) {
            return false;
        }
        try {
            return crop.getStateForAge(crop.getMaxAge()).getBlock() instanceof CropBlock;
        } catch (RuntimeException invalidCropContract) {
            return false;
        }
    }

    @Override
    public boolean isMature(BlockState state) {
        Objects.requireNonNull(state, "state");
        return recognizes(state)
                && state.getBlock() instanceof CropBlock crop
                && crop.isMaxAge(state);
    }

    @Override
    public HarvestAction action() {
        return HarvestAction.BREAK;
    }

    @Override
    public Optional<ResourceLocation> canonicalCropId(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (!recognizes(state)) {
            return Optional.empty();
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        ResourceLocation airId = BuiltInRegistries.BLOCK.getKey(
                net.minecraft.world.level.block.Blocks.AIR);
        return id == null || id.equals(airId) ? Optional.empty() : Optional.of(id);
    }

    @Override
    public Optional<ResourceLocation> plantingItemId(
            LevelReader level,
            BlockPos pos,
            BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        if (!recognizes(state) || !(state.getBlock() instanceof CropBlock crop)) {
            return Optional.empty();
        }
        return plantingItemIdFromClone(level, pos, state, crop::getCloneItemStack);
    }

    static Optional<ResourceLocation> plantingItemIdFromClone(
            LevelReader level,
            BlockPos pos,
            BlockState state,
            CloneStackSource cloneSource) {
        return plantingItemIdFromClone(
                level, pos, state, cloneSource, BuiltInRegistries.ITEM::getKey);
    }

    static Optional<ResourceLocation> plantingItemIdFromClone(
            LevelReader level,
            BlockPos pos,
            BlockState state,
            CloneStackSource cloneSource,
            ItemIdSource itemIdSource) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(cloneSource, "cloneSource");
        Objects.requireNonNull(itemIdSource, "itemIdSource");
        try {
            ItemStack clone = cloneSource.resolve(level, pos, state);
            if (clone == null || clone.isEmpty() || clone.is(Items.AIR)) {
                return Optional.empty();
            }
            ResourceLocation id = itemIdSource.key(clone.getItem());
            ResourceLocation airId = BuiltInRegistries.ITEM.getKey(Items.AIR);
            if (id == null || id.equals(airId)) {
                return Optional.empty();
            }
            return Optional.of(id);
        } catch (RuntimeException ignored) {
            // Clone metadata is optional and cannot revoke support for an otherwise valid crop.
            return Optional.empty();
        }
    }
}
