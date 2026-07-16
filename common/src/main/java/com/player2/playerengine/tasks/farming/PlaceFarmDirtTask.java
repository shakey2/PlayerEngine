package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

/** Places exactly one dirt block from a frozen stance through normal right-click interaction. */
public final class PlaceFarmDirtTask extends Task implements FarmMutationReceipt {
    private static final int RETRY_DELAY_TICKS = 10;

    enum ReceiptDisposition {
        PENDING,
        SUCCESS,
        INVALID
    }

    private final BlockPos target;
    private final BlockPos support;
    private final BlockPos stance;
    private final boolean allowProtectedTarget;

    private String dimension;
    private BlockState expectedTarget;
    private BlockState expectedSupport;
    private BlockState expectedTargetHead;
    private BlockState expectedStance;
    private BlockState expectedStanceHead;
    private boolean expectedStancePassableCrop;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private int noProgressTicks;
    private int attempts;
    private int ticksSinceAttempt;
    private int dirtBefore;
    private boolean inventoryBaselineCaptured;
    private boolean transientReceiptDetached;

    public PlaceFarmDirtTask(BlockPos target, BlockPos stance) {
        this(target, target == null ? null : target.below(), stance);
    }

    public PlaceFarmDirtTask(BlockPos target, BlockPos support, BlockPos stance) {
        this(target, support, stance, false);
    }

