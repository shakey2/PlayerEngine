package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.util.helpers.LookHelper;
import com.player2.playerengine.util.slots.PlayerSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Tills one frozen farm cell through normal item use, then applies exactly one NPC-owned durability
 * attempt only after observing a fresh farmland transition.
 */
public final class TillFarmBlockTask extends Task implements FarmMutationReceipt {
    private static final int RETRY_DELAY_TICKS = 10;

    private final BlockPos target;
    private final BlockPos stance;
    private final Item requestedHoeItem;

    private String dimension;
    private BlockState expectedBefore;
    private ItemStack selectedHoe;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private boolean alreadyFarmland;
    private boolean freshTill;
    private boolean durabilityApplied;
    private int noProgressTicks;
    private int attempts;
    private int ticksSinceAttempt;
    private int beforeDamage;
    private int maximumDamage;
    private boolean transientReceiptDetached;

    public TillFarmBlockTask(BlockPos target, BlockPos stance) {
        this(target, stance, null);
    }

    public TillFarmBlockTask(BlockPos target, BlockPos stance, Item requestedHoeItem) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.stance = Objects.requireNonNull(stance, "stance").immutable();
        if (requestedHoeItem != null && !(requestedHoeItem instanceof HoeItem)) {
            throw new IllegalArgumentException("requested item must be a hoe");
        }
        this.requestedHoeItem = requestedHoeItem;
    }

    @Override
    protected void onStart() {
        dimension = currentDimension();
        selectedHoe = null;
        terminal = false;
        successful = false;
        reason = FarmTaskReason.NONE;
        alreadyFarmland = false;
        freshTill = false;
        durabilityApplied = false;
        noProgressTicks = 0;
        attempts = 0;
        ticksSinceAttempt = RETRY_DELAY_TICKS;
        transientReceiptDetached = false;

        if (PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return;
        }
        if (!loaded(target) || !loaded(target.above()) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return;
        }
        expectedBefore = this.controller.getWorld().getBlockState(target);
        if (expectedBefore.is(Blocks.FARMLAND)) {
            alreadyFarmland = true;
            succeed();
            return;
        }
        if (!FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)
                || tillProtectionDenied(
                isProtected(target),
                isProtected(target.above()),
                isProtected(stance.below()),
                isProtected(stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                isProtected(stance.above()))) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return;
        }
        if (!this.controller.getWorld().getBlockState(target.above()).isAir()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return;
        }
        selectedHoe = chooseExactHoe();
        if (selectedHoe == null) {
            fail(FarmTaskReason.HOE_UNAVAILABLE);
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
        if (!loaded(target) || !loaded(target.above()) || !loaded(stance)
                || !loaded(stance.below()) || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }

        BlockState observed = this.controller.getWorld().getBlockState(target);
        if (!observed.equals(expectedBefore)) {
            completeOwnedTillTransition(observed);
            return null;
        }
        if (!FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)
                || tillProtectionDenied(
                isProtected(target),
                isProtected(target.above()),
                isProtected(stance.below()),
                isProtected(stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                isProtected(stance.above()))) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }

        noProgressTicks++;
        ticksSinceAttempt++;
        if (noProgressTicks > FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!this.controller.getWorld().getBlockState(target.above()).isAir()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        // Minecraft's raw blockPosition() rounds feet on 15/16-high farmland into the support
        // block. Use the same logical-feet coordinate as Baritone goal/path evaluation instead.
        if (!FarmStanceNavigation.isAtFrozenStance(this.controller, stance)) {
            this.setDebugState("Moving to fixed till stance");
            return new GetToBlockTask(stance);
        }
        if (attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (ticksSinceAttempt < RETRY_DELAY_TICKS) {
            return null;
        }
        if (!equipSelectedHoe()) {
            fail(FarmTaskReason.HOE_UNAVAILABLE);
            return null;
        }

        Optional<Rotation> reach = LookHelper.getReach(this.controller, target, Direction.UP);
        if (reach.isEmpty()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!LookHelper.isLookingAt(this.controller, reach.get())) {
            LookHelper.lookAt(this.controller, reach.get());
            return null;
        }

        ItemStack held = this.controller.getPlayer().getMainHandItem();
        HitResult mouseOver = controller.getBaritone().getEntityContext().objectMouseOver();
        Optional<InteractionResult> interaction = dispatchTillAttempt(
                mouseOver,
                held::getDamageValue,
                held::getMaxDamage,
                hit -> FarmInteractionDispatcher.useMainHandBlock(controller, hit));
        if (interaction.isEmpty()) {
            // Approximate rotation equality does not prove the live ray still hits this cell's top.
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        this.setDebugState("Tilling and verifying target (attempt " + attempts + "/"
                + FarmPlotPolicy.MUTATION_MAX_ATTEMPTS + ")");
        // Vanilla and loader hoe use mutates the block synchronously. Settle immediately so the
        // exact hoe is still visibly held for its one animation/durability charge. The ordinary
        // next-tick and transient settlement paths remain as fallback for delayed mod behavior.
        BlockState afterInteraction = controller.getWorld().getBlockState(target);
        if (shouldSettleImmediatelyAfterDispatch(
                attempts, durabilityApplied, expectedBefore, afterInteraction)) {
            completeOwnedTillTransition(afterInteraction);
        }
        return null;
    }

    static boolean isExpectedTargetTopHit(HitResult hit, BlockPos expectedTarget) {
        return FarmInteractionDispatcher.isExpectedBlockFace(
                hit, expectedTarget, Direction.UP);
    }

    /** Package-visible exact synchronous receipt boundary used by the farming regression suite. */
    Optional<InteractionResult> dispatchTillAttempt(
            HitResult hit,
            IntSupplier damageSupplier,
            IntSupplier maximumDamageSupplier,
            Function<BlockHitResult, InteractionResult> interaction) {
        return FarmInteractionDispatcher.dispatchExactFace(
                hit,
                target,
                Direction.UP,
                () -> {
                    beforeDamage = Objects.requireNonNull(
                            damageSupplier, "damageSupplier").getAsInt();
                    maximumDamage = Objects.requireNonNull(
                            maximumDamageSupplier, "maximumDamageSupplier").getAsInt();
                    attempts++;
                    ticksSinceAttempt = 0;
                },
                interaction);
    }

    private ItemStack chooseExactHoe() {
        LivingEntityInventory inventory = this.controller.getInventory();
        ItemStack best = null;
        int bestRemaining = -1;
        for (ItemStack candidate : inventory.main) {
            if (candidate.isEmpty() || !(candidate.getItem() instanceof HoeItem)) {
                continue;
            }
            if (requestedHoeItem != null && candidate.getItem() != requestedHoeItem) {
                continue;
            }
            if (!FarmHoePolicy.isAccepted(candidate)) {
                continue;
            }
            int remaining = candidate.getMaxDamage() - candidate.getDamageValue();
            if (remaining > bestRemaining) {
                best = candidate;
                bestRemaining = remaining;
            }
        }
        return bestRemaining >= 1 ? best : null;
    }

    static boolean ownsFreshTillTransition(int issuedAttempts) {
        return issuedAttempts > 0;
    }

    static boolean needsOwnedTransitionSettlement(
            int issuedAttempts,
            boolean damageAlreadyApplied,
            BlockState expected,
            BlockState observed) {
        return issuedAttempts > 0
                && !damageAlreadyApplied
                && expected != null
                && observed != null
                && !observed.equals(expected);
    }

    /** Pure decision used by the same-tick interaction path and its deterministic regression. */
    static boolean shouldSettleImmediatelyAfterDispatch(
            int issuedAttempts,
            boolean damageAlreadyApplied,
            BlockState expected,
            BlockState observed) {
        return needsOwnedTransitionSettlement(
                issuedAttempts, damageAlreadyApplied, expected, observed);
    }

    /**
     * Closes the one-tick receipt window where the use action changed the block to farmland but the
     * NPC-owned durability charge has not yet been applied. Called by the setup root before it
     * discards this child for a transient resume.
     */
    @Override
    public boolean settlePendingTransition() {
        if (terminal) {
            return successful;
        }
        if (controller == null || expectedBefore == null) {
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
        BlockState observed = controller.getWorld().getBlockState(target);
        if (!needsOwnedTransitionSettlement(
                attempts, durabilityApplied, expectedBefore, observed)) {
            return true;
        }
        return completeOwnedTillTransition(observed);
    }

    @Override
    public boolean hasIssuedMutation() {
        return attempts > 0;
    }

    @Override
    public void prepareForTransientDetach() {
        transientReceiptDetached = true;
    }

    private boolean completeOwnedTillTransition(BlockState observed) {
        if (!FarmInteractionPostconditions.farmlandTransition(expectedBefore, observed)) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return false;
        }
        if (!ownsFreshTillTransition(attempts)) {
            // Another actor tilled the frozen target before this task issued its own use. Do not
            // charge the NPC's hoe for someone else's world mutation.
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return false;
        }
        freshTill = true;
        if (durabilityApplied) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        ItemStack ownedHoe = selectedHoe;
        if (ownedHoe == null
                || ownedHoe.isEmpty()
                || !inventoryContainsIdentity(ownedHoe)
                || ownedHoe.getDamageValue() != beforeDamage
                || ownedHoe.getMaxDamage() != maximumDamage) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        // Immediate vanilla settlement still has this exact hoe selected. A delayed mod receipt or
        // transient-settlement fallback may run after inventory maintenance, so restore the same
        // object before animating. Swing before damage so the final durability use visibly holds
        // the hoe rather than an empty hand or the maintenance-restored pickaxe.
        if (controller.getPlayer().getMainHandItem() != ownedHoe && !equipSelectedHoe()) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        this.controller.getEntity().swing(InteractionHand.MAIN_HAND);
        durabilityApplied = HoeDurabilityCompat.damageOnce(ownedHoe, this.controller.getEntity());
        if (!durabilityApplied) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        succeed();
        return true;
    }

    private boolean inventoryContainsIdentity(ItemStack expected) {
        for (ItemStack stack : controller.getInventory().main) {
            if (stack == expected) {
                return true;
            }
        }
        return false;
    }

    static boolean isStoneOrBetter(HoeItem hoe) {
        return FarmHoePolicy.isStoneOrBetter(hoe);
    }

    /** Package-visible exact protection gate used by the farming self-test. */
    static boolean tillProtectionDenied(
            boolean targetProtected,
            boolean targetHeadProtected,
            boolean stanceSupportProtected,
            boolean stanceProtected,
            boolean stanceIsPassableCrop,
            boolean stanceHeadProtected) {
        return targetProtected
                || targetHeadProtected
                || FarmStanceNavigation.stanceProtectionDenied(
                stanceSupportProtected,
                stanceProtected,
                stanceIsPassableCrop,
                stanceHeadProtected);
    }

    private boolean equipSelectedHoe() {
        if (selectedHoe == null || selectedHoe.isEmpty()) {
            return false;
        }
        LivingEntityInventory inventory = this.controller.getInventory();
        int exactSlot = -1;
        for (int i = 0; i < inventory.main.size(); i++) {
            if (inventory.main.get(i) == selectedHoe) {
                exactSlot = i;
                break;
            }
        }
        if (exactSlot < 0) {
            return false;
        }
        this.controller.getSlotHandler().forceEquipSlot(
                this.controller, PlayerSlot.getMainSlot(inventory, exactSlot));
        return this.controller.getPlayer().getMainHandItem() == selectedHoe
                && selectedHoe.getItem() instanceof HoeItem;
    }

    private boolean loaded(BlockPos pos) {
        return this.controller.getChunkTracker().isChunkLoaded(pos);
    }

    private boolean isProtected(BlockPos pos) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store == null || store.contains(dimension, pos);
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

    public BlockPos stance() {
        return stance;
    }

    public Item selectedHoeItem() {
        return selectedHoe == null ? null : selectedHoe.getItem();
    }

    public boolean alreadyFarmland() {
        return alreadyFarmland;
    }

    public boolean freshTill() {
        return freshTill;
    }

    public boolean durabilityApplied() {
        return durabilityApplied;
    }

    public int attempts() {
        return attempts;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TillFarmBlockTask task
                && task.target.equals(target)
                && task.stance.equals(stance)
                && task.requestedHoeItem == requestedHoeItem;
    }

    @Override
    protected String toDebugString() {
        return "Till verified farm block at " + target.toShortString();
    }
}
