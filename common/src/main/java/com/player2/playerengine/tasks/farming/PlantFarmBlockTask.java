package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.trackers.storage.SurvivalConsumptionLedger;
import com.player2.playerengine.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;

/** Plants one frozen open farm cell through a normal right-click and exact paired receipt. */
public final class PlantFarmBlockTask extends Task implements FarmMutationReceipt {
    private static final int RETRY_DELAY_TICKS = 10;

    private final BlockPos target;
    private final BlockPos stance;
    private final FarmPlantingItemResolver.Descriptor descriptor;

    private String dimension;
    private BlockState expectedTarget;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private boolean inventoryBaselineCaptured;
    private int itemCountBefore;
    private SurvivalConsumptionLedger.Snapshot survivalConsumptionBaseline;
    private int attempts;
    private int noProgressTicks;
    private int ticksSinceAttempt;
    private boolean transientReceiptDetached;

    public PlantFarmBlockTask(
            BlockPos target,
            BlockPos stance,
            FarmPlantingItemResolver.Descriptor descriptor) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.stance = Objects.requireNonNull(stance, "stance").immutable();
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    protected void onStart() {
        dimension = currentDimension();
        terminal = false;
        successful = false;
        reason = FarmTaskReason.NONE;
        inventoryBaselineCaptured = false;
        itemCountBefore = 0;
        survivalConsumptionBaseline = null;
        attempts = 0;
        noProgressTicks = 0;
        ticksSinceAttempt = RETRY_DELAY_TICKS;
        transientReceiptDetached = false;

        if (!loaded(target) || !loaded(target.below()) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return;
        }
        expectedTarget = controller.getWorld().getBlockState(target);
        if (!expectedTarget.isAir()
                || !controller.getWorld().getBlockState(target.below()).is(Blocks.FARMLAND)) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return;
        }
        if (!FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)) {
            fail(FarmTaskReason.INTERACTION_DENIED);
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
        if (!loaded(target) || !loaded(target.below()) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }

        FarmPlantingReceiptClassifier.Disposition receipt = receiptDisposition();
        if (receipt == FarmPlantingReceiptClassifier.Disposition.SUCCESS) {
            succeed();
            return null;
        }
        if (receipt == FarmPlantingReceiptClassifier.Disposition.INVALID) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }

        noProgressTicks++;
        ticksSinceAttempt++;
        if (noProgressTicks >= FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS
                || attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!validPrecondition()
                || !FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (!FarmStanceNavigation.isAtFrozenStance(controller, stance)) {
            setDebugState("Moving to fixed planting stance");
            return new GetToBlockTask(stance);
        }
        if (ticksSinceAttempt < RETRY_DELAY_TICKS) {
            return null;
        }
        Item item = descriptor.item();
        if (countRequestedItem() < 1
                || !controller.getSlotHandler().forceEquipItem(item)
                || !controller.getPlayer().getMainHandItem().is(item)) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }

        BlockPos support = target.below();
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
        Optional<InteractionResult> interaction =
                FarmInteractionDispatcher.dispatchMainHandBlockUseAtFace(
                controller,
                mouseOver,
                support,
                Direction.UP,
                () -> {
                    if (!inventoryBaselineCaptured) {
                        itemCountBefore = countRequestedItem();
                        survivalConsumptionBaseline = controller.getSurvivalConsumptionLedger()
                                .snapshot();
                        inventoryBaselineCaptured = true;
                    }
                    attempts++;
                    ticksSinceAttempt = 0;
                });
        if (interaction.isEmpty()) {
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        setDebugState("Planting and verifying crop (attempt " + attempts + "/"
                + FarmPlotPolicy.MUTATION_MAX_ATTEMPTS + ")");
        return null;
    }

    private FarmPlantingReceiptClassifier.Disposition receiptDisposition() {
        BlockState observed = controller.getWorld().getBlockState(target);
        String observedCanonical = HarvestBehaviorRegistry.DEFAULT.resolve(observed)
                .flatMap(behavior -> behavior.canonicalCropId(observed))
                .map(Object::toString)
                .orElse(null);
        int confirmedSurvivalConsumptions = inventoryBaselineCaptured
                ? controller.getSurvivalConsumptionLedger()
                .consumedSince(descriptor.item(), survivalConsumptionBaseline)
                .orElse(-1)
                : 0;
        return FarmPlantingReceiptClassifier.classify(
                attempts,
                inventoryBaselineCaptured,
                observed.equals(expectedTarget),
                controller.getWorld().getBlockState(target.below()).is(Blocks.FARMLAND),
                descriptor.canonicalCropId().toString(),
                observedCanonical,
                confirmedSurvivalConsumptions,
                itemCountBefore,
                countRequestedItem());
    }

    private boolean validPrecondition() {
        return controller.getWorld().getBlockState(target).equals(expectedTarget)
                && expectedTarget.isAir()
                && controller.getWorld().getBlockState(target.below()).is(Blocks.FARMLAND)
                && descriptor.placementBlock().defaultBlockState().canSurvive(
                controller.getWorld(), target);
    }

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
        if (!loaded(target) || !loaded(target.below())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return false;
        }
        FarmPlantingReceiptClassifier.Disposition receipt = receiptDisposition();
        if (receipt == FarmPlantingReceiptClassifier.Disposition.SUCCESS) {
            succeed();
            return true;
        }
        if (receipt == FarmPlantingReceiptClassifier.Disposition.INVALID) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return false;
        }
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

    @Override
    protected void onStop(Task interruptTask) {
        quiesce();
        if (!terminal && interruptTask == null && !transientReceiptDetached) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
    }

    private int countRequestedItem() {
        return controller.getItemStorage().getItemCountInventoryOnly(descriptor.item());
    }

    private boolean loaded(BlockPos position) {
        return controller.getChunkTracker().isChunkLoaded(position);
    }

    private String currentDimension() {
        return controller.getWorld().dimension().location().toString();
    }

    private void quiesce() {
        if (controller != null) {
            controller.getInputControls().release(Input.CLICK_RIGHT);
            controller.getBaritone().getPathingBehavior().forceCancel();
        }
    }

    private void succeed() {
        terminal = true;
        successful = true;
        reason = FarmTaskReason.NONE;
        controller.getEntity().swing(InteractionHand.MAIN_HAND);
    }

    private void fail(FarmTaskReason failure) {
        terminal = true;
        successful = false;
        reason = Objects.requireNonNull(failure, "failure");
    }

    @Override
    public boolean isFinished() {
        return terminal;
    }

    @Override
    public boolean isSuccessful() {
        return terminal && successful;
    }

    @Override
    public FarmTaskReason reason() {
        return reason;
    }

    public BlockPos target() {
        return target;
    }

    public BlockPos stance() {
        return stance;
    }

    public FarmPlantingItemResolver.Descriptor descriptor() {
        return descriptor;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlantFarmBlockTask task
                && task.target.equals(target)
                && task.stance.equals(stance)
                && task.descriptor.item() == descriptor.item();
    }

    @Override
    protected String toDebugString() {
        return "Plant verified crop " + descriptor.plantingItemId()
                + " at " + target.toShortString();
    }
}
