package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;

/** Places the farm center source through normal bucket use and verifies exact world/material deltas. */
public final class PlaceFarmWaterTask extends Task implements FarmMutationReceipt {
    private static final int RETRY_DELAY_TICKS = 10;

    enum ReceiptDisposition {
        PENDING,
        SUCCESS,
        INVALID
    }

    private final BlockPos center;
    private final BlockPos support;
    private final BlockPos stance;

    private String dimension;
    private BlockState expectedCenter;
    private BlockState expectedSupport;
    private boolean behaviourPushed;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private int noProgressTicks;
    private int attempts;
    private int ticksSinceAttempt;
    private int emptyBucketsBefore;
    private int waterBucketsBefore;
    private boolean inventoryBaselineCaptured;
    private boolean transientReceiptDetached;

    public PlaceFarmWaterTask(BlockPos center, BlockPos stance) {
        this(center, center == null ? null : center.below(), stance);
    }

    public PlaceFarmWaterTask(BlockPos center, BlockPos support, BlockPos stance) {
        this.center = Objects.requireNonNull(center, "center").immutable();
        this.support = Objects.requireNonNull(support, "support").immutable();
        this.stance = Objects.requireNonNull(stance, "stance").immutable();
        if (!this.support.equals(this.center.below())) {
            throw new IllegalArgumentException("farm water support must be directly below center");
        }
    }

    @Override
    protected void onStart() {
        dimension = currentDimension();
        behaviourPushed = false;
        terminal = false;
        successful = false;
        reason = FarmTaskReason.NONE;
        noProgressTicks = 0;
        attempts = 0;
        ticksSinceAttempt = RETRY_DELAY_TICKS;
        inventoryBaselineCaptured = false;
        transientReceiptDetached = false;

        if (PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return;
        }
        if (!loaded(center) || !loaded(support) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return;
        }
        expectedCenter = this.controller.getWorld().getBlockState(center);
        expectedSupport = this.controller.getWorld().getBlockState(support);
        if (!isValidPrecondition()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return;
        }
        if (count(Items.WATER_BUCKET) < 1) {
            fail(FarmTaskReason.BUCKET_UNAVAILABLE);
            return;
        }
        this.controller.getBehaviour().push();
        behaviourPushed = true;
        this.controller.getBehaviour().setRayTracingFluidHandling(
                net.minecraft.world.level.ClipContext.Fluid.NONE);
    }

