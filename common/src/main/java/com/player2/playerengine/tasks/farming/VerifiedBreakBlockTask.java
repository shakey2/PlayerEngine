package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.util.helpers.LookHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.util.slots.PlayerSlot;
import com.player2.playerengine.util.slots.Slot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Breaks exactly one block through normal look/left-click handling and verifies the state transition.
 * Builder/path inactivity is never interpreted as completion.
 */
public final class VerifiedBreakBlockTask extends Task implements FarmMutationReceipt {
    enum ObservedTransition {
        UNCHANGED,
        SUCCESS,
        PRE_ATTEMPT_DRIFT,
        INVALID_DRIFT
    }

    private final BlockPos target;
    private final BlockPos stance;
    private final Predicate<BlockState> allowedAfter;
    private final Predicate<BlockState> requiredBefore;
    private final boolean allowProtectedTarget;
    private final boolean requirePlannerSafeStanceSupport;
    private final boolean protectStanceSupport;
    private final boolean protectTargetSupport;
    private final boolean requireUnmodifiedDropHand;
    private final String operationIdentity;
    private final FarmBreakToolRequirement repairToolRequirement;
    private final SourceGuard sourceGuard;

    private String dimension;
    private BlockState expectedBefore;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private int noProgressTicks;
    private int attempts;
    private int ticksInAttempt;
    private int attemptTickBudget;
    private boolean clickHeld;
    private ItemStack selectedRepairStack;
    private boolean preAttemptTargetDrift;
    private boolean transientReceiptDetached;

    public VerifiedBreakBlockTask(BlockPos target, BlockPos stance) {
        this(target, stance, BlockState::isAir);
    }

    public VerifiedBreakBlockTask(
            BlockPos target,
            BlockPos stance,
            Predicate<BlockState> allowedAfter) {
        this(target, stance, allowedAfter, state -> !allowedAfter.test(state),
                false, true, true, false, false,
                "ordinary", null, null);
    }

