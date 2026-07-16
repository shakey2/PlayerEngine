package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.farming.FarmBreakToolRequirement;
import com.player2.playerengine.tasks.farming.FarmPlotPolicy;
import com.player2.playerengine.tasks.farming.FarmRepairToolPlan;
import com.player2.playerengine.tasks.farming.FarmTaskReason;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Checkpointed acquisition of every standard tool still required by one frozen farm-repair plan.
 *
 * <p>The global stage order is fixed: reconcile the live inventory, exhaust a frozen EllieGPS
 * marked-storage manifest, then use the catalogue crafting fallback for each requirement that is
 * still missing. Custom requirements are inventory-only; if one is unsatisfied this child returns a
 * typed unavailable outcome instead of guessing how to locate or craft a mod-specific item.
 *
 * <p>The logical setup root owns the {@link Checkpoint}. Reconstructing this child after a transient
 * scheduler interruption first reconciles live inventory, retries an in-flight chest transfer
 * without claiming another logical candidate, and reissues a persisted absolute crafting target.
 * Candidate coordinates remain task-local and are never included in player or model feedback.
 */
public final class FarmRepairToolAcquisitionTask extends Task {

    public enum Outcome {
        RUNNING,
        SATISFIED_FROM_INVENTORY,
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
     * Durable state for one logical repair-tool acquisition attempt. The checkpoint is deliberately
     * bound to the immutable plan identity and farm destination so it cannot be reused by another
     * setup operation after side effects begin.
     */
    public static final class Checkpoint {
        private String planIdentity;
        private BlockPos frozenCenter;
        private String dimensionId;
        private boolean candidatesFrozen;
        private List<Candidate> candidates = List.of();
        private int candidateCursor;
        private final Map<FarmBreakToolRequirement.Family, Integer> markedAttemptsByFamily =
                new LinkedHashMap<>();
        private final Map<CandidateKey, Integer> markedAttemptsByCandidate =
                new LinkedHashMap<>();
        private Candidate inFlightCandidate;
        private boolean transferAttemptInProgress;
        private final Map<FarmBreakToolRequirement.Family, CraftTarget> craftTargetsByFamily =
                new LinkedHashMap<>();
        private boolean fallbackTerminalFailure;
        private boolean markedTransferMoved;

        public Checkpoint() {
        }

        public boolean isBound() {
            return planIdentity != null;
        }

        public String planIdentity() {
            return planIdentity;
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

        public int attemptedMarkedTransfers(FarmBreakToolRequirement.Family family) {
            return family == null ? 0 : markedAttemptsByFamily.getOrDefault(family, 0);
        }

        public boolean fallbackCommitted() {
            return !craftTargetsByFamily.isEmpty();
        }

        private void bind(String identity, BlockPos center, String dimension) {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(dimension, "dimension");
            if (!isBound()) {
                planIdentity = identity;
                frozenCenter = center.immutable();
                dimensionId = dimension;
                return;
            }
            if (!Objects.equals(planIdentity, identity)
                    || !Objects.equals(frozenCenter, center)
                    || !Objects.equals(dimensionId, dimension)) {
                throw new IllegalArgumentException(
                        "farm repair tool checkpoint cannot be rebound to another plan");
            }
        }

        private void freezeCandidates(List<Candidate> discovered) {
            if (candidatesFrozen) {
                return;
            }
            candidates = List.copyOf(discovered);
            candidatesFrozen = true;
        }

        private Candidate claimNextCandidate(
                Set<FarmBreakToolRequirement.Family> missingFamilies) {
            if (!candidatesFrozen) {
                return null;
            }
            Objects.requireNonNull(missingFamilies, "missingFamilies");
            while (true) {
                if (inFlightCandidate != null) {
                    if (!missingFamilies.contains(inFlightCandidate.family())) {
                        inFlightCandidate = null;
                        transferAttemptInProgress = false;
                        continue;
                    }
                    if (transferAttemptInProgress) {
                        return inFlightCandidate;
                    }
                    int attempts = attemptedMarkedTransfers(inFlightCandidate.family());
                    CandidateKey key = keyOf(inFlightCandidate);
                    int candidateAttempts = markedAttemptsByCandidate.getOrDefault(key, 0);
                    if (attempts
                            >= FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_FAMILY
                            || candidateAttempts
                            >= FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_CANDIDATE) {
                        inFlightCandidate = null;
                        continue;
                    }
                    markedAttemptsByFamily.put(inFlightCandidate.family(), attempts + 1);
                    markedAttemptsByCandidate.put(key, candidateAttempts + 1);
                    transferAttemptInProgress = true;
                    return inFlightCandidate;
                }

                if (candidateCursor >= candidates.size()) {
                    return null;
                }
                Candidate candidate = candidates.get(candidateCursor++);
                if (!missingFamilies.contains(candidate.family())
                        || attemptedMarkedTransfers(candidate.family())
                        >= FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_FAMILY) {
                    continue;
                }
                inFlightCandidate = candidate;
            }
        }

        private void completeTransferAttempt(boolean moved) {
            transferAttemptInProgress = false;
            if (moved) {
                markedTransferMoved = true;
            }
            // A second bounded probe lets a healthy unstackable tool immediately behind a damaged
            // copy in the same chest be considered. After that, advance instead of draining one
            // chest until the family-wide budget is exhausted.
            if (!moved || inFlightCandidate == null
                    || markedAttemptsByCandidate.getOrDefault(keyOf(inFlightCandidate), 0)
                    >= FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_CANDIDATE) {
                inFlightCandidate = null;
            }
        }

        private static CandidateKey keyOf(Candidate candidate) {
            return new CandidateKey(candidate.family(), candidate.item(), candidate.chest());
        }

        private void retryStoppedTransfer() {
            transferAttemptInProgress = false;
        }

        private CraftTarget craftTarget(
                FarmBreakToolRequirement.Family family,
                Item item,
                int currentExactCount) {
            CraftTarget existing = craftTargetsByFamily.get(family);
            if (existing != null) {
                if (existing.item() != item) {
                    throw new IllegalArgumentException(
                            "farm repair fallback item changed for " + family);
                }
                return existing;
            }
            CraftTarget created = new CraftTarget(
                    item, Math.addExact(currentExactCount, 1));
            craftTargetsByFamily.put(family, created);
            return created;
        }
    }

