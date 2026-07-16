package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.farming.FarmHoePolicy;
import com.player2.playerengine.tasks.farming.FarmPlotPolicy;
import com.player2.playerengine.tasks.farming.FarmTaskReason;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic farm-specific hoe acquisition: live inventory, EllieGPS marked storage, then the
 * ordinary stone-hoe catalogue chain. The task owns no player/model messaging; its parent maps the
 * bounded typed outcome into the existing farm completion feedback.
 *
 * <p>A {@link Checkpoint} belongs to the logical setup root, not to one transient child instance.
 * Reconstructing this task with the same checkpoint after a scheduler pause preserves the frozen
 * candidate manifest and advances past every already-issued withdrawal. Live inventory is always
 * rechecked first, reconciling a transfer or craft receipt that landed during interruption.
 */
public final class FarmHoeAcquisitionTask extends Task {

    public enum Outcome {
        RUNNING,
        ACQUIRED_FROM_INVENTORY,
        ACQUIRED_FROM_MARKED_STORAGE,
        ACQUIRED_FROM_CRAFTING,
        UNAVAILABLE
    }

    private enum Phase {
        CHECK_INVENTORY,
        SEARCH_MARKED,
        WITHDRAW_MARKED,
        CRAFT_FALLBACK,
        DONE,
        FAILED
    }

    /**
     * Resume state for one logical acquisition attempt. Create a separate checkpoint for initial
     * acquisition and for a later replacement-hoe attempt because required durability is part of
     * the frozen identity.
     */
    public static final class Checkpoint {
        private int requiredDurability = -1;
        private BlockPos frozenCenter;
        private String dimensionId;
        private boolean candidatesFrozen;
        private List<Candidate> candidates = List.of();
        private int candidateCursor;
        private int markedTransferAttempts;
        private Candidate inFlightCandidate;
        private boolean transferAttemptInProgress;
        private int fallbackTargetStoneHoeCount;
        private boolean fallbackTerminalFailure;

        public Checkpoint() {
        }

        public boolean isBound() {
            return requiredDurability >= 0;
        }

        public int requiredDurability() {
            return requiredDurability;
        }

        public BlockPos frozenCenter() {
            return frozenCenter;
        }

        public String dimensionId() {
            return dimensionId;
        }

        public boolean candidatesFrozen() {
            return candidatesFrozen;
        }

        public int attemptedMarkedTransfers() {
            return markedTransferAttempts;
        }

        public boolean fallbackCommitted() {
            return fallbackTargetStoneHoeCount > 0;
        }

        private void bind(int required, BlockPos center, String dimension) {
            if (!isBound()) {
                requiredDurability = required;
                frozenCenter = center.immutable();
                dimensionId = dimension;
                return;
            }
            if (requiredDurability != required
                    || !Objects.equals(frozenCenter, center)
                    || !Objects.equals(dimensionId, dimension)) {
                throw new IllegalArgumentException(
                        "farm hoe checkpoint cannot be rebound to a different acquisition");
            }
        }

        private void freezeCandidates(List<Candidate> discovered) {
            if (candidatesFrozen) {
                return;
            }
            candidates = List.copyOf(discovered);
            candidatesFrozen = true;
        }

        /**
         * Claims one bounded attempt. A transient detach retains the in-flight candidate so the
         * same chest is retried after live inventory reconciliation. A terminal no-move/failure
         * retires it; a moved hoe with insufficient durability permits another bounded draw.
         */
        private Candidate claimNextCandidate() {
            if (!candidatesFrozen) {
                return null;
            }
            if (inFlightCandidate == null) {
                if (candidateCursor >= candidates.size()) {
                    return null;
                }
                inFlightCandidate = candidates.get(candidateCursor++);
            }
            if (!transferAttemptInProgress) {
                if (markedTransferAttempts >= FarmPlotPolicy.HOE_MARKED_CHEST_ATTEMPTS) {
                    return null;
                }
                markedTransferAttempts++;
                transferAttemptInProgress = true;
            }
            return inFlightCandidate;
        }

        private void completeTransferAttempt(boolean retainCandidate) {
            transferAttemptInProgress = false;
            if (!retainCandidate) {
                inFlightCandidate = null;
            }
        }

        private void retryStoppedTransfer() {
            transferAttemptInProgress = false;
        }

        private void commitFallbackTarget(int absoluteStoneHoeCount) {
            if (fallbackTargetStoneHoeCount <= 0) {
                fallbackTargetStoneHoeCount = absoluteStoneHoeCount;
            }
        }
    }

