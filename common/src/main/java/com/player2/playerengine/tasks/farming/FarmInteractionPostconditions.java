package com.player2.playerengine.tasks.farming;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.function.Predicate;

/** Pure completion gates for physical farm interactions. */
public final class FarmInteractionPostconditions {
    private FarmInteractionPostconditions() {
    }

    public static boolean isStandaloneWaterSource(BlockState state) {
        return state != null
                && state.is(Blocks.WATER)
                && state.getFluidState().isSource()
                && state.getBlock() instanceof BucketPickup;
    }

    /** A valid pickup consumes one empty bucket and creates one water bucket. */
    public static boolean waterPickupInventoryDelta(
            int emptyBucketsBefore,
            int emptyBucketsAfter,
            int waterBucketsBefore,
            int waterBucketsAfter) {
        return emptyBucketsBefore >= 1
                && emptyBucketsAfter == emptyBucketsBefore - 1
                && waterBucketsAfter == waterBucketsBefore + 1;
    }

    /** A valid placement creates the standalone source and swaps one filled bucket to empty. */
    public static boolean waterPlacement(
            BlockState centerAfter,
            int emptyBucketsBefore,
            int emptyBucketsAfter,
            int waterBucketsBefore,
            int waterBucketsAfter) {
        return isStandaloneWaterSource(centerAfter)
                && waterBucketsBefore >= 1
                && waterBucketsAfter == waterBucketsBefore - 1
                && emptyBucketsAfter == emptyBucketsBefore + 1;
    }

    public static boolean farmlandTransition(BlockState before, BlockState after) {
        return before != null
                && after != null
                && !before.is(Blocks.FARMLAND)
                && after.is(Blocks.FARMLAND);
    }

    public static boolean verifiedBreakTransition(
            BlockState expectedBefore,
            BlockState observedAfter,
            Predicate<BlockState> allowedAfter) {
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(observedAfter, "observedAfter");
        Objects.requireNonNull(allowedAfter, "allowedAfter");
        return !observedAfter.equals(expectedBefore) && allowedAfter.test(observedAfter);
    }

    /** Exactly one durability point was spent, including the final point that broke the item. */
    public static boolean exactlyOneHoeDamage(
            int beforeDamage,
            int maximumDamage,
            int afterDamage,
            boolean broke) {
        if (maximumDamage < 1 || beforeDamage < 0 || beforeDamage >= maximumDamage) {
            return false;
        }
        int remainingBefore = maximumDamage - beforeDamage;
        if (broke) {
            return remainingBefore == 1;
        }
        return afterDamage == beforeDamage + 1 && afterDamage < maximumDamage;
    }
}