    /** Frozen marked-storage tuple plus the bound requirement used for live receipt checks. */
    private record Candidate(
            FarmBreakToolRequirement.Family family,
            Item item,
            BlockPos chest,
            FarmRepairToolPlan.Requirement requirement,
            int requirementPriority,
            int itemPriority) {
        private Candidate {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(item, "item");
            chest = Objects.requireNonNull(chest, "chest").immutable();
            Objects.requireNonNull(requirement, "requirement");
        }
    }

    private record CandidateKey(
            FarmBreakToolRequirement.Family family,
            Item item,
            BlockPos chest) {
    }

    private record CraftTarget(Item item, int absoluteCount) {
        private CraftTarget {
            Objects.requireNonNull(item, "item");
            if (absoluteCount < 1) {
                throw new IllegalArgumentException("absolute craft target must be positive");
            }
        }
    }

    private final FarmRepairToolPlan plan;
    private final String planIdentity;
    private final BlockPos frozenCenter;
    private final String dimensionId;
    private final Checkpoint checkpoint;

    private Phase phase = Phase.CHECK_INVENTORY;
    private Outcome outcome = Outcome.RUNNING;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private BoundedContainerTransferTask withdrawChild;
    private FarmRepairToolPlan.Requirement activeCraftRequirement;
    private Task craftChild;

    public FarmRepairToolAcquisitionTask(
            FarmRepairToolPlan plan,
            BlockPos frozenCenter,
            String dimensionId,
            Checkpoint checkpoint) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.planIdentity = Objects.requireNonNull(plan.identity(), "plan.identity()");
        if (planIdentity.isBlank()) {
            throw new IllegalArgumentException("plan identity cannot be blank");
        }
        this.frozenCenter = Objects.requireNonNull(frozenCenter, "frozenCenter").immutable();
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }
        this.dimensionId = dimensionId;
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        this.checkpoint.bind(planIdentity, this.frozenCenter, dimensionId);
    }

    public FarmRepairToolAcquisitionTask(
            FarmRepairToolPlan plan,
            BlockPos frozenCenter,
            String dimensionId) {
        this(plan, frozenCenter, dimensionId, new Checkpoint());
    }

    @Override
    public boolean isFinished() {
        return outcome != Outcome.RUNNING;
    }

    public boolean isSuccessful() {
        return outcome == Outcome.SATISFIED_FROM_INVENTORY
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
        withdrawChild = null;
        activeCraftRequirement = null;
        craftChild = null;
        setDebugState(phase.name());
    }