    private record Candidate(BlockPos position, Item item, int itemPriority) {
        private Candidate {
            position = position.immutable();
            Objects.requireNonNull(item, "item");
        }
    }

    private record CandidateKey(BlockPos position, Item item) {
    }

    private final int requiredDurability;
    private final BlockPos frozenCenter;
    private final String dimensionId;
    private final Checkpoint checkpoint;

    private Phase phase = Phase.CHECK_INVENTORY;
    private Outcome outcome = Outcome.RUNNING;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private BoundedContainerTransferTask withdrawChild;
    private Task craftChild;

    public FarmHoeAcquisitionTask(
            int requiredDurability,
            BlockPos frozenCenter,
            String dimensionId,
            Checkpoint checkpoint) {
        if (requiredDurability < 1 || requiredDurability > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException("requiredDurability must be within the farm soil bound");
        }
        this.requiredDurability = requiredDurability;
        this.frozenCenter = Objects.requireNonNull(frozenCenter, "frozenCenter").immutable();
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }
        this.dimensionId = dimensionId;
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        this.checkpoint.bind(requiredDurability, this.frozenCenter, dimensionId);
    }

    public FarmHoeAcquisitionTask(
            int requiredDurability,
            BlockPos frozenCenter,
            String dimensionId) {
        this(requiredDurability, frozenCenter, dimensionId, new Checkpoint());
    }

    @Override
    public boolean isFinished() {
        return outcome != Outcome.RUNNING;
    }

    public boolean isSuccessful() {
        return outcome == Outcome.ACQUIRED_FROM_INVENTORY
                || outcome == Outcome.ACQUIRED_FROM_MARKED_STORAGE
                || outcome == Outcome.ACQUIRED_FROM_CRAFTING;
    }

    public Outcome outcome() {
        return outcome;
    }

    public FarmTaskReason reason() {
        return reason;
    }

    public Checkpoint checkpoint() {
        return checkpoint;
    }

    @Override
    protected void onStart() {
        phase = Phase.CHECK_INVENTORY;
        setDebugState(phase.name());
    }

    @Override
    protected Task onTick() {
        if (isFinished()) {
            return null;
        }

        if (hasRequiredDurability()) {
            succeed(observedSuccessSource());
            return null;
        }

        return switch (phase) {
            case CHECK_INVENTORY -> {
                phase = checkpoint.candidatesFrozen ? Phase.WITHDRAW_MARKED : Phase.SEARCH_MARKED;
                setDebugState(phase.name());
                yield null;
            }
            case SEARCH_MARKED -> searchMarked();
            case WITHDRAW_MARKED -> withdrawMarked();
            case CRAFT_FALLBACK -> craftFallback();
            case DONE, FAILED -> null;
        };
    }

    private Task searchMarked() {
        if (!checkpoint.candidatesFrozen) {
            checkpoint.freezeCandidates(discoverCandidates());
        }
        phase = Phase.WITHDRAW_MARKED;
        setDebugState(phase.name());
        return null;
    }

    private List<Candidate> discoverCandidates() {
        Vec3 origin = new Vec3(
                frozenCenter.getX() + 0.5,
                frozenCenter.getY() + 0.5,
                frozenCenter.getZ() + 0.5);
        List<Candidate> discovered = new ArrayList<>();
        Set<CandidateKey> seen = new HashSet<>();
        List<Item> items = FarmHoePolicy.markedStorageItems();
        for (int priority = 0; priority < items.size(); priority++) {
            Item item = items.get(priority);
            for (BlockPos position : MarkedChestToolLocator.candidateCoordinates(
                    controller,
                    item,
                    origin,
                    FarmPlotPolicy.HOE_MARKED_SEARCH_RADIUS,
                    dimensionId)) {
                CandidateKey key = new CandidateKey(position, item);
                if (seen.add(key)) {
                    discovered.add(new Candidate(position, item, priority));
                }
            }
        }
        discovered.sort(Comparator
                .comparingLong((Candidate c) -> distanceSquared(c.position(), frozenCenter))
                .thenComparingInt(Candidate::itemPriority)
                .thenComparingInt(c -> c.position().getX())
                .thenComparingInt(c -> c.position().getY())
                .thenComparingInt(c -> c.position().getZ()));
        return discovered;
    }

    private Task withdrawMarked() {
        if (withdrawChild != null) {
            if (!withdrawChild.isFinished() && !withdrawChild.stopped()) {
                return withdrawChild;
            }
            BoundedContainerTransferTask.Outcome transferOutcome = withdrawChild.outcome();
            boolean moved = transferOutcome == BoundedContainerTransferTask.Outcome.MOVED_ALL
                    || transferOutcome == BoundedContainerTransferTask.Outcome.MOVED_PARTIAL;
            if (transferOutcome != null) {
                checkpoint.completeTransferAttempt(moved);
            } else if (withdrawChild.stopped()) {
                checkpoint.retryStoppedTransfer();
            }
            withdrawChild = null;
            if (hasRequiredDurability()) {
                succeed(Outcome.ACQUIRED_FROM_MARKED_STORAGE);
                return null;
            }
            // Let Task detach the completed child before claiming another candidate.
            return null;
        }
        if (getSub() != null) {
            return null;
        }

        Candidate candidate = checkpoint.claimNextCandidate();
        if (candidate == null) {
            phase = Phase.CRAFT_FALLBACK;
            setDebugState(phase.name());
            return null;
        }

        List<StorageItemArgs.ItemQuery> query = List.of(new StorageItemArgs.ItemQuery(
                candidate.item(), ItemHelper.stripItemName(candidate.item()), 1));
        withdrawChild = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW,
                candidate.position(),
                query);
        setDebugState(phase.name() + "@" + candidate.position().toShortString());
        return withdrawChild;
    }

    private Task craftFallback() {
        if (checkpoint.fallbackTerminalFailure) {
            failUnavailable();
            return null;
        }
        if (craftChild != null) {
            if (!craftChild.isFinished() && !craftChild.stopped()) {
                return craftChild;
            }
            craftChild = null;
            if (hasRequiredDurability()) {
                succeed(Outcome.ACQUIRED_FROM_CRAFTING);
            } else {
                checkpoint.fallbackTerminalFailure = true;
                failUnavailable();
            }
            return null;
        }
        if (getSub() != null) {
            return null;
        }

        int target = checkpoint.fallbackTargetStoneHoeCount;
        if (target <= 0) {
            target = nextStoneHoeTarget(
                    controller.getItemStorage().getItemCount(Items.STONE_HOE));
            checkpoint.commitFallbackTarget(target);
        }
        try {
            craftChild = TaskCatalogue.getItemTask(Items.STONE_HOE, target);
        } catch (RuntimeException catalogueFailure) {
            checkpoint.fallbackTerminalFailure = true;
            failUnavailable();
            return null;
        }
        if (craftChild == null) {
            checkpoint.fallbackTerminalFailure = true;
            failUnavailable();
            return null;
        }
        return craftChild;
    }

    private boolean hasRequiredDurability() {
        return controller != null
                && FarmHoePolicy.hasDurability(controller.getInventory(), requiredDurability);
    }

    private Outcome observedSuccessSource() {
        if (checkpoint.fallbackCommitted()) {
            return Outcome.ACQUIRED_FROM_CRAFTING;
        }
        if (checkpoint.attemptedMarkedTransfers() > 0) {
            return Outcome.ACQUIRED_FROM_MARKED_STORAGE;
        }
        return Outcome.ACQUIRED_FROM_INVENTORY;
    }

    private void succeed(Outcome successfulOutcome) {
        outcome = successfulOutcome;
        reason = FarmTaskReason.NONE;
        phase = Phase.DONE;
        setDebugState(phase.name() + ":" + successfulOutcome.name());
    }

    private void failUnavailable() {
        outcome = Outcome.UNAVAILABLE;
        reason = FarmTaskReason.HOE_UNAVAILABLE;
        phase = Phase.FAILED;
        setDebugState(phase.name() + ":" + reason.controlledReason());
    }

    @Override
    protected void onStop(Task interruptTask) {
        // The setup root owns terminal-vs-transient classification. Candidate claims and the
        // fallback target already live in its checkpoint; a reconstructed child starts by
        // reconciling the live inventory receipt.
        withdrawChild = null;
        craftChild = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FarmHoeAcquisitionTask task
                && task.requiredDurability == requiredDurability
                && Objects.equals(task.frozenCenter, frozenCenter)
                && Objects.equals(task.dimensionId, dimensionId)
                && task.checkpoint == checkpoint;
    }

    @Override
    protected String toDebugString() {
        return "FarmHoeAcquisition[durability=" + requiredDurability + "]";
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public static int nextStoneHoeTarget(int currentStoneHoeCount) {
        if (currentStoneHoeCount < 0) {
            throw new IllegalArgumentException("current stone-hoe count cannot be negative");
        }
        return Math.addExact(currentStoneHoeCount, 1);
    }
}
