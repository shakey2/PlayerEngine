package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** Extensible, state-driven contract for one supported farm harvest family. */
public interface HarvestBehavior {
    enum HarvestAction {
        BREAK
    }

    boolean recognizes(BlockState state);

    boolean isMature(BlockState state);

    HarvestAction action();

    /**
     * Stable crop-family identity used by EllieGPS and planting receipts.
     *
     * <p>This may intentionally differ from the live block id. Torchflower, for example, becomes
     * a flower block at maturity but remains part of the {@code minecraft:torchflower_crop}
     * family. An empty result makes the state unsupported rather than silently falling back to a
     * transient live block id.</p>
     */
    Optional<ResourceLocation> canonicalCropId(BlockState state);

    Optional<ResourceLocation> plantingItemId(
            LevelReader level,
            BlockPos pos,
            BlockState state);

    /**
     * Whether this exact crop state has a known low, step-safe collision shape.
     *
     * <p>The default is deliberately false: arbitrary mod crops do not become traversable merely
     * because they are recognized. Runtime stance validation still checks the live shape, fluid,
     * block entity, and hardness.</p>
     */
    default boolean allowsLowCollisionStance(BlockState state) {
        return false;
    }
}