    @Override
    protected Task onTick() {
        if (isFinished()) {
            return null;
        }

        LivingEntityInventory inventory = liveInventory();
        if (inventory == null) {
            failUnavailable();
            return null;
        }
        if (plan.isSatisfied(inventory)) {
            succeed(observedSuccessOutcome());
            return null;
        }
        if (plan.hasUnsatisfiedCustom(inventory)) {
            failUnavailable();
            return null;
        }

        return switch (phase) {
            case CHECK_INVENTORY -> {
                phase = checkpoint.candidatesFrozen
                        ? Phase.WITHDRAW_MARKED
                        : Phase.SEARCH_MARKED;
                setDebugState(phase.name());
                yield null;
            }
            case SEARCH_MARKED -> searchMarked(inventory);
            case WITHDRAW_MARKED -> withdrawMarked(inventory);
            case CRAFT_FALLBACK -> craftFallback(inventory);
            case DONE, FAILED -> null;
        };
    }

    private Task searchMarked(LivingEntityInventory inventory) {
        if (!checkpoint.candidatesFrozen) {
            List<FarmRepairToolPlan.Requirement> missing = missingStandardRequirements(inventory);
            if (missing == null) {
                failUnavailable();
                return null;
            }
            checkpoint.freezeCandidates(discoverCandidates(missing));
        }
        phase = Phase.WITHDRAW_MARKED;
        setDebugState(phase.name());
        return null;
    }

    private List<Candidate> discoverCandidates(
            List<FarmRepairToolPlan.Requirement> requirements) {
        Vec3 origin = new Vec3(
                frozenCenter.getX() + 0.5,
                frozenCenter.getY() + 0.5,
                frozenCenter.getZ() + 0.5);
        ArrayList<Candidate> discovered = new ArrayList<>();
        Set<CandidateKey> seen = new HashSet<>();
        for (int requirementPriority = 0;
                requirementPriority < requirements.size();
                requirementPriority++) {
            FarmRepairToolPlan.Requirement requirement = requirements.get(requirementPriority);
            if (requirement == null) {
                continue;
            }
            FarmBreakToolRequirement.Family family = requirement.family();
            List<Item> items = requirement.markedStorageItems();
            if (items == null) {
                continue;
            }
            for (int itemPriority = 0; itemPriority < items.size(); itemPriority++) {
                Item item = items.get(itemPriority);
                if (item == null) {
                    continue;
                }
                for (BlockPos chest : MarkedChestToolLocator.candidateCoordinates(
                        controller,
                        item,
                        origin,
                        FarmPlotPolicy.REPAIR_TOOL_MARKED_SEARCH_RADIUS,
                        dimensionId)) {
                    CandidateKey key = new CandidateKey(family, item, chest);
                    if (seen.add(key)) {
                        discovered.add(new Candidate(
                                family,
                                item,
                                chest,
                                requirement,
                                requirementPriority,
                                itemPriority));
                    }
                }
            }
        }
        discovered.sort(Comparator
                .comparingLong((Candidate candidate) ->
                        distanceSquared(candidate.chest(), frozenCenter))
                .thenComparingInt(Candidate::requirementPriority)
                .thenComparingInt(Candidate::itemPriority)
                .thenComparingInt(candidate -> candidate.chest().getX())
                .thenComparingInt(candidate -> candidate.chest().getY())
                .thenComparingInt(candidate -> candidate.chest().getZ())
                .thenComparing(Candidate::family));
        return discovered;
    }

    private Task withdrawMarked(LivingEntityInventory inventory) {
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
            if (plan.isSatisfied(inventory)) {
                succeed(Outcome.ACQUIRED_FROM_MARKED_STORAGE);
            }
            // Let Task detach the completed child before claiming or crafting more work.
            return null;
        }
        if (getSub() != null) {
            return null;
        }

        List<FarmRepairToolPlan.Requirement> missing = missingStandardRequirements(inventory);
        if (missing == null) {
            failUnavailable();
            return null;
        }
        Set<FarmBreakToolRequirement.Family> missingFamilies = new HashSet<>();
        for (FarmRepairToolPlan.Requirement requirement : missing) {
            if (requirement != null) {
                missingFamilies.add(requirement.family());
            }
        }
        Candidate candidate = checkpoint.claimNextCandidate(missingFamilies);
        if (candidate == null) {
            phase = Phase.CRAFT_FALLBACK;
            setDebugState(phase.name());
            return null;
        }

