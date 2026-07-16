package com.player2.playerengine.tasks.farming;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Resolves public planting-item identity into one supported canonical farm crop family. */
public final class FarmPlantingItemResolver {
    public enum Status {
        SUPPORTED,
        INVALID_ITEM,
        NOT_BLOCK_ITEM,
        EXCLUDED_STEM,
        UNSUPPORTED_FAMILY,
        UNSUPPORTED_CROP_CONTRACT
    }

    public record Descriptor(
            Item item,
            ResourceLocation plantingItemId,
            Block placementBlock,
            ResourceLocation canonicalCropId,
            HarvestBehavior behavior) {
        public Descriptor {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(plantingItemId, "plantingItemId");
            Objects.requireNonNull(placementBlock, "placementBlock");
            Objects.requireNonNull(canonicalCropId, "canonicalCropId");
            Objects.requireNonNull(behavior, "behavior");
        }
    }

    public record Result(Status status, Descriptor descriptor) {
        public Result {
            Objects.requireNonNull(status, "status");
            if ((status == Status.SUPPORTED) != (descriptor != null)) {
                throw new IllegalArgumentException("supported status and descriptor disagree");
            }
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    private FarmPlantingItemResolver() {
    }

    public static Result resolve(Item requestedItem) {
        if (requestedItem == null || requestedItem == Items.AIR) {
            return rejected(Status.INVALID_ITEM);
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(requestedItem);
        ResourceLocation airItemId = BuiltInRegistries.ITEM.getKey(Items.AIR);
        if (itemId == null || itemId.equals(airItemId)) {
            return rejected(Status.INVALID_ITEM);
        }
        if (!(requestedItem instanceof BlockItem blockItem)) {
            return rejected(Status.NOT_BLOCK_ITEM);
        }

        Block placementBlock = blockItem.getBlock();
        if (placementBlock == null || placementBlock == Blocks.AIR) {
            return rejected(Status.UNSUPPORTED_FAMILY);
        }
        if (placementBlock instanceof StemBlock || placementBlock instanceof AttachedStemBlock) {
            return rejected(Status.EXCLUDED_STEM);
        }

        BlockState placementState = placementBlock.defaultBlockState();
        HarvestBehavior behavior;
        if (placementBlock == Blocks.TORCHFLOWER_CROP) {
            if (requestedItem != Items.TORCHFLOWER_SEEDS) {
                return rejected(Status.UNSUPPORTED_CROP_CONTRACT);
            }
            behavior = HarvestBehaviorRegistry.DEFAULT.resolve(placementState).orElse(null);
        } else if (placementBlock == Blocks.PITCHER_CROP) {
            if (requestedItem != Items.PITCHER_POD) {
                return rejected(Status.UNSUPPORTED_CROP_CONTRACT);
            }
            behavior = HarvestBehaviorRegistry.DEFAULT.resolve(placementState).orElse(null);
        } else if (placementBlock instanceof CropBlock) {
            behavior = HarvestBehaviorRegistry.DEFAULT.resolve(placementState).orElse(null);
        } else {
            return rejected(Status.UNSUPPORTED_FAMILY);
        }

        if (behavior == null) {
            return rejected(Status.UNSUPPORTED_CROP_CONTRACT);
        }
        ResourceLocation canonicalId = behavior.canonicalCropId(placementState).orElse(null);
        if (canonicalId == null) {
            return rejected(Status.UNSUPPORTED_CROP_CONTRACT);
        }
        return new Result(Status.SUPPORTED, new Descriptor(
                requestedItem, itemId, placementBlock, canonicalId, behavior));
    }

    private static Result rejected(Status status) {
        return new Result(status, null);
    }
}