    private VerifiedBreakBlockTask(
            BlockPos target,
            BlockPos stance,
            Predicate<BlockState> allowedAfter,
            Predicate<BlockState> requiredBefore,
            boolean allowProtectedTarget,
            boolean requirePlannerSafeStanceSupport,
            boolean protectStanceSupport,
            boolean protectTargetSupport,
            boolean requireUnmodifiedDropHand,
            String operationIdentity,
            FarmBreakToolRequirement repairToolRequirement,
            SourceGuard sourceGuard) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.stance = Objects.requireNonNull(stance, "stance").immutable();
        this.allowedAfter = Objects.requireNonNull(allowedAfter, "allowedAfter");
        this.requiredBefore = Objects.requireNonNull(requiredBefore, "requiredBefore");
        this.allowProtectedTarget = allowProtectedTarget;
        this.requirePlannerSafeStanceSupport = requirePlannerSafeStanceSupport;
        this.protectStanceSupport = protectStanceSupport;
        this.protectTargetSupport = protectTargetSupport;
        this.requireUnmodifiedDropHand = requireUnmodifiedDropHand;
        this.operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        this.repairToolRequirement = repairToolRequirement;
        this.sourceGuard = sourceGuard;
    }

    /**
     * Creates the narrow structure-protection exception used by a mature-crop harvest pass.
     * Only the frozen, still-actionable crop target may be protected. A protected crop may also be
     * traversed at the stance feet. Its non-mutating perimeter stance may use any stable support,
     * while feet/headroom protection and maturity/type drift remain enforced before input.
     */
    static VerifiedBreakBlockTask forMatureCrop(BlockPos target, BlockPos stance) {
        return new VerifiedBreakBlockTask(
                target,
                stance,
                BlockState::isAir,
                HarvestFarmTask::isActionable,
                true,
                false,
                false,
                false,
                false,
                "mature-crop",
                null,
                null);
    }

    /**
     * Exact-state, no-protection-exception break used by frozen acquisition-source manifests.
     * The relaxed stance support accepts ordinary stable village terrain, while target, stance
     * support, feet, and head protection all remain enforced. A replacement block is never broken.
     */
    static VerifiedBreakBlockTask forUnprotectedSource(
            BlockPos target,
            BlockPos stance,
            BlockState expectedState,
            Item plantingItem,
            String dimensionId,
            PlantableSourcePlanner.SourceKind sourceKind) {
        BlockState frozenExpected = Objects.requireNonNull(
                expectedState, "expectedState");
        SourceGuard guard = new SourceGuard(
                Objects.requireNonNull(plantingItem, "plantingItem"),
                dimensionId,
                Objects.requireNonNull(sourceKind, "sourceKind"),
                frozenExpected);
        return new VerifiedBreakBlockTask(
                target,
                stance,
                BlockState::isAir,
                frozenExpected::equals,
                false,
                false,
                true,
                true,
                true,
                "unprotected-source:" + guard.identity(),
                null,
                guard);
    }

    /**
     * Narrow exception for a verified, task-local repair action inside an exact prepared farm.
     * Only the frozen non-crop fingerprint may be removed. Stance protections remain enforced
     * except for traversing a recognized collision-empty crop at the stance feet.
     */
    static VerifiedBreakBlockTask forFarmRepair(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint,
            FarmBreakToolRequirement breakToolRequirement) {
        String fingerprint = Objects.requireNonNull(
                expectedStateFingerprint, "expectedStateFingerprint");
        FarmBreakToolRequirement toolRequirement = Objects.requireNonNull(
                breakToolRequirement, "breakToolRequirement");
        BlockState expectedState = Objects.requireNonNull(
                toolRequirement.expectedState(), "breakToolRequirement.expectedState");
        if (!fingerprint.equals(FarmSiteWorldView.stateFingerprint(expectedState))) {
            throw new IllegalArgumentException(
                    "Repair tool requirement must describe the frozen target state");
        }
        return new VerifiedBreakBlockTask(
                target,
                stance,
                BlockState::isAir,
                state -> fingerprint.equals(FarmSiteWorldView.stateFingerprint(state))
                        && state.is(expectedState.getBlock())
                        && HarvestBehaviorRegistry.DEFAULT.resolve(state).isEmpty(),
                true,
                true,
                true,
                false,
                false,
                "farm-repair:" + fingerprint + ":" + toolRequirement,
                toolRequirement,
                null);
    }

    @Override
    protected void onStart() {
        dimension = currentDimension();
        terminal = false;
        successful = false;
        reason = FarmTaskReason.NONE;
        noProgressTicks = 0;
        attempts = 0;
        ticksInAttempt = 0;
        attemptTickBudget = 0;
        clickHeld = false;
        selectedRepairStack = null;
        preAttemptTargetDrift = false;
        transientReceiptDetached = false;

        if (PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return;
        }
        if (!loaded(target) || !loaded(stance) || !loaded(stance.below())
                || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return;
        }
        expectedBefore = this.controller.getWorld().getBlockState(target);
        boolean requiredBeforeSatisfied = requiredBefore.test(expectedBefore);
        if (!requiredBeforeSatisfied || allowedAfter.test(expectedBefore)) {
            preAttemptTargetDrift = true;
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return;
        }
        if (targetProtectionDenied(
                        isProtected(target), allowProtectedTarget, requiredBeforeSatisfied)
                || (protectTargetSupport && isProtected(target.below()))
                || liveStanceUnsafe()
                || !sourceGuardPermits()) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
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
        if (!loaded(target) || !loaded(stance) || !loaded(stance.below())
                || !loaded(stance.above())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }
        BlockState observed = this.controller.getWorld().getBlockState(target);
        ObservedTransition transition = observedTransition(
                expectedBefore, observed, allowedAfter, attempts);
        if (transition != ObservedTransition.UNCHANGED) {
            if (transition == ObservedTransition.SUCCESS) {
                succeed(transition);
            } else {
                preAttemptTargetDrift = transition == ObservedTransition.PRE_ATTEMPT_DRIFT;
                fail(FarmTaskReason.MANIFEST_DRIFT);
            }
            return null;
        }
        if (targetProtectionDenied(
                    isProtected(target), allowProtectedTarget, requiredBefore.test(expectedBefore))
                || (protectTargetSupport && isProtected(target.below()))
                || liveStanceUnsafe()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }

        // A successful target transition is deliberately checked first: if the selected tool
        // breaks on the same tick as the block, the verified world receipt wins. Otherwise a
        // vanished or replaced strict repair tool terminates before another click is issued.
        if (clickHeld && repairToolRequirement != null
                && repairToolRequirement.requiresTool()
                && !selectedRepairToolStillAvailable()) {
            fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
            return null;
        }

        noProgressTicks++;
        if (noProgressTicks > FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!FarmStanceNavigation.isAtFrozenStance(this.controller, stance)) {
            stopClick();
            this.setDebugState("Moving to fixed break stance");
            return new GetToBlockTask(stance);
        }

        Optional<Rotation> reach = LookHelper.getReach(this.controller, target);
        if (reach.isEmpty()) {
            stopClick();
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!LookHelper.isLookingAt(this.controller, reach.get())) {
            stopClick();
            LookHelper.lookAt(this.controller, reach.get());
            return null;
        }
        // This is the final source-authorization gate before either starting or continuing input.
        // It closes the navigation race where a farm registration or structure/protection change
        // could otherwise land after the acquisition root selected this frozen candidate.
        if (!sourceGuardPermits()) {
            stopClick();
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        boolean repairHandPrepared = false;
        if (!clickHeld) {
            if (attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
                fail(FarmTaskReason.INTERACTION_DENIED);
                return null;
            }
            if (repairToolRequirement != null) {
                // Revalidate the frozen fingerprint/type immediately before each continuous
                // click attempt, then equip the exact full-inventory slot selected by policy.
                if (!requiredBefore.test(observed)) {
                    preAttemptTargetDrift = true;
                    fail(FarmTaskReason.MANIFEST_DRIFT);
                    return null;
                }
                if (!equipFarmRepairHand()) {
                    fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
                    return null;
                }
                repairHandPrepared = true;
            } else if (requireUnmodifiedDropHand) {
                if (!equipUnmodifiedDropHand()) {
                    fail(FarmTaskReason.INTERACTION_DENIED);
                    return null;
                }
            } else {
                equipBestTool(expectedBefore);
            }
            attempts++;
            ticksInAttempt = 0;
            float destroyProgress = this.controller.getPlayer() instanceof Player player
                    ? observed.getDestroyProgress(
                    player, this.controller.getWorld(), target)
                    : 0.0F;
            attemptTickBudget = continuousClickBudget(destroyProgress);
            clickHeld = true;
        }
        if (repairToolRequirement != null && !repairHandPrepared
                && !reequipSelectedRepairHand()) {
            fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
            return null;
        }
        if (requireUnmodifiedDropHand && !equipUnmodifiedDropHand()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        this.controller.getInputControls().hold(Input.CLICK_LEFT);
        ticksInAttempt++;
        if (ticksInAttempt >= attemptTickBudget) {
            stopClick();
        }
        this.setDebugState("Breaking and verifying target (attempt " + attempts + "/"
                + FarmPlotPolicy.MUTATION_MAX_ATTEMPTS + ")");
        return null;
    }

    private void equipBestTool(BlockState state) {
        Optional<Slot> best = StorageHelper.getBestToolSlot(this.controller, state);
        if (best.isEmpty()) {
            return;
        }
        Item exactItem = StorageHelper.getItemStackInSlot(best.get()).getItem();
        this.controller.getSlotHandler().forceEquipItem(exactItem);
    }

    /** Grass shears change the loot identity; source acquisition must use any non-shears hand. */
    private boolean equipUnmodifiedDropHand() {
        LivingEntityInventory inventory = this.controller.getInventory();
        int selected = -1;
        for (int i = 0; i < inventory.main.size(); i++) {
            if (inventory.main.get(i).isEmpty()) {
                selected = i;
                break;
            }
        }
        if (selected < 0) {
            for (int i = 0; i < inventory.main.size(); i++) {
                if (safeUnmodifiedDropHand(inventory.main.get(i))) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected < 0) {
            return false;
        }
        this.controller.getSlotHandler().forceEquipSlot(
                this.controller, PlayerSlot.getMainSlot(inventory, selected));
        return safeUnmodifiedDropHand(this.controller.getPlayer().getMainHandItem());
    }

    static boolean safeUnmodifiedDropHand(ItemStack stack) {
        ItemStack checked = Objects.requireNonNull(stack, "stack");
        return checked.isEmpty()
                || (!checked.isDamageableItem()
                && !(checked.getItem() instanceof ShearsItem));
    }

    private boolean equipFarmRepairHand() {
        LivingEntityInventory inventory = this.controller.getInventory();
        int exactSlot = FarmRepairToolPlan.bestSlot(inventory, repairToolRequirement, 1);
        if (exactSlot < 0 || exactSlot >= inventory.main.size()) {
            return false;
        }

        ItemStack exactStack = inventory.main.get(exactSlot);
        if (repairToolRequirement.requiresTool()) {
            if (exactStack.isEmpty() || !repairToolRequirement.accepts(exactStack)) {
                return false;
            }
        }

        selectedRepairStack = exactStack;
        return equipExactRepairSlot(inventory, exactSlot, exactStack);
    }

    /** Reasserts the exact policy-selected identity before every continued click tick. */
    private boolean reequipSelectedRepairHand() {
        LivingEntityInventory inventory = this.controller.getInventory();
        int exactSlot = findIdentitySlot(inventory, selectedRepairStack);
        if (exactSlot < 0) {
            // A required tool disappearing while the target remains unchanged is terminal. A
            // no-tool hand is just a safety choice, so it may be replanned from live inventory.
            return !repairToolRequirement.requiresTool() && equipFarmRepairHand();
        }
        return equipExactRepairSlot(inventory, exactSlot, selectedRepairStack);
    }

    private boolean equipExactRepairSlot(
            LivingEntityInventory inventory,
            int exactSlot,
            ItemStack exactStack) {
        if (repairToolRequirement.requiresTool()) {
            if (exactStack.isEmpty() || !repairToolRequirement.accepts(exactStack)) {
                return false;
            }
        }
        this.controller.getSlotHandler().forceEquipSlot(
                this.controller, PlayerSlot.getMainSlot(inventory, exactSlot));
        ItemStack mainHand = this.controller.getPlayer().getMainHandItem();
        if (mainHand != exactStack) {
            return false;
        }
        if (repairToolRequirement.requiresTool()
                && !repairToolRequirement.accepts(mainHand)) {
            return false;
        }
        return true;
    }

    private boolean selectedRepairToolStillAvailable() {
        return selectedRepairStack != null
                && findIdentitySlot(this.controller.getInventory(), selectedRepairStack) >= 0
                && !selectedRepairStack.isEmpty()
                && repairToolRequirement.accepts(selectedRepairStack);
    }

    private static int findIdentitySlot(
            LivingEntityInventory inventory,
            ItemStack expected) {
        if (expected == null) {
            return -1;
        }
        for (int i = 0; i < inventory.main.size(); i++) {
            if (inventory.main.get(i) == expected) {
                return i;
            }
        }
        return -1;
    }

    /** Prefer a free hand, but never fail a clear solely because every slot is damageable. */
    static boolean safeNoToolRepairHand(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.isEmpty()
                || !stack.isDamageableItem()
                || !(stack.getItem() instanceof HoeItem);
    }

    /**
     * Converts the live, equipped-hand destroy progress into one uninterrupted click window.
     * The bounded margin absorbs latency/tick rounding while the operation-wide no-progress
     * ceiling remains the hard upper bound.
     */
    static int continuousClickBudget(float destroyProgress) {
        int ceiling = FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS;
        if (!Float.isFinite(destroyProgress) || destroyProgress <= 0.0F) {
            return ceiling;
        }
        double rawTicks = Math.ceil(1.0D / destroyProgress);
        if (rawTicks >= ceiling) {
            return ceiling;
        }
        int expectedTicks = Math.max(1, (int) rawTicks);
        int safetyMargin = Math.max(5, Math.min(40,
                (int) Math.ceil(expectedTicks * 0.20D)));
        return Math.min(ceiling, expectedTicks + safetyMargin);
    }

    private boolean loaded(BlockPos pos) {
        return this.controller.getChunkTracker().isChunkLoaded(pos);
    }

    private boolean liveStanceUnsafe() {
        boolean geometrySafe = requirePlannerSafeStanceSupport
                ? FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)
                : FarmStanceNavigation.hasSafeLiveGeometry(controller, stance);
        return !geometrySafe
                || FarmStanceNavigation.stanceProtectionDenied(
                protectStanceSupport && isProtected(stance.below()),
                isProtected(stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                isProtected(stance.above()));
    }

    private boolean isProtected(BlockPos pos) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store == null || store.contains(dimension, pos);
    }

    private boolean sourceGuardPermits() {
        return sourceGuard == null || sourceGuard.permits(
                controller.getWorld(), target);
    }

    /** Package-visible operation-policy seam used by harvest/setup stance regressions. */
    boolean requiresUnprotectedStanceSupport() {
        return protectStanceSupport;
    }

    boolean requiresPlannerSafeStanceSupport() {
        return requirePlannerSafeStanceSupport;
    }

    /** Package-visible pure protection policy seam used by the farming self-test. */
    static boolean targetProtectionDenied(
            boolean protectedTarget,
            boolean allowProtectedTarget,
            boolean requiredBeforeSatisfied) {
        return protectedTarget && !(allowProtectedTarget && requiredBeforeSatisfied);
    }

    /**
     * A bot-owned break does not pass through the real-player break hook which normally prunes the
     * structure-protection store. Retire only the exact protected target after an owned, verified
     * success; never retire it for an ordinary break or any drift/no-change observation.
     */
    static boolean shouldRetireTargetProtection(
            boolean allowProtectedTarget,
            boolean protectedTarget,
            ObservedTransition transition) {
        return allowProtectedTarget
                && protectedTarget
                && transition == ObservedTransition.SUCCESS;
    }

    static ObservedTransition observedTransition(
            BlockState expectedBefore,
            BlockState observed,
            Predicate<BlockState> allowedAfter,
            int attempts) {
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(allowedAfter, "allowedAfter");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts cannot be negative");
        }
        if (observed.equals(expectedBefore)) {
            return ObservedTransition.UNCHANGED;
        }
        if (attempts == 0) {
            return ObservedTransition.PRE_ATTEMPT_DRIFT;
        }
        return FarmInteractionPostconditions.verifiedBreakTransition(
                expectedBefore, observed, allowedAfter)
                ? ObservedTransition.SUCCESS
                : ObservedTransition.INVALID_DRIFT;
    }

    /**
     * Closes the receipt window where the owned break landed after input was issued but before the
     * setup root consumed this child's terminal result. A task which never issued an attempt owns no
     * mutation, so live-state reconstruction remains responsible for any such external change.
     */
    @Override
    public boolean settlePendingTransition() {
        if (terminal) {
            return successful;
        }
        if (controller == null || expectedBefore == null || attempts <= 0) {
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
        ObservedTransition transition = observedTransition(
                expectedBefore, observed, allowedAfter, attempts);
        if (transition == ObservedTransition.UNCHANGED) {
            return true;
        }
        if (transition == ObservedTransition.SUCCESS) {
            succeed(transition);
            return true;
        }
        preAttemptTargetDrift = transition == ObservedTransition.PRE_ATTEMPT_DRIFT;
        fail(FarmTaskReason.MANIFEST_DRIFT);
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

    private String currentDimension() {
        return this.controller.getWorld().dimension().location().toString();
    }

    private void succeed(ObservedTransition transition) {
        stopClick();
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        boolean targetProtected = store != null && store.contains(dimension, target);
        if (shouldRetireTargetProtection(
                allowProtectedTarget, targetProtected, transition)) {
            // Idempotent: a loader/player-break hook may already have removed the entry. The
            // verified world mutation remains successful regardless of remove()'s boolean result.
            store.remove(dimension, target);
        }
        terminal = true;
        successful = true;
        reason = FarmTaskReason.NONE;
    }

    private void fail(FarmTaskReason failure) {
        stopClick();
        terminal = true;
        successful = false;
        reason = Objects.requireNonNull(failure, "failure");
    }

    private void stopClick() {
        if (controller != null) {
            controller.getInputControls().release(Input.CLICK_LEFT);
        }
        clickHeld = false;
        ticksInAttempt = 0;
        attemptTickBudget = 0;
        selectedRepairStack = null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        stopClick();
        if (controller != null) {
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

    public BlockState expectedBefore() {
        return expectedBefore;
    }

    public int attempts() {
        return attempts;
    }

    public boolean wasPreAttemptTargetDrift() {
        return preAttemptTargetDrift;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof VerifiedBreakBlockTask task
                && task.target.equals(target)
                && task.stance.equals(stance)
                && task.operationIdentity.equals(operationIdentity);
    }

    @Override
    protected String toDebugString() {
        return "Verified break at " + target.toShortString();
    }

    private record SourceGuard(
            Item plantingItem,
            String dimensionId,
            PlantableSourcePlanner.SourceKind sourceKind,
            BlockState expectedState) {
        private SourceGuard {
            plantingItem = Objects.requireNonNull(plantingItem, "plantingItem");
            if (dimensionId == null || dimensionId.isBlank()) {
                throw new IllegalArgumentException("dimensionId cannot be blank");
            }
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
            if (sourceKind != PlantableSourcePlanner.SourceKind.NATURAL_GRASS
                    && sourceKind != PlantableSourcePlanner.SourceKind.VILLAGE_CROP) {
                throw new IllegalArgumentException("source guard requires a break source");
            }
            expectedState = Objects.requireNonNull(expectedState, "expectedState");
        }

        private boolean permits(
                net.minecraft.server.level.ServerLevel level,
                BlockPos target) {
            return PlantableSourcePlanner.isLiveBreakCandidate(
                    level,
                    plantingItem,
                    dimensionId,
                    new PlantableSourcePlanner.BreakCandidate(
                            sourceKind, target, expectedState));
        }

        private String identity() {
            return sourceKind.name()
                    + ":" + dimensionId
                    + ":" + plantingItem
                    + ":" + FarmSiteWorldView.stateFingerprint(expectedState);
        }
    }
}