        List<StorageItemArgs.ItemQuery> query = List.of(new StorageItemArgs.ItemQuery(
                candidate.item(), ItemHelper.stripItemName(candidate.item()), 1));
        withdrawChild = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW,
                candidate.chest(),
                query);
        setDebugState(phase.name() + ":" + candidate.family().name());
        return withdrawChild;
    }

    private Task craftFallback(LivingEntityInventory inventory) {
        if (checkpoint.fallbackTerminalFailure) {
            failUnavailable();
            return null;
        }
        if (craftChild != null) {
            if (!craftChild.isFinished() && !craftChild.stopped()) {
                return craftChild;
            }
            craftChild = null;
            FarmBreakToolRequirement.Family completedFamily = activeCraftRequirement == null
                    ? null
                    : activeCraftRequirement.family();
            activeCraftRequirement = null;
            List<FarmRepairToolPlan.Requirement> missing =
                    missingStandardRequirements(inventory);
            boolean acquired = missing != null && completedFamily != null
                    && missing.stream().noneMatch(requirement ->
                    requirement != null && requirement.family() == completedFamily);
            if (!acquired) {
                checkpoint.fallbackTerminalFailure = true;
                failUnavailable();
            }
            // Let Task detach the completed child before issuing another fallback.
            return null;
        }
        if (getSub() != null) {
            return null;
        }

        List<FarmRepairToolPlan.Requirement> missing = missingStandardRequirements(inventory);
        if (missing == null) {
            failUnavailable();
            return null;
        }
        FarmRepairToolPlan.Requirement requirement = firstMissing(missing);
        if (requirement == null) {
            if (plan.isSatisfied(inventory)) {
                succeed(observedSuccessOutcome());
            } else {
                failUnavailable();
            }
            return null;
        }

        FarmBreakToolRequirement.Family family = requirement.family();
        Item fallback = requirement.craftFallbackItem();
        if (fallback == null) {
            checkpoint.fallbackTerminalFailure = true;
            failUnavailable();
            return null;
        }

        final CraftTarget target;
        try {
            target = checkpoint.craftTarget(
                    family,
                    fallback,
                    controller.getItemStorage().getItemCount(fallback));
            craftChild = TaskCatalogue.getItemTask(fallback, target.absoluteCount());
        } catch (RuntimeException invalidFallback) {
            checkpoint.fallbackTerminalFailure = true;
            failUnavailable();
            return null;
        }
        if (craftChild == null) {
            checkpoint.fallbackTerminalFailure = true;
            failUnavailable();
            return null;
        }
        activeCraftRequirement = requirement;
        setDebugState(phase.name() + ":" + family.name());
        return craftChild;
    }

    private List<FarmRepairToolPlan.Requirement> missingStandardRequirements(
            LivingEntityInventory inventory) {
        try {
            return plan.missingStandardRequirements(inventory);
        } catch (RuntimeException invalidPlan) {
            return null;
        }
    }

    private static FarmRepairToolPlan.Requirement firstMissing(
            List<FarmRepairToolPlan.Requirement> requirements) {
        for (FarmRepairToolPlan.Requirement requirement : requirements) {
            if (requirement != null) {
                return requirement;
            }
        }
        return null;
    }

    private LivingEntityInventory liveInventory() {
        return controller == null ? null : controller.getInventory();
    }

    private Outcome observedSuccessOutcome() {
        if (checkpoint.fallbackCommitted()) {
            return Outcome.ACQUIRED_FROM_CRAFTING;
        }
        if (checkpoint.markedTransferMoved) {
            return Outcome.ACQUIRED_FROM_MARKED_STORAGE;
        }
        return Outcome.SATISFIED_FROM_INVENTORY;
    }

    private void succeed(Outcome successfulOutcome) {
        outcome = successfulOutcome;
        reason = FarmTaskReason.NONE;
        phase = Phase.DONE;
        setDebugState(phase.name() + ":" + successfulOutcome.name());
    }

    private void failUnavailable() {
        outcome = Outcome.UNAVAILABLE;
        reason = FarmTaskReason.REPAIR_TOOL_UNAVAILABLE;
        phase = Phase.FAILED;
        setDebugState(phase.name() + ":" + reason.controlledReason());
    }

    @Override
    protected void onStop(Task interruptTask) {
        // The setup root owns terminal-vs-transient classification. Frozen candidates, the in-flight
        // logical attempt, and absolute crafting targets remain in its checkpoint; reconstructed
        // work begins by reconciling live inventory.
        if (withdrawChild != null && withdrawChild.outcome() != null) {
            BoundedContainerTransferTask.Outcome transferOutcome = withdrawChild.outcome();
            boolean moved = transferOutcome == BoundedContainerTransferTask.Outcome.MOVED_ALL
                    || transferOutcome == BoundedContainerTransferTask.Outcome.MOVED_PARTIAL;
            checkpoint.completeTransferAttempt(moved);
        }
        withdrawChild = null;
        activeCraftRequirement = null;
        craftChild = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FarmRepairToolAcquisitionTask task
                && Objects.equals(task.planIdentity, planIdentity)
                && Objects.equals(task.frozenCenter, frozenCenter)
                && Objects.equals(task.dimensionId, dimensionId)
                && task.checkpoint == checkpoint;
    }

    @Override
    protected String toDebugString() {
        return "FarmRepairToolAcquisition";
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
