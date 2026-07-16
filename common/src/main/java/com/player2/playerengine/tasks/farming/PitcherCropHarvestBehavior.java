package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Stable behavior for both halves and every age of the vertically growing pitcher crop. */
public final class PitcherCropHarvestBehavior implements HarvestBehavior {
    private static final String BLOCK_ID = "minecraft:pitcher_crop";

    @Override
    public boolean recognizes(BlockState state) {
        Objects.requireNonNull(state, "state");
        return state.is(Blocks.PITCHER_CROP)
                && state.hasProperty(PitcherCropBlock.AGE)
                && state.hasProperty(DoublePlantBlock.HALF);
    }

    @Override
    public boolean isMature(BlockState state) {
        Objects.requireNonNull(state, "state");
        return recognizes(state)
                && state.getValue(PitcherCropBlock.AGE) >= PitcherCropBlock.MAX_AGE;
    }

    @Override
    public HarvestAction action() {
        return HarvestAction.BREAK;
    }

    @Override
    public Optional<ResourceLocation> canonicalCropId(BlockState state) {
        Objects.requireNonNull(state, "state");
        return recognizes(state)
                ? Optional.of(BuiltInRegistries.BLOCK.getKey(Blocks.PITCHER_CROP))
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
                ? Optional.of(BuiltInRegistries.ITEM.getKey(Items.PITCHER_POD))
                : Optional.empty();
    }

    @Override
    public boolean allowsLowCollisionStance(BlockState state) {
        return recognizes(state)
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    static boolean isLowerFingerprint(String fingerprint) {
        return hasHalfFingerprint(fingerprint, "lower");
    }

    static boolean isUpperFingerprint(String fingerprint) {
        return hasHalfFingerprint(fingerprint, "upper");
    }

    static OptionalInt ageFromFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.startsWith(BLOCK_ID + "|")) {
            return OptionalInt.empty();
        }
        int open = fingerprint.indexOf('[');
        int close = fingerprint.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return OptionalInt.empty();
        }
        String[] properties = fingerprint.substring(open + 1, close).split(",");
        for (String property : properties) {
            String stripped = property.strip();
            if (!stripped.startsWith("age=")) {
                continue;
            }
            try {
                return OptionalInt.of(Integer.parseInt(stripped.substring("age=".length())));
            } catch (NumberFormatException invalidAge) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    private static boolean hasHalfFingerprint(String fingerprint, String expectedHalf) {
        if (fingerprint == null || !fingerprint.startsWith(BLOCK_ID + "|")) {
            return false;
        }
        int open = fingerprint.indexOf('[');
        int close = fingerprint.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return false;
        }
        String[] properties = fingerprint.substring(open + 1, close).split(",");
        for (String property : properties) {
            if (("half=" + expectedHalf).equals(property.strip())) {
                return true;
            }
        }
        return false;
    }
}