    private PlaceFarmDirtTask(
            BlockPos target,
            BlockPos support,
            BlockPos stance,
            boolean allowProtectedTarget) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.support = Objects.requireNonNull(support, "support").immutable();
        this.stance = Objects.requireNonNull(stance, "stance").immutable();
        this.allowProtectedTarget = allowProtectedTarget;
        if (!this.support.equals(this.target.below())) {
            throw new IllegalArgumentException("farm dirt support must be directly below target");
        }
    }

    /** Registered-farm-only repair placement; all support, headroom, and stance guards remain. */
    static PlaceFarmDirtTask forFarmRepair(BlockPos target, BlockPos stance) {
        return new PlaceFarmDirtTask(target, target.below(), stance, true);
    }

    @Override
    protected void onStart() {
        dimension = currentDimension();
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
        if (!loaded(target) || !loaded(target.above()) || !loaded(support)
                || !loaded(stance) || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return;
        }
        expectedTarget = controller.getWorld().getBlockState(target);
        expectedSupport = controller.getWorld().getBlockState(support);
        expectedTargetHead = controller.getWorld().getBlockState(target.above());
        expectedStance = controller.getWorld().getBlockState(stance);
        expectedStanceHead = controller.getWorld().getBlockState(stance.above());
        expectedStancePassableCrop = FarmStanceNavigation.isPassableCropStance(
                controller, stance);
        if (!validPrecondition()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return;
        }
        if (countDirt() < 1) {
            fail(FarmTaskReason.DIRT_UNAVAILABLE);
        }
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
        if (!loaded(target) || !loaded(target.above()) || !loaded(support)
                || !loaded(stance) || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }

        noProgressTicks++;
        ticksSinceAttempt++;
        if (noProgressTicks >= FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }

        BlockState observedTarget = controller.getWorld().getBlockState(target);
        int dirtAfter = countDirt();
        if (inventoryBaselineCaptured) {
            if (placementCompleted(expectedTarget, observedTarget, dirtBefore, dirtAfter)) {
                succeed();
                return null;
            }
            if (!observedTarget.equals(expectedTarget) || dirtAfter != dirtBefore) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
        }

        if (!validPrecondition()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (!FarmStanceNavigation.isAtFrozenStance(controller, stance)) {
            setDebugState("Moving to fixed dirt-placement stance");
            return new GetToBlockTask(stance);
        }
        if (attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (ticksSinceAttempt < RETRY_DELAY_TICKS) {
            return null;
        }
        if (!controller.getSlotHandler().forceEquipItem(Items.DIRT)
                || !controller.getPlayer().getMainHandItem().is(Items.DIRT)) {
            fail(FarmTaskReason.DIRT_UNAVAILABLE);
            return null;
        }

        Optional<Rotation> reach = LookHelper.getReach(controller, support, Direction.UP);
        if (reach.isEmpty()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!LookHelper.isLookingAt(controller, reach.get())) {
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        HitResult mouseOver = controller.getBaritone().getEntityContext().objectMouseOver();
        Optional<InteractionResult> interaction = dispatchPlacementAttempt(
                mouseOver,
                this::countDirt,
                hit -> FarmInteractionDispatcher.useMainHandBlock(controller, hit));
        if (interaction.isEmpty()) {
            // The approximate rotation gate can be true while the live ray has drifted to a
            // neighboring block or side face. Re-aim without spending a mutation attempt.
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        // Dispatch synchronously while the dirt hand proven above is still selected. A deferred
        // input pulse runs after InventoryBehavior, which may legally move a pickaxe back into the
        // selected hotbar slot and turn this placement into a no-op. The exact world/inventory
        // receipt below remains authoritative even when an interaction result is non-consuming.
        if (interaction.get().consumesAction()) {
            controller.getPlayer().swing(InteractionHand.MAIN_HAND);
        }
        setDebugState("Placing and verifying farm dirt (attempt " + attempts + "/"
                + FarmPlotPolicy.MUTATION_MAX_ATTEMPTS + ")");
        return null;
    }

    static boolean placementCompleted(
            BlockState expectedTarget,
            BlockState observedTarget,
            int dirtBefore,
            int dirtAfter) {
        return expectedTarget != null
                && expectedTarget.isAir()
                && observedTarget != null
                && observedTarget.is(Blocks.DIRT)
                && dirtBefore >= 1
                && dirtAfter == dirtBefore - 1;
    }

    /** Package-visible pure receipt classifier used by the farming self-test. */
    static ReceiptDisposition receiptDisposition(
            int issuedAttempts,
            boolean baselineCaptured,
            BlockState expectedTarget,
            BlockState observedTarget,
            int dirtBefore,
            int dirtAfter) {
        if (issuedAttempts <= 0) {
            return ReceiptDisposition.PENDING;
        }
        if (!baselineCaptured || expectedTarget == null || observedTarget == null) {
            return ReceiptDisposition.INVALID;
        }
        if (placementCompleted(expectedTarget, observedTarget, dirtBefore, dirtAfter)) {
            return ReceiptDisposition.SUCCESS;
        }
        return observedTarget.equals(expectedTarget) && dirtAfter == dirtBefore
                ? ReceiptDisposition.PENDING
                : ReceiptDisposition.INVALID;
    }

    /** Package-visible exact input-target gate used by the farming regression suite. */
    static boolean isExpectedSupportTopHit(HitResult hit, BlockPos expectedSupport) {
        return FarmInteractionDispatcher.isExpectedBlockFace(
                hit, expectedSupport, Direction.UP);
    }

    /**
     * Owns the complete receipt boundary: validate the exact live ray, capture inventory, spend
     * one attempt, and dispatch the interaction synchronously before any later tick behavior can
     * replace the equipped dirt. Package-visible for a deterministic scheduling regression.
     */
    Optional<InteractionResult> dispatchPlacementAttempt(
            HitResult hit,
            IntSupplier dirtCounter,
            Function<BlockHitResult, InteractionResult> interaction) {
        return FarmInteractionDispatcher.dispatchExactFace(
                hit,
                support,
                Direction.UP,
                () -> {
                    if (!inventoryBaselineCaptured) {
                        dirtBefore = Objects.requireNonNull(
                                dirtCounter, "dirtCounter").getAsInt();
                        inventoryBaselineCaptured = true;
                    }
                    attempts++;
                    ticksSinceAttempt = 0;
                },
                interaction);
    }

    boolean inventoryBaselineCaptured() {
        return inventoryBaselineCaptured;
    }

    /**
     * Settles an issued dirt-placement receipt from live world and inventory state before this child
     * is discarded for a transient root resume.
     */
    @Override
    public boolean settlePendingTransition() {
        if (terminal) {
            return successful;
        }
        if (controller == null || expectedTarget == null || attempts <= 0) {
            return true;
        }
        if (!Objects.equals(dimension, currentDimension())) {
            fail(FarmTaskReason.DIMENSION_CHANGED);
            return false;
        }
        if (!loaded(target)) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return false;
        }
        ReceiptDisposition disposition = receiptDisposition(
                attempts,
                inventoryBaselineCaptured,
                expectedTarget,
                controller.getWorld().getBlockState(target),
                dirtBefore,
                countDirt());
        if (disposition == ReceiptDisposition.PENDING) {
            return true;
        }
        if (disposition == ReceiptDisposition.SUCCESS) {
            succeed();
            return true;
        }
        fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
        return false;
    }

    @Override
    public boolean hasIssuedMutation() {
        return attempts > 0;
    }

    @Override
    public void prepareForTransientDetach() {
        transientReceiptDetached = true;
    }

    private boolean validPrecondition() {
        Level level = controller.getWorld();
        BlockState observedStance = level.getBlockState(stance);
        boolean observedStancePassableCrop = FarmStanceNavigation.isPassableCropStance(
                controller, stance);
        return FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)
                && level.getBlockState(target).equals(expectedTarget)
                && expectedTarget.isAir()
                && level.getBlockState(target.above()).equals(expectedTargetHead)
                && expectedTargetHead.isAir()
                && level.getBlockState(support).equals(expectedSupport)
                && expectedSupport.isFaceSturdy(level, support, Direction.UP)
                && !(expectedSupport.getBlock() instanceof LiquidBlockContainer)
                && stanceStateStillValid(
                expectedStance,
                observedStance,
                expectedStancePassableCrop,
                observedStancePassableCrop)
                && level.getBlockState(stance.above()).equals(expectedStanceHead)
                && observedStance.getCollisionShape(level, stance).isEmpty()
                && expectedStanceHead.getCollisionShape(level, stance.above()).isEmpty()
                && (allowProtectedTarget || !isProtected(target))
                && !isProtected(target.above())
                && !isProtected(support)
                && !FarmStanceNavigation.stanceProtectionDenied(
                isProtected(stance.below()),
                isProtected(stance),
                observedStancePassableCrop,
                isProtected(stance.above()));
    }

    /** Same recognized crop may age while pathing; ordinary stance cells remain exact-state. */
    static boolean stanceStateStillValid(
            BlockState expected,
            BlockState observed,
            boolean expectedPassableCrop,
            boolean observedPassableCrop) {
        return expected != null
                && observed != null
                && (expected.equals(observed)
                || (expectedPassableCrop
                && observedPassableCrop
                && expected.getBlock() == observed.getBlock()));
    }

    private boolean loaded(BlockPos position) {
        return controller.getChunkTracker().isChunkLoaded(position);
    }

    private boolean isProtected(BlockPos position) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store == null || store.contains(dimension, position);
    }

    private int countDirt() {
        return controller.getItemStorage().getItemCount(Items.DIRT);
    }

    private String currentDimension() {
        return controller.getWorld().dimension().location().toString();
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
        if (controller != null) {
            controller.getInputControls().release(Input.CLICK_RIGHT);
            controller.getBaritone().getPathingBehavior().forceCancel();
            if (controller.getBaritone().getCustomGoalProcess().isActive()) {
                controller.getBaritone().getCustomGoalProcess().onLostControl();
            }
        }
        if (!terminal && interruptTask == null && !transientReceiptDetached) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
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

    public BlockPos target() {
        return target;
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
        return other instanceof PlaceFarmDirtTask task
                && task.target.equals(target)
                && task.support.equals(support)
                && task.stance.equals(stance)
                && task.allowProtectedTarget == allowProtectedTarget;
    }

    @Override
    protected String toDebugString() {
        return "Place verified farm dirt at " + target.toShortString();
    }
}