    @Override
    protected Task onTick() {
        if (terminal) {
            return null;
        }
        if (!Objects.equals(dimension, currentDimension())) {
            fail(FarmTaskReason.DIMENSION_CHANGED);
            return null;
        }
        if (!loaded(center) || !loaded(support) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }

        noProgressTicks++;
        ticksSinceAttempt++;
        if (noProgressTicks > FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }

        BlockState observedCenter = this.controller.getWorld().getBlockState(center);
        if (inventoryBaselineCaptured) {
            int emptyAfter = count(Items.BUCKET);
            int waterAfter = count(Items.WATER_BUCKET);
            if (FarmInteractionPostconditions.waterPlacement(
                    observedCenter,
                    emptyBucketsBefore,
                    emptyAfter,
                    waterBucketsBefore,
                    waterAfter)) {
                if (this.controller.getWorld().getBiome(center).value()
                        .shouldFreeze(this.controller.getWorld(), center)) {
                    fail(FarmTaskReason.FREEZE_RISK);
                    return null;
                }
                succeed();
                return null;
            }
            if (!observedCenter.equals(expectedCenter)
                    || emptyAfter != emptyBucketsBefore
                    || waterAfter != waterBucketsBefore) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
        }

        if (!isValidPrecondition()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (!FarmStanceNavigation.isAtFrozenStance(this.controller, stance)) {
            this.setDebugState("Moving to fixed farm-water stance");
            return new GetToBlockTask(stance);
        }
        if (attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (ticksSinceAttempt < RETRY_DELAY_TICKS) {
            return null;
        }
        if (!this.controller.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)
                || !this.controller.getPlayer().getMainHandItem().is(Items.WATER_BUCKET)) {
            fail(FarmTaskReason.BUCKET_UNAVAILABLE);
            return null;
        }

        Optional<Rotation> reach = LookHelper.getReach(this.controller, support, Direction.UP);
        if (reach.isEmpty()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!LookHelper.isLookingAt(this.controller, reach.get())) {
            LookHelper.lookAt(this.controller, reach.get());
            return null;
        }
        HitResult mouseOver = this.controller.getBaritone().getEntityContext().objectMouseOver();
        if (!isExpectedSupportTopHit(mouseOver, support)) {
            // Never spend an attempt on a generic right click whose live ray target drifted.
            LookHelper.lookAt(this.controller, reach.get());
            return null;
        }

        Optional<InteractionResult> interaction =
                FarmInteractionDispatcher.dispatchMainHandBlockThenItemAtFace(
                controller,
                mouseOver,
                support,
                Direction.UP,
                () -> {
                    if (!inventoryBaselineCaptured) {
                        emptyBucketsBefore = count(Items.BUCKET);
                        waterBucketsBefore = count(Items.WATER_BUCKET);
                        inventoryBaselineCaptured = true;
                    }
                    attempts++;
                    ticksSinceAttempt = 0;
                });
        if (interaction.isEmpty()) {
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        this.setDebugState("Placing and verifying farm water");
        return null;
    }

    static boolean isExpectedSupportTopHit(HitResult hit, BlockPos expectedSupport) {
        return FarmInteractionDispatcher.isExpectedBlockFace(
                hit, expectedSupport, Direction.UP);
    }

    /** Package-visible pure receipt classifier used by the farming self-test. */
    static ReceiptDisposition receiptDisposition(
            int issuedAttempts,
            boolean baselineCaptured,
            BlockState expectedCenter,
            BlockState observedCenter,
            int emptyBucketsBefore,
            int emptyBucketsAfter,
            int waterBucketsBefore,
            int waterBucketsAfter) {
        if (issuedAttempts <= 0) {
            return ReceiptDisposition.PENDING;
        }
        if (!baselineCaptured || expectedCenter == null || observedCenter == null) {
            return ReceiptDisposition.INVALID;
        }
        if (FarmInteractionPostconditions.waterPlacement(
                observedCenter,
                emptyBucketsBefore,
                emptyBucketsAfter,
                waterBucketsBefore,
                waterBucketsAfter)) {
            return ReceiptDisposition.SUCCESS;
        }
        return observedCenter.equals(expectedCenter)
                && emptyBucketsAfter == emptyBucketsBefore
                && waterBucketsAfter == waterBucketsBefore
                ? ReceiptDisposition.PENDING
                : ReceiptDisposition.INVALID;
    }

    /**
     * Settles an issued bucket-use receipt from live world and inventory state before this child is
     * discarded for a transient root resume.
     */
    @Override
    public boolean settlePendingTransition() {
        if (terminal) {
            return successful;
        }
        if (controller == null || expectedCenter == null || attempts <= 0) {
            return true;
        }
        if (!Objects.equals(dimension, currentDimension())) {
            fail(FarmTaskReason.DIMENSION_CHANGED);
            return false;
        }
        if (!loaded(center)) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return false;
        }
        ReceiptDisposition disposition = receiptDisposition(
                attempts,
                inventoryBaselineCaptured,
                expectedCenter,
                controller.getWorld().getBlockState(center),
                emptyBucketsBefore,
                count(Items.BUCKET),
                waterBucketsBefore,
                count(Items.WATER_BUCKET));
        if (disposition == ReceiptDisposition.PENDING) {
            return true;
        }
        if (disposition == ReceiptDisposition.INVALID) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        if (controller.getWorld().getBiome(center).value()
                .shouldFreeze(controller.getWorld(), center)) {
            fail(FarmTaskReason.FREEZE_RISK);
            return false;
        }
        succeed();
        return true;
    }

    @Override
    public boolean hasIssuedMutation() {
        return attempts > 0;
    }

    @Override
    public void prepareForTransientDetach() {
        transientReceiptDetached = true;
    }

    private boolean isValidPrecondition() {
        Level level = this.controller.getWorld();
        return loaded(center)
                && loaded(support)
                && loaded(stance)
                && loaded(stance.below())
                && loaded(stance.above())
                && FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)
                && level.getBlockState(center).equals(expectedCenter)
                && expectedCenter.isAir()
                && level.getBlockState(support).equals(expectedSupport)
                && expectedSupport.isFaceSturdy(level, support, Direction.UP)
                && !(expectedSupport.getBlock() instanceof LiquidBlockContainer)
                && !isProtected(center)
                && !isProtected(support)
                && !FarmStanceNavigation.stanceProtectionDenied(
                isProtected(stance.below()),
                isProtected(stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                isProtected(stance.above()));
    }

    private boolean loaded(BlockPos pos) {
        return this.controller.getChunkTracker().isChunkLoaded(pos);
    }

    private boolean isProtected(BlockPos pos) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store == null || store.contains(dimension, pos);
    }

    private int count(net.minecraft.world.item.Item item) {
        return this.controller.getItemStorage().getItemCount(item);
    }

    private String currentDimension() {
        return this.controller.getWorld().dimension().location().toString();
    }

    private void succeed() {
        terminal = true;
        successful = true;
        reason = FarmTaskReason.NONE;
    }

    private void fail(FarmTaskReason failure) {
        terminal = true;
        successful = false;
        reason = Objects.requireNonNull(failure, "failure");
    }

    @Override
    protected void onStop(Task interruptTask) {
        releaseControls();
        if (!terminal && interruptTask == null && !transientReceiptDetached) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
    }

    private void releaseControls() {
        if (controller == null) {
            return;
        }
        controller.getInputControls().release(Input.CLICK_RIGHT);
        controller.getBaritone().getPathingBehavior().forceCancel();
        if (controller.getBaritone().getCustomGoalProcess().isActive()) {
            controller.getBaritone().getCustomGoalProcess().onLostControl();
        }
        if (behaviourPushed) {
            controller.getBehaviour().pop();
            behaviourPushed = false;
        }
    }

    @Override
    public boolean isFinished() {
        return terminal;
    }

    public boolean isSuccessful() {
        return terminal && successful;
    }

    public FarmTaskReason reason() {
        return reason;
    }

    public String controlledReason() {
        return reason.controlledReason();
    }

    public BlockPos center() {
        return center;
    }

    public BlockPos support() {
        return support;
    }

    public BlockPos stance() {
        return stance;
    }

    public int attempts() {
        return attempts;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceFarmWaterTask task
                && task.center.equals(center)
                && task.support.equals(support)
                && task.stance.equals(stance);
    }

    @Override
    protected String toDebugString() {
        return "Place verified farm water at " + center.toShortString();
    }
}
