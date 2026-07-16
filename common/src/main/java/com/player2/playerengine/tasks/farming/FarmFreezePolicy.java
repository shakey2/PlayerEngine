package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/** Pure model of vanilla's edge-sensitive water-freezing preconditions for hypothetical water. */
public final class FarmFreezePolicy {
    private static final int MAX_FREEZE_BLOCK_LIGHT = 9;

    private FarmFreezePolicy() {
    }

    /**
     * Returns true when a source placed at the candidate center could immediately freeze under
     * vanilla's temperature, build-height, block-light, and exposed-edge checks.
     */
    public static boolean wouldFreeze(FarmSiteSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        BlockPos center = snapshot.center();
        FarmSiteWorldView.CellObservation centerFacts = snapshot.observation(center);
        if (centerFacts == null || !centerFacts.isReadable()) {
            return true;
        }
        if (centerFacts.warmEnoughToRain()
                || centerFacts.blockLight() > MAX_FREEZE_BLOCK_LIGHT) {
            return false;
        }

        boolean surroundedByWater = true;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            FarmSiteWorldView.CellObservation neighbor = snapshot.observation(center.relative(direction));
            if (neighbor == null
                    || (neighbor.fluidKind() != FarmSiteWorldView.FluidKind.STANDALONE_WATER_SOURCE
                    && neighbor.fluidKind() != FarmSiteWorldView.FluidKind.OTHER_WATER_SOURCE
                    && neighbor.fluidKind() != FarmSiteWorldView.FluidKind.FLOWING_WATER)) {
                surroundedByWater = false;
                break;
            }
        }
        return !surroundedByWater;
    }
}
