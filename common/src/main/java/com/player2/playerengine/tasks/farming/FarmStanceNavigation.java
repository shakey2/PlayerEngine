package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.PlayerEngineController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Keeps frozen farm-action arrival checks aligned with Baritone's logical feet coordinates. */
final class FarmStanceNavigation {
    private static final double MAX_STEP_SAFE_CROP_COLLISION_Y = 0.5D;

    private FarmStanceNavigation() {
    }

    static boolean isAtFrozenStance(
            PlayerEngineController controller, BlockPos frozenStance) {
        return controller != null
                && isAtFrozenStance(
                        controller.getBaritone().getEntityContext().feetPos(), frozenStance);
    }

    static boolean isAtFrozenStance(
            BlockPos logicalFeetPosition, BlockPos frozenStance) {
        return frozenStance != null && frozenStance.equals(logicalFeetPosition);
    }

    /**
     * A player-protection marker on the feet cell normally denies a frozen stance. The sole farm
     * traversal exception is an intact, registry-recognized crop whose live state is either
     * collision-empty or explicitly declares a low step-safe crop collision. Fluid, block entities,
     * unbreakable states, and arbitrary colliding mod crops remain denied. Standing through it does
     * not authorize mutating the crop; action targets and stance headroom keep their ordinary
     * protection gates, and setup/repair mutations also keep the stance-support gate.
     */
    static boolean isPassableCropStance(
            PlayerEngineController controller, BlockPos stance) {
        if (controller == null || stance == null
                || !controller.getChunkTracker().isChunkLoaded(stance)) {
            return false;
        }
        Level level = controller.getWorld();
        BlockState state = level.getBlockState(stance);
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT.resolve(state).orElse(null);
        if (behavior == null) {
            return false;
        }
        VoxelShape collision = state.getCollisionShape(level, stance);
        return cropStanceGeometryAllowed(
                true,
                behavior.allowsLowCollisionStance(state),
                collision.isEmpty(),
                collision.isEmpty() ? 0.0D : collision.max(Direction.Axis.Y),
                state.getFluidState().isEmpty(),
                state.hasBlockEntity(),
                state.getDestroySpeed(level, stance));
    }

    /** Relaxed stable support gate for non-mutating mature-harvest perimeter navigation. */
    static boolean hasSafeLiveGeometry(
            PlayerEngineController controller, BlockPos stance) {
        return hasSafeLiveGeometry(controller, stance, false);
    }

    /** Planner-matching support gate for setup, repair, tilling, and water placement. */
    static boolean hasPlannerSafeLiveGeometry(
            PlayerEngineController controller, BlockPos stance) {
        return hasSafeLiveGeometry(controller, stance, true);
    }

    private static boolean hasSafeLiveGeometry(
            PlayerEngineController controller,
            BlockPos stance,
            boolean requirePlannerSafeSupport) {
        if (controller == null || stance == null
                || !controller.getChunkTracker().isChunkLoaded(stance.below())
                || !controller.getChunkTracker().isChunkLoaded(stance)
                || !controller.getChunkTracker().isChunkLoaded(stance.above())) {
            return false;
        }
        Level level = controller.getWorld();
        BlockPos supportPos = stance.below();
        BlockState support = level.getBlockState(supportPos);
        boolean stableTop = support.isFaceSturdy(level, supportPos, Direction.UP);
        return supportGeometryAllowed(
                support,
                stableTop,
                support.getCollisionShape(level, supportPos).isEmpty(),
                support.getFluidState().isEmpty(),
                support.getDestroySpeed(level, supportPos),
                requirePlannerSafeSupport)
                && isOpenStanceCell(controller, stance, true)
                && isOpenStanceCell(controller, stance.above(), false);
    }

    /** Package-visible combined support seam for setup-clear and harvest-perimeter regressions. */
    static boolean supportGeometryAllowed(
            BlockState support,
            boolean sturdyTop,
            boolean collisionEmpty,
            boolean fluidEmpty,
            float destroySpeed,
            boolean requirePlannerSafeSupport) {
        if (support == null
                || support.hasBlockEntity()
                || support.getBlock() instanceof Fallable
                || collisionEmpty
                || !fluidEmpty
                || destroySpeed < 0.0F) {
            return false;
        }
        if (!requirePlannerSafeSupport) {
            return true;
        }
        boolean normalizableFarmSupport = support.is(Blocks.GRASS_BLOCK)
                || support.is(Blocks.DIRT)
                || support.is(Blocks.STONE)
                || support.is(Blocks.FARMLAND);
        return !(support.getBlock() instanceof LiquidBlockContainer)
                && (sturdyTop || normalizableFarmSupport);
    }

    private static boolean isOpenStanceCell(
            PlayerEngineController controller,
            BlockPos position,
            boolean allowLowCropCollision) {
        Level level = controller.getWorld();
        BlockState state = level.getBlockState(position);
        if (state.isAir()) {
            return true;
        }
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT.resolve(state).orElse(null);
        VoxelShape collision = state.getCollisionShape(level, position);
        if (behavior != null && allowLowCropCollision) {
            return cropStanceGeometryAllowed(
                    true,
                    behavior.allowsLowCollisionStance(state),
                    collision.isEmpty(),
                    collision.isEmpty() ? 0.0D : collision.max(Direction.Axis.Y),
                    state.getFluidState().isEmpty(),
                    state.hasBlockEntity(),
                    state.getDestroySpeed(level, position));
        }
        return (behavior != null
                || FarmSiteWorldView.isClearableGroundCover(state, state.getFluidState()))
                && collision.isEmpty()
                && state.getFluidState().isEmpty()
                && !state.hasBlockEntity()
                && state.getDestroySpeed(level, position) >= 0.0F;
    }

    /** Pure geometry gate proving the low-collision exception cannot admit arbitrary hazards. */
    static boolean cropStanceGeometryAllowed(
            boolean recognizedCrop,
            boolean behaviorAllowsLowCollision,
            boolean collisionEmpty,
            double collisionMaxY,
            boolean fluidEmpty,
            boolean blockEntity,
            float destroySpeed) {
        if (!recognizedCrop || !fluidEmpty || blockEntity || destroySpeed < 0.0F) {
            return false;
        }
        if (collisionEmpty) {
            return true;
        }
        return behaviorAllowsLowCollision
                && Double.isFinite(collisionMaxY)
                && collisionMaxY >= 0.0D
                && collisionMaxY <= MAX_STEP_SAFE_CROP_COLLISION_Y;
    }

    /** Pure protection seam shared by the planner/root and every farm mutation child. */
    static boolean stanceProtectionDenied(
            boolean supportProtected,
            boolean feetProtected,
            boolean feetIsPassableCrop,
            boolean headProtected) {
        return supportProtected || (feetProtected && !feetIsPassableCrop) || headProtected;
    }
}
