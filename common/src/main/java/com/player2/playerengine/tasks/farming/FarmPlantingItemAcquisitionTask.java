package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.tasks.movement.GetToEntityTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finite, ordered planting-item acquisition for one logical farm request.
 *
 * <p>The parent planting root owns transient scheduling. This child deliberately does not implement
 * {@code TransientlyResumableTask}: its public {@link Checkpoint} survives child detachment, captures
 * the frozen source manifest/cursors/in-flight receipts, and lets the parent reconstruct a fresh
 * child after food, gesture, defense, or mob-attack preemption without selecting new sources or
 * resetting the cumulative deadline.
 */
public final class FarmPlantingItemAcquisitionTask extends Task implements DescribesProgress {
    public static final int WHOLE_TIMEOUT_TICKS = 18_000;
    public static final int MAX_WORLD_ROUNDS = 16;
    public static final int WORLD_ROUND_WANDER_TICKS = 600;
    public static final long WORLD_ROUND_WANDER_MS = WORLD_ROUND_WANDER_TICKS * 50L;
    public static final int PICKUP_TIMEOUT_TICKS = 60;
    public static final double PICKUP_RADIUS_BLOCKS = 8.0D;
    public static final double DROP_OWNERSHIP_RADIUS_BLOCKS = 2.0D;
    public static final int MAX_PREEXISTING_DROP_IDS = 64;
    public static final int MAX_OWNED_DROP_IDS = 16;

    public enum Phase {
        CHECK_INVENTORY,
        FREEZE_SOURCES,
        MARKED_CHESTS,
        DISCOVER_WORLD_ROUND,
        NATURAL_GRASS,
        VILLAGE_CROPS,
        VILLAGE_CHESTS,
        PICKUP_DROPS,
        WANDER_BETWEEN_ROUNDS,
        DONE,
        FAILED
    }

    public enum Outcome {
        RUNNING,
        ACQUIRED,
        SOURCE_EXHAUSTED,
        WORLD_GUARDS_UNAVAILABLE,
        SOURCE_DISCOVERY_FAILED,
        DEADLINE_EXCEEDED,
        DIMENSION_CHANGED,
        CANCELLED
    }

    /** Durable state owned by the logical PlantFarmTask root. */
    public static final class Checkpoint {
        private String targetItemId;
        private int absoluteTargetCount = -1;
        private BlockPos frozenOrigin;
        private String dimensionId;
        private int wholeTickBudget;
        private int wholeTicks;
        private int lastObservedInventoryCount;
        private boolean candidatesFrozen;
        private PlantableSourcePlanner.MarkedManifest markedManifest;
        private PlantableSourcePlanner.WorldRoundManifest worldRoundManifest;
        private Phase phase = Phase.CHECK_INVENTORY;
        private Outcome outcome = Outcome.RUNNING;
        private Outcome terminalRequest;
        private int markedCursor;
        private int naturalCursor;
        private int cropCursor;
        private int villageChestCursor;
        private int worldRoundsDiscovered;
        private int wanderTicksInLeg;
        private int naturalScanCursor;
        private int cropScanCursor;
        private int villageChestScanCursor;
        private final LinkedHashSet<BlockPos> attemptedNaturalGrass = new LinkedHashSet<>();
        private final LinkedHashSet<BlockPos> attemptedVillageCrops = new LinkedHashSet<>();
        private final LinkedHashSet<BlockPos> attemptedVillageChests = new LinkedHashSet<>();
        private TransferReceipt pendingTransfer;
        private BreakReceipt pendingBreak;
        private PickupReceipt pickupReceipt;

        public Checkpoint() {
        }

        public boolean isBound() {
            return absoluteTargetCount > 0;
        }

        public String targetItemId() {
            return targetItemId;
        }

        public int absoluteTargetCount() {
            return absoluteTargetCount;
        }

        public BlockPos frozenOrigin() {
            return frozenOrigin;
        }

        public String dimensionId() {
            return dimensionId;
        }

        public boolean candidatesFrozen() {
            return candidatesFrozen;
        }

        public int wholeTicks() {
            return wholeTicks;
        }

        public int markedCursor() {
            return markedCursor;
        }

        public int naturalCursor() {
            return naturalCursor;
        }

        public int cropCursor() {
            return cropCursor;
        }

        public int villageChestCursor() {
            return villageChestCursor;
        }

        public int worldRoundsDiscovered() {
            return worldRoundsDiscovered;
        }

        public int wanderTicksInLeg() {
            return wanderTicksInLeg;
        }

        public int attemptedNaturalGrassCount() {
            return attemptedNaturalGrass.size();
        }

        public int attemptedVillageCropCount() {
            return attemptedVillageCrops.size();
        }

        public int attemptedVillageChestCount() {
            return attemptedVillageChests.size();
        }

        public int naturalScanCursor() {
            return naturalScanCursor;
        }

        public int cropScanCursor() {
            return cropScanCursor;
        }

        public int villageChestScanCursor() {
            return villageChestScanCursor;
        }

        private void bind(
                Item item,
                int targetCount,
                BlockPos origin,
                String dimension,
                int tickBudget) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                throw new IllegalArgumentException("planting item must have a registry id");
            }
            String itemId = key.toString();
            if (!isBound()) {
                targetItemId = itemId;
                absoluteTargetCount = targetCount;
                frozenOrigin = origin.immutable();
                dimensionId = dimension;
                wholeTickBudget = tickBudget;
                return;
            }
            if (!targetItemId.equals(itemId)
                    || absoluteTargetCount != targetCount
                    || !frozenOrigin.equals(origin)
                    || !dimensionId.equals(dimension)
                    || wholeTickBudget != tickBudget) {
                throw new IllegalArgumentException(
                        "planting acquisition checkpoint cannot be rebound");
            }
        }

        private void freezeMarked(PlantableSourcePlanner.MarkedManifest frozenManifest) {
            if (candidatesFrozen) {
                return;
            }
            markedManifest = Objects.requireNonNull(frozenManifest, "frozenManifest");
            candidatesFrozen = true;
        }

        private void freezeWorldRound(
                PlantableSourcePlanner.WorldRoundManifest frozenManifest) {
            if (worldRoundManifest != null) {
                throw new IllegalStateException("world round is already frozen");
            }
            worldRoundManifest = Objects.requireNonNull(frozenManifest, "frozenManifest");
            naturalCursor = 0;
            cropCursor = 0;
            villageChestCursor = 0;
            naturalScanCursor = frozenManifest.nextNaturalScanCursor();
            cropScanCursor = frozenManifest.nextCropScanCursor();
            villageChestScanCursor = frozenManifest.nextVillageChestScanCursor();
            worldRoundsDiscovered++;
        }

        private void clearWorldRoundForWander() {
            worldRoundManifest = null;
            naturalCursor = 0;
            cropCursor = 0;
            villageChestCursor = 0;
            wanderTicksInLeg = 0;
        }
    }

    private record TransferReceipt(
            Phase phase,
            BlockPos position,
            int inventoryBaseline,
            boolean terminal,
            int moved) {
        private TransferReceipt {
            if (phase != Phase.MARKED_CHESTS && phase != Phase.VILLAGE_CHESTS) {
                throw new IllegalArgumentException("transfer receipt phase must be a chest phase");
            }
            position = Objects.requireNonNull(position, "position").immutable();
        }
    }

    private record BreakReceipt(
            PlantableSourcePlanner.BreakCandidate candidate,
            int inventoryBaseline,
            Set<UUID> preExistingDropIds,
            boolean issued,
            boolean terminal,
            boolean successful) {
        private BreakReceipt {
            candidate = Objects.requireNonNull(candidate, "candidate");
            preExistingDropIds = Set.copyOf(
                    preExistingDropIds == null ? Set.of() : preExistingDropIds);
        }
    }

    private record PickupReceipt(
            Phase returnPhase,
            BlockPos sourcePosition,
            Set<UUID> ownedDropIds,
            int ticks) {
        private PickupReceipt {
            if (returnPhase != Phase.NATURAL_GRASS && returnPhase != Phase.VILLAGE_CROPS) {
                throw new IllegalArgumentException("pickup return phase must be a break phase");
            }
            sourcePosition = Objects.requireNonNull(
                    sourcePosition, "sourcePosition").immutable();
            ownedDropIds = Set.copyOf(
                    ownedDropIds == null ? Set.of() : ownedDropIds);
            if (ticks < 0) {
                throw new IllegalArgumentException("pickup ticks cannot be negative");
            }
        }

        private PickupReceipt nextTick() {
            return new PickupReceipt(
                    returnPhase, sourcePosition, ownedDropIds, ticks + 1);
        }
    }

    private final Item plantingItem;
    private final int absoluteTargetCount;
    private final BlockPos frozenOrigin;
    private final String dimensionId;
    private final Checkpoint checkpoint;

    private BoundedContainerTransferTask transferChild;
    private VerifiedBreakBlockTask breakChild;
    private TimeoutWanderTask wanderChild;
    private boolean detachedCancelled;

    public FarmPlantingItemAcquisitionTask(
            Item plantingItem,
            int absoluteTargetCount,
            BlockPos frozenOrigin,
            String dimensionId) {
        this(
                plantingItem,
                absoluteTargetCount,
                frozenOrigin,
                dimensionId,
                new Checkpoint());
    }

    public FarmPlantingItemAcquisitionTask(
            Item plantingItem,
            int absoluteTargetCount,
            BlockPos frozenOrigin,
            String dimensionId,
            Checkpoint checkpoint) {
        this.plantingItem = Objects.requireNonNull(plantingItem, "plantingItem");
        if (absoluteTargetCount < 1
                || absoluteTargetCount > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException(
                    "absoluteTargetCount must be within the farm soil-cell bound");
        }
        this.absoluteTargetCount = absoluteTargetCount;
        this.frozenOrigin = Objects.requireNonNull(
                frozenOrigin, "frozenOrigin").immutable();
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }
        this.dimensionId = dimensionId;
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.bind(
                plantingItem,
                absoluteTargetCount,
                this.frozenOrigin,
                dimensionId,
                WHOLE_TIMEOUT_TICKS);
    }

    public Checkpoint checkpoint() {
        return checkpoint;
    }

    public Phase phase() {
        return checkpoint.phase;
    }

    public Outcome outcome() {
        return detachedCancelled && checkpoint.outcome == Outcome.RUNNING
                ? Outcome.CANCELLED
                : checkpoint.outcome;
    }

    public boolean isSuccessful() {
        return checkpoint.outcome == Outcome.ACQUIRED;
    }

    public int acquiredCount() {
        return checkpoint.lastObservedInventoryCount;
    }

    public int remainingCount() {
        return Math.max(0, absoluteTargetCount - acquiredCount());
    }

    /** Short controlled status suitable for a parent task's bounded model-facing feedback. */
    public String controlledReason() {
        return switch (outcome()) {
            case RUNNING -> "planting-item acquisition is still running";
            case ACQUIRED -> "the requested planting items were acquired";
            case SOURCE_EXHAUSTED -> "the bounded planting-item sources were exhausted";
            case WORLD_GUARDS_UNAVAILABLE ->
                    "world sources were skipped because protection data was unavailable";
            case SOURCE_DISCOVERY_FAILED -> "planting-item source discovery failed";
            case DEADLINE_EXCEEDED -> "planting-item acquisition reached its whole-operation deadline";
            case DIMENSION_CHANGED -> "planting-item acquisition stopped after a dimension change";
            case CANCELLED -> "planting-item acquisition was cancelled";
        };
    }

    @Override
    public boolean isFinished() {
        return checkpoint.outcome != Outcome.RUNNING || detachedCancelled;
    }

    @Override
    protected void onStart() {
        transferChild = null;
        breakChild = null;
        wanderChild = null;
        detachedCancelled = false;
        setDebugState(checkpoint.phase.name());
    }

    @Override
    protected Task onTick() {
        if (checkpoint.outcome != Outcome.RUNNING) {
            return null;
        }
        checkpoint.wholeTicks++;
        settleCompletedMutationChildren();
        if (!dimensionId.equals(currentDimension())) {
            if (checkpoint.terminalRequest == null) {
                beginTerminalSettlement(Outcome.DIMENSION_CHANGED);
            } else {
                fail(Outcome.DIMENSION_CHANGED);
            }
            return null;
        }

        Task receiptNavigation = reconcilePendingReceipts();
        updateObservedInventory();
        if (checkpoint.terminalRequest != null) {
            if (receiptNavigation != null) {
                if (checkpoint.terminalRequest == Outcome.ACQUIRED
                        && checkpoint.wholeTicks < checkpoint.wholeTickBudget) {
                    return receiptNavigation;
                }
                fail(checkpoint.terminalRequest == Outcome.ACQUIRED
                        ? Outcome.DEADLINE_EXCEEDED
                        : checkpoint.terminalRequest);
                return null;
            }
            finalizeTerminalSettlement();
            return null;
        }
        if (checkpoint.lastObservedInventoryCount >= absoluteTargetCount) {
            beginTerminalSettlement(Outcome.ACQUIRED);
            return null;
        }
        if (checkpoint.wholeTicks >= checkpoint.wholeTickBudget) {
            beginTerminalSettlement(Outcome.DEADLINE_EXCEEDED);
            return null;
        }
        if (receiptNavigation != null) {
            return receiptNavigation;
        }

        if (checkpoint.outcome != Outcome.RUNNING) {
            return null;
        }

        setDebugState(checkpoint.phase.name());
        return switch (checkpoint.phase) {
            case CHECK_INVENTORY -> advanceFromInventory();
            case FREEZE_SOURCES -> freezeSources();
            case MARKED_CHESTS -> processMarkedChests();
            case DISCOVER_WORLD_ROUND -> discoverWorldRound();
            case NATURAL_GRASS -> processNaturalGrass();
            case VILLAGE_CROPS -> processVillageCrops();
            case VILLAGE_CHESTS -> processVillageChests();
            case PICKUP_DROPS -> processPickup();
            case WANDER_BETWEEN_ROUNDS -> processWorldRoundWander();
            case DONE, FAILED -> null;
        };
    }

    private Task advanceFromInventory() {
        checkpoint.phase = Phase.FREEZE_SOURCES;
        return null;
    }

    private Task freezeSources() {
        if (!checkpoint.candidatesFrozen) {
            try {
                checkpoint.freezeMarked(PlantableSourcePlanner.freezeMarked(
                        controller, plantingItem, frozenOrigin, dimensionId));
            } catch (RuntimeException discoveryFailure) {
                fail(Outcome.SOURCE_DISCOVERY_FAILED);
                return null;
            }
        }
        checkpoint.phase = Phase.MARKED_CHESTS;
        return null;
    }

    private Task processMarkedChests() {
        if (transferChild != null && checkpoint.pendingTransfer != null) {
            return processTransfer(
                    Phase.MARKED_CHESTS, checkpoint.pendingTransfer.position());
        }
        List<BlockPos> candidates = checkpoint.markedManifest.markedChests();
        if (checkpoint.markedCursor >= candidates.size()) {
            checkpoint.phase = Phase.DISCOVER_WORLD_ROUND;
            return null;
        }
        return processTransfer(
                Phase.MARKED_CHESTS,
                candidates.get(checkpoint.markedCursor));
    }

    private Task discoverWorldRound() {
        if (checkpoint.worldRoundManifest != null) {
            fail(Outcome.SOURCE_DISCOVERY_FAILED);
            return null;
        }
        if (checkpoint.worldRoundsDiscovered >= MAX_WORLD_ROUNDS) {
            fail(Outcome.SOURCE_EXHAUSTED);
            return null;
        }
        try {
            BlockPos scanOrigin = controller.getEntity().blockPosition().immutable();
            PlantableSourcePlanner.WorldRoundManifest round =
                    PlantableSourcePlanner.freezeWorldRound(
                            controller,
                            plantingItem,
                            scanOrigin,
                            dimensionId,
                            checkpoint.attemptedNaturalGrass,
                            checkpoint.attemptedVillageCrops,
                            checkpoint.attemptedVillageChests,
                            checkpoint.naturalScanCursor,
                            checkpoint.cropScanCursor,
                            checkpoint.villageChestScanCursor);
            if (!round.worldGuardsAvailable()) {
                fail(Outcome.WORLD_GUARDS_UNAVAILABLE);
                return null;
            }
            checkpoint.freezeWorldRound(round);
            checkpoint.phase = plantingItem == Items.WHEAT_SEEDS
                    ? Phase.NATURAL_GRASS
                    : Phase.VILLAGE_CROPS;
        } catch (RuntimeException discoveryFailure) {
            fail(Outcome.SOURCE_DISCOVERY_FAILED);
        }
        return null;
    }

    private Task processNaturalGrass() {
        PlantableSourcePlanner.WorldRoundManifest round = requireWorldRound();
        return round == null ? null : processBreakStage(
                round.naturalGrass(), Phase.NATURAL_GRASS, Phase.VILLAGE_CROPS);
    }

    private Task processVillageCrops() {
        PlantableSourcePlanner.WorldRoundManifest round = requireWorldRound();
        return round == null ? null : processBreakStage(
                round.villageCrops(), Phase.VILLAGE_CROPS, Phase.VILLAGE_CHESTS);
    }

    private Task processWorldRoundWander() {
        if (checkpoint.worldRoundManifest != null) {
            fail(Outcome.SOURCE_DISCOVERY_FAILED);
            return null;
        }
        if (checkpoint.wanderTicksInLeg >= WORLD_ROUND_WANDER_TICKS) {
            finishWanderLeg();
            return null;
        }
        checkpoint.wanderTicksInLeg++;
        if (wanderChild != null) {
            if (!wanderChild.isFinished() && !wanderChild.stopped()) {
                return wanderChild;
            }
            finishWanderLeg();
            return null;
        }
        long remainingMs = Math.max(
                50L,
                (long)(WORLD_ROUND_WANDER_TICKS - checkpoint.wanderTicksInLeg + 1)
                        * 50L);
        wanderChild = TimeoutWanderTask.bounded(
                Math.min(WORLD_ROUND_WANDER_MS, remainingMs));
        return wanderChild;
    }

    private void finishWorldRound() {
        checkpoint.clearWorldRoundForWander();
        if (checkpoint.worldRoundsDiscovered >= MAX_WORLD_ROUNDS) {
            fail(Outcome.SOURCE_EXHAUSTED);
            return;
        }
        checkpoint.phase = Phase.WANDER_BETWEEN_ROUNDS;
    }

    private void finishWanderLeg() {
        stopExploreProcess();
        wanderChild = null;
        checkpoint.wanderTicksInLeg = 0;
        checkpoint.phase = Phase.DISCOVER_WORLD_ROUND;
    }

    private PlantableSourcePlanner.WorldRoundManifest requireWorldRound() {
        if (checkpoint.worldRoundManifest == null) {
            fail(Outcome.SOURCE_DISCOVERY_FAILED);
            return null;
        }
        return checkpoint.worldRoundManifest;
    }

    private Task processVillageChests() {
        if (!worldSourcesPermitted()) {
            return null;
        }
        PlantableSourcePlanner.WorldRoundManifest round = requireWorldRound();
        if (round == null) {
            return null;
        }
        if (transferChild != null && checkpoint.pendingTransfer != null) {
            return processTransfer(
                    Phase.VILLAGE_CHESTS, checkpoint.pendingTransfer.position());
        }
        List<PlantableSourcePlanner.ChestCandidate> candidates =
                round.villageChests();
        if (checkpoint.villageChestCursor >= candidates.size()) {
            finishWorldRound();
            return null;
        }
        PlantableSourcePlanner.ChestCandidate candidate =
                candidates.get(checkpoint.villageChestCursor);
        if (!PlantableSourcePlanner.isLiveVillageChestCandidate(
                controller.getWorld(), dimensionId, candidate)) {
            advanceVillageChestCursor(candidate.position());
            return null;
        }
        return processTransfer(Phase.VILLAGE_CHESTS, candidate.position());
    }

    private Task processTransfer(Phase phase, BlockPos position) {
        if (phase == Phase.VILLAGE_CHESTS && !isLiveVillageChest(position)) {
            captureTransferChild();
            transferChild = null;
            Task reconciliation = reconcilePendingReceipts();
            if (checkpoint.pendingTransfer == null
                    && checkpoint.worldRoundManifest != null
                    && checkpoint.villageChestCursor
                    < checkpoint.worldRoundManifest.villageChests().size()) {
                PlantableSourcePlanner.ChestCandidate candidate =
                        checkpoint.worldRoundManifest.villageChests()
                                .get(checkpoint.villageChestCursor);
                if (candidate.position().equals(position)) {
                    advanceVillageChestCursor(position);
                }
            }
            return reconciliation;
        }
        if (transferChild != null) {
            if (!transferChild.isFinished() && !transferChild.stopped()) {
                return transferChild;
            }
            captureTransferChild();
            transferChild = null;
            return reconcilePendingReceipts();
        }
        if (getSub() != null) {
            return null;
        }
        int remaining = Math.max(1, absoluteTargetCount - inventoryCount());
        checkpoint.pendingTransfer = new TransferReceipt(
                phase, position, inventoryCount(), false, 0);
        transferChild = BoundedContainerTransferTask.withdrawUpTo(
                position, plantingItem, remaining);
        return transferChild;
    }

    private Task processBreakStage(
            List<PlantableSourcePlanner.BreakCandidate> candidates,
            Phase phase,
            Phase exhaustedPhase) {
        if (!worldSourcesPermitted()) {
            return null;
        }
        if (breakChild != null) {
            refreshPendingBreakFromChild(false);
            if (!breakChild.isFinished() && !breakChild.stopped()) {
                return breakChild;
            }
            breakChild = null;
            return reconcilePendingReceipts();
        }
        if (getSub() != null) {
            return !getSub().isFinished() && !getSub().stopped() ? getSub() : null;
        }

        int cursor = phase == Phase.NATURAL_GRASS
                ? checkpoint.naturalCursor
                : checkpoint.cropCursor;
        if (cursor >= candidates.size()) {
            checkpoint.phase = exhaustedPhase;
            return null;
        }
        PlantableSourcePlanner.BreakCandidate candidate = candidates.get(cursor);
        if (!controller.getWorld().hasChunkAt(candidate.position())) {
            setDebugState(phase.name() + ":returning-to-frozen-source");
            return new GetToBlockTask(candidate.position());
        }
        if (!PlantableSourcePlanner.isLiveBreakCandidate(
                controller.getWorld(), plantingItem, dimensionId, candidate)) {
            advanceBreakCursor(candidate);
            return null;
        }
        BlockPos stance = selectStance(candidate.position());
        if (stance == null) {
            advanceBreakCursor(candidate);
            return null;
        }
        Set<UUID> preExisting = preExistingDropIds(candidate.position());
        if (preExisting == null) {
            advanceBreakCursor(candidate);
            return null;
        }

        checkpoint.pendingBreak = new BreakReceipt(
                candidate,
                inventoryCount(),
                preExisting,
                false,
                false,
                false);
        breakChild = VerifiedBreakBlockTask.forUnprotectedSource(
                candidate.position(),
                stance,
                candidate.expectedState(),
                plantingItem,
                dimensionId,
                candidate.kind());
        return breakChild;
    }

    private Task processPickup() {
        PickupReceipt receipt = checkpoint.pickupReceipt;
        if (receipt == null) {
            fail(Outcome.SOURCE_DISCOVERY_FAILED);
            return null;
        }
        if (receipt.ticks() >= PICKUP_TIMEOUT_TICKS) {
            checkpoint.pickupReceipt = null;
            checkpoint.phase = receipt.returnPhase();
            return null;
        }
        checkpoint.pickupReceipt = receipt.nextTick();
        ItemEntity drop = nearestOwnedDrop(receipt);
        return drop == null ? null : new GetToEntityTask(drop);
    }

    /** Returns navigation only when a retained break receipt cannot yet be read safely. */
    private Task reconcilePendingReceipts() {
        TransferReceipt transfer = checkpoint.pendingTransfer;
        if (transfer != null && transferChild == null) {
            boolean inventoryRose = inventoryCount() > transfer.inventoryBaseline();
            checkpoint.pendingTransfer = null;
            checkpoint.phase = transfer.phase();
            if (transferReceiptAllowsAdvance(
                    transfer.terminal(), transfer.moved(), inventoryRose)) {
                advanceTransferCursor(transfer.phase(), transfer.position());
            }
        }

        BreakReceipt broken = checkpoint.pendingBreak;
        if (broken == null || breakChild != null) {
            return null;
        }
        BlockPos position = broken.candidate().position();
        if (!controller.getWorld().hasChunkAt(position)) {
            setDebugState("reconciling-frozen-break-receipt");
            return new GetToBlockTask(position);
        }
        BlockState live = controller.getWorld().getBlockState(position);
        boolean inventoryRose = inventoryCount() > broken.inventoryBaseline();
        boolean worldChanged = !live.equals(broken.candidate().expectedState());
        boolean ownsMutation = breakReceiptOwnsMutation(
                broken.successful(), broken.issued(), live.isAir(), inventoryRose);
        checkpoint.pendingBreak = null;
        checkpoint.phase = phaseFor(broken.candidate().kind());

        if (broken.terminal()) {
            advanceBreakCursor(broken.candidate());
            if (ownsMutation) {
                beginPickup(broken);
            }
        } else if (ownsMutation) {
            advanceBreakCursor(broken.candidate());
            beginPickup(broken);
        } else if (worldChanged) {
            // Pre-input drift belongs to another actor; retire it without claiming a pickup receipt.
            advanceBreakCursor(broken.candidate());
        }
        return null;
    }

    static boolean transferReceiptAllowsAdvance(
            boolean terminal,
            int authoritativeMoved,
            boolean inventoryRose) {
        if (authoritativeMoved < 0) {
            throw new IllegalArgumentException("authoritativeMoved cannot be negative");
        }
        // inventoryRose is deliberately non-authoritative: a player gift or unrelated pickup may
        // occur while this child is detached.
        return terminal || authoritativeMoved > 0;
    }

    static boolean breakReceiptOwnsMutation(
            boolean childSuccessful,
            boolean issued,
            boolean verifiedTargetRemoved,
            boolean inventoryRose) {
        // inventoryRose is deliberately non-authoritative for the same detached-child reason.
        return childSuccessful || (issued && verifiedTargetRemoved);
    }

    private void beginPickup(BreakReceipt receipt) {
        Phase returnPhase = phaseFor(receipt.candidate().kind());
        Set<UUID> ownedDropIds = newlyOwnedDropIds(receipt);
        if (ownedDropIds == null || ownedDropIds.isEmpty()) {
            checkpoint.pickupReceipt = null;
            checkpoint.phase = returnPhase;
            return;
        }
        checkpoint.pickupReceipt = new PickupReceipt(
                returnPhase,
                receipt.candidate().position(),
                ownedDropIds,
                0);
        checkpoint.phase = Phase.PICKUP_DROPS;
    }

    private void captureTransferChild() {
        TransferReceipt receipt = checkpoint.pendingTransfer;
        if (receipt == null || transferChild == null) {
            return;
        }
        BoundedContainerTransferTask.Outcome transferOutcome = transferChild.outcome();
        int moved = transferChild.entryOutcomes().stream()
                .mapToInt(ScanReportFormatter.EntryOutcome::moved)
                .sum();
        checkpoint.pendingTransfer = new TransferReceipt(
                receipt.phase(),
                receipt.position(),
                receipt.inventoryBaseline(),
                transferOutcome != null || transferChild.isFinished(),
                moved);
    }

    private void settleCompletedMutationChildren() {
        if (transferChild != null
                && (transferChild.isFinished() || transferChild.stopped())) {
            captureTransferChild();
            transferChild = null;
        }
        if (breakChild != null) {
            refreshPendingBreakFromChild(false);
            if (breakChild.isFinished() || breakChild.stopped()) {
                breakChild = null;
            }
        }
    }

    private void beginTerminalSettlement(Outcome requested) {
        if (requested == Outcome.RUNNING) {
            throw new IllegalArgumentException("terminal settlement outcome required");
        }
        checkpoint.terminalRequest = requested;
        captureTransferChild();
        refreshPendingBreakFromChild(true);
        transferChild = null;
        breakChild = null;
        wanderChild = null;
        stopExploreProcess();
        quiesce();
    }

    private void finalizeTerminalSettlement() {
        Outcome requested = checkpoint.terminalRequest;
        checkpoint.terminalRequest = null;
        updateObservedInventory();
        if ((requested == Outcome.ACQUIRED || requested == Outcome.DEADLINE_EXCEEDED)
                && checkpoint.lastObservedInventoryCount >= absoluteTargetCount) {
            succeed();
            return;
        }
        if (requested == Outcome.ACQUIRED) {
            if (checkpoint.wholeTicks >= checkpoint.wholeTickBudget) {
                fail(Outcome.DEADLINE_EXCEEDED);
            }
            return;
        }
        fail(requested);
    }

    private void refreshPendingBreakFromChild(boolean transientDetach) {
        BreakReceipt receipt = checkpoint.pendingBreak;
        if (receipt == null || breakChild == null) {
            return;
        }
        if (transientDetach) {
            breakChild.prepareForTransientDetach();
        }
        breakChild.settlePendingTransition();
        checkpoint.pendingBreak = new BreakReceipt(
                receipt.candidate(),
                receipt.inventoryBaseline(),
                receipt.preExistingDropIds(),
                receipt.issued() || breakChild.hasIssuedMutation(),
                breakChild.isFinished(),
                breakChild.isSuccessful());
    }

    private boolean worldSourcesPermitted() {
        if (checkpoint.worldRoundManifest == null
                || !checkpoint.worldRoundManifest.worldGuardsAvailable()
                || PlayerPlacedBlockStore.get() == null) {
            beginTerminalSettlement(Outcome.WORLD_GUARDS_UNAVAILABLE);
            return false;
        }
        return true;
    }

    private BlockPos selectStance(BlockPos target) {
        PlayerPlacedBlockStore playerPlaced = PlayerPlacedBlockStore.get();
        if (playerPlaced == null) {
            return null;
        }
        for (Direction direction : List.of(
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos stance = target.relative(direction).immutable();
            if (FarmStanceNavigation.hasSafeLiveGeometry(controller, stance)
                    && !playerPlaced.contains(dimensionId, stance.below())
                    && !playerPlaced.contains(dimensionId, stance)
                    && !playerPlaced.contains(dimensionId, stance.above())) {
                return stance;
            }
        }
        return null;
    }

    /** Null is the bounded overflow sentinel: do not break when old/new drop identity is ambiguous. */
    private Set<UUID> preExistingDropIds(BlockPos sourcePosition) {
        List<ItemEntity> drops = boundedSourceDrops(
                sourcePosition,
                entity -> entity.isAlive() && entity.getItem().is(plantingItem));
        if (drops.size() > MAX_PREEXISTING_DROP_IDS) {
            return null;
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        drops.stream()
                .sorted(Comparator.comparing(entity -> entity.getUUID().toString()))
                .forEach(entity -> ids.add(entity.getUUID()));
        return Set.copyOf(ids);
    }

    private ItemEntity nearestOwnedDrop(PickupReceipt receipt) {
        Vec3 player = controller.getEntity().position();
        List<ItemEntity> candidates = boundedSourceDrops(
                        receipt.sourcePosition(),
                        entity -> entity.isAlive()
                                && entity.getItem().is(plantingItem)
                                && receipt.ownedDropIds().contains(entity.getUUID()));
        if (candidates.size() > MAX_PREEXISTING_DROP_IDS) {
            return null;
        }
        return candidates
                .stream()
                .min(Comparator
                        .comparingDouble((ItemEntity entity) ->
                                entity.position().distanceToSqr(player))
                        .thenComparing(entity -> entity.getUUID().toString()))
                .orElse(null);
    }

    private Set<UUID> newlyOwnedDropIds(BreakReceipt receipt) {
        Vec3 center = Vec3.atCenterOf(receipt.candidate().position());
        double ownershipRadiusSq =
                DROP_OWNERSHIP_RADIUS_BLOCKS * DROP_OWNERSHIP_RADIUS_BLOCKS;
        List<UUID> ids = boundedSourceDrops(
                        receipt.candidate().position(),
                        entity -> entity.isAlive()
                                && entity.getItem().is(plantingItem)
                                && !receipt.preExistingDropIds().contains(entity.getUUID())
                                && entity.position().distanceToSqr(center) <= ownershipRadiusSq)
                .stream()
                .map(ItemEntity::getUUID)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (ids.size() > MAX_OWNED_DROP_IDS) {
            return null;
        }
        return Set.copyOf(ids);
    }

    private List<ItemEntity> boundedSourceDrops(
            BlockPos sourcePosition,
            java.util.function.Predicate<ItemEntity> predicate) {
        Vec3 center = Vec3.atCenterOf(sourcePosition);
        AABB bounds = AABB.ofSize(
                center,
                PICKUP_RADIUS_BLOCKS * 2.0D,
                PICKUP_RADIUS_BLOCKS * 2.0D,
                PICKUP_RADIUS_BLOCKS * 2.0D);
        ArrayList<ItemEntity> drops = new ArrayList<>(MAX_PREEXISTING_DROP_IDS + 1);
        double radiusSq = PICKUP_RADIUS_BLOCKS * PICKUP_RADIUS_BLOCKS;
        controller.getWorld().getEntities(
                EntityTypeTest.forClass(ItemEntity.class),
                bounds,
                entity -> entity.position().distanceToSqr(center) <= radiusSq
                        && predicate.test(entity),
                drops,
                MAX_PREEXISTING_DROP_IDS + 1);
        return drops;
    }

    private void advanceTransferCursor(Phase phase, BlockPos position) {
        if (phase == Phase.MARKED_CHESTS) {
            checkpoint.markedCursor++;
        } else if (phase == Phase.VILLAGE_CHESTS) {
            advanceVillageChestCursor(position);
        } else {
            throw new IllegalArgumentException("not a transfer phase: " + phase);
        }
    }

    private void advanceVillageChestCursor(BlockPos position) {
        checkpoint.attemptedVillageChests.add(
                Objects.requireNonNull(position, "position").immutable());
        checkpoint.villageChestCursor++;
    }

    private void advanceBreakCursor(
            PlantableSourcePlanner.BreakCandidate candidate) {
        if (candidate.kind() == PlantableSourcePlanner.SourceKind.NATURAL_GRASS) {
            checkpoint.attemptedNaturalGrass.add(candidate.position());
            checkpoint.naturalCursor++;
        } else if (candidate.kind() == PlantableSourcePlanner.SourceKind.VILLAGE_CROP) {
            checkpoint.attemptedVillageCrops.add(candidate.position());
            checkpoint.cropCursor++;
        } else {
            throw new IllegalArgumentException(
                    "not a break source: " + candidate.kind());
        }
    }

    private boolean isLiveVillageChest(BlockPos position) {
        PlantableSourcePlanner.WorldRoundManifest round = checkpoint.worldRoundManifest;
        if (round == null
                || checkpoint.villageChestCursor >= round.villageChests().size()) {
            return false;
        }
        PlantableSourcePlanner.ChestCandidate candidate =
                round.villageChests().get(checkpoint.villageChestCursor);
        if (!candidate.position().equals(position)) {
            return false;
        }
        // The frozen round is sufficient during long travel. Re-run every authoritative guard in
        // the short interaction radius, immediately before the transfer child may open/mutate.
        double distanceSq = controller.getEntity().position().distanceToSqr(
                position.getX() + 0.5D,
                position.getY() + 0.5D,
                position.getZ() + 0.5D);
        return distanceSq > 64.0D || PlantableSourcePlanner.isLiveVillageChestCandidate(
                controller.getWorld(), dimensionId, candidate);
    }

    private static Phase phaseFor(PlantableSourcePlanner.SourceKind kind) {
        return kind == PlantableSourcePlanner.SourceKind.NATURAL_GRASS
                ? Phase.NATURAL_GRASS
                : Phase.VILLAGE_CROPS;
    }

    private int inventoryCount() {
        return controller == null
                ? checkpoint.lastObservedInventoryCount
                : controller.getItemStorage().getItemCountInventoryOnly(plantingItem);
    }

    private void updateObservedInventory() {
        checkpoint.lastObservedInventoryCount = inventoryCount();
    }

    private String currentDimension() {
        return controller == null || controller.getWorld() == null
                ? ""
                : controller.getWorld().dimension().location().toString();
    }

    private void succeed() {
        updateObservedInventory();
        checkpoint.terminalRequest = null;
        checkpoint.outcome = Outcome.ACQUIRED;
        checkpoint.phase = Phase.DONE;
        checkpoint.pendingTransfer = null;
        checkpoint.pendingBreak = null;
        checkpoint.pickupReceipt = null;
        wanderChild = null;
        stopExploreProcess();
        setDebugState("DONE");
    }

    private void fail(Outcome failure) {
        if (failure == Outcome.RUNNING || failure == Outcome.ACQUIRED) {
            throw new IllegalArgumentException("failure outcome required");
        }
        updateObservedInventory();
        checkpoint.terminalRequest = null;
        checkpoint.outcome = failure;
        checkpoint.phase = Phase.FAILED;
        checkpoint.pendingTransfer = null;
        checkpoint.pendingBreak = null;
        checkpoint.pickupReceipt = null;
        wanderChild = null;
        stopExploreProcess();
        setDebugState("FAILED:" + failure.name());
    }

    /**
     * Snapshot receipts before the framework recursively stops children. The shared checkpoint stays
     * RUNNING so the parent root may reconstruct this acquisition after a transient detach.
     */
    @Override
    protected void onStop(Task interruptTask) {
        captureTransferChild();
        refreshPendingBreakFromChild(interruptTask != null);
        quiesce();
        transferChild = null;
        breakChild = null;
        wanderChild = null;
        detachedCancelled = interruptTask == null && checkpoint.outcome == Outcome.RUNNING;
    }

    private void quiesce() {
        if (controller == null) {
            return;
        }
        controller.getInputControls().release(Input.CLICK_LEFT);
        controller.getInputControls().release(Input.CLICK_RIGHT);
        controller.getBaritone().getPathingBehavior().forceCancel();
        if (controller.getBaritone().getCustomGoalProcess().isActive()) {
            controller.getBaritone().getCustomGoalProcess().onLostControl();
        }
        stopExploreProcess();
    }

    private void stopExploreProcess() {
        if (controller != null
                && controller.getBaritone().getExploreProcess().isActive()) {
            controller.getBaritone().getExploreProcess().onLostControl();
        }
    }

    @Override
    public String describeProgress() {
        return "planting-item-acquisition item=" + checkpoint.targetItemId
                + " phase=" + checkpoint.phase.name().toLowerCase(java.util.Locale.ROOT)
                + " have=" + acquiredCount()
                + " target=" + absoluteTargetCount
                + " ticks=" + checkpoint.wholeTicks;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FarmPlantingItemAcquisitionTask task
                && task.plantingItem == plantingItem
                && task.absoluteTargetCount == absoluteTargetCount
                && task.frozenOrigin.equals(frozenOrigin)
                && task.dimensionId.equals(dimensionId)
                && task.checkpoint == checkpoint;
    }

    @Override
    protected String toDebugString() {
        return "FarmPlantingItemAcquisition[" + checkpoint.targetItemId
                + " -> " + absoluteTargetCount + "]";
    }
}
