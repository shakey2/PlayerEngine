package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
import com.player2.playerengine.agentic.elliegps.FarmPlantingRestrictionUpdate;
import com.player2.playerengine.agentic.elliegps.FarmWaypointObservation;
import com.player2.playerengine.agentic.elliegps.FarmWaypointService;
import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchOrder;
import com.player2.playerengine.agentic.elliegps.WaypointSearchResult;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskSuspensionCause;
import com.player2.playerengine.tasks.base.TrackedTaskOutcomeProvider;
import com.player2.playerengine.tasks.base.TransientlyResumableTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One finite, ordered, transiently resumable planting operation over one recorded 9x9 farm. */
public final class PlantFarmTask extends Task
        implements TrackedTaskOutcomeProvider, TransientlyResumableTask {
    public static final int WHOLE_TIMEOUT_TICKS = 24_000;
    private static final int TERMINAL_RESCAN_TRAVEL_GRACE_TICKS = 1_200;
    private static final int TRANSIENT_RECEIPT_SETTLE_TICKS =
            FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS;

    private final BlockPos commandAnchor;
    private final String commandDimension;
    private final List<FarmPlantingRequest> requests;
    private final BlockPos exactCenter;
    private final FarmPlantingPolicy policy;
    private final AgenticRunState runState;

    private final int[] plantedCounts;
    private final boolean[] acquisitionCompleted;
    private final FarmPlantingItemAcquisitionTask.Checkpoint[] acquisitionCheckpoints;

    private PlantFarmPhase phase = PlantFarmPhase.RESOLVE;
    private List<FarmPlantingItemResolver.Descriptor> descriptors = List.of();
    private List<WaypointRecord> candidates = List.of();
    private int candidateIndex;
    private WaypointRecord farm;
    private BlockPos center;
    private Direction exitEdge;
    private FarmCropScanner.ScanResult scan;
    private List<BlockPos> openTargets = List.of();
    private int targetIndex;
    private int requestIndex;
    private FarmPlantingItemAcquisitionTask acquisitionChild;
    private PlantFarmBlockTask mutationChild;
    private PlantFarmBlockTask pendingMutationReceipt;
    private int pendingReceiptTicks;

    private boolean resumeStartPending;
    private boolean destinationCommitted;
    private boolean mutationAttempted;
    private boolean terminal;
    private boolean successful;
    private boolean completionPending;
    private PlantFarmReason failureReason = PlantFarmReason.NONE;
    private PlantFarmReason pendingFailureReason = PlantFarmReason.NONE;
    private PlantFarmPhase pendingFailurePhase = PlantFarmPhase.FAILED;
    private PlantFarmOutcome terminalOutcome;
    private WaypointMutationResult waypointResult;
    private FarmWaypointObservation finalObservation;
    private int wholeTicks;
    private int openSlotsAfter = -1;
    private int liveCandidateScans;
    private int liveFullScans;
    private int liveRepairRejected;

    enum FailureTransition {
        RESCAN,
        TERMINAL
    }

    /** Pure lifecycle decisions shared by the live runner and deterministic root tests. */
    static boolean mayTryNextCandidate(
            boolean exactFarmRequested,
            boolean destinationCommitted,
            boolean mutationAttempted,
            boolean hasNextCandidate) {
        return !exactFarmRequested
                && !destinationCommitted
                && !mutationAttempted
                && hasNextCandidate;
    }

    static PlantFarmPhase resumePhase(
            boolean hasFrozenFarm,
            boolean completionPending,
            boolean failurePending) {
        if (!hasFrozenFarm) {
            return PlantFarmPhase.RESOLVE;
        }
        return completionPending || failurePending
                ? PlantFarmPhase.RESCAN
                : PlantFarmPhase.SCAN;
    }

    static FailureTransition failureTransition(
            boolean mutationAttempted,
            boolean frozenFarmKnown,
            PlantFarmPhase currentPhase,
            boolean sameDimension,
            boolean controllerAvailable) {
        Objects.requireNonNull(currentPhase, "currentPhase");
        return mutationAttempted
                && frozenFarmKnown
                && sameDimension
                && controllerAvailable
                && currentPhase != PlantFarmPhase.RESCAN
                && currentPhase != PlantFarmPhase.COMMIT
                ? FailureTransition.RESCAN
                : FailureTransition.TERMINAL;
    }

    static FailureTransition cancellationTransition(
            boolean mutationAttempted,
            boolean frozenFarmKnown,
            boolean sameDimension,
            boolean controllerAvailable) {
        // A terminal stop cannot wait in a partially completed RESCAN/COMMIT phase. Restart the
        // bounded reconciliation synchronously so cancellation observes the same receipt barrier.
        return failureTransition(
                mutationAttempted,
                frozenFarmKnown,
                PlantFarmPhase.PLANT,
                sameDimension,
                controllerAvailable);
    }

    static PlantFarmPhase phaseAfterObservedRescan() {
        return PlantFarmPhase.COMMIT;
    }

    public PlantFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            List<FarmPlantingRequest> requests,
            BlockPos exactCenter,
            FarmPlantingPolicy policy,
            AgenticRunState runState) {
        this.commandAnchor = Objects.requireNonNull(commandAnchor, "commandAnchor").immutable();
        String checkedDimension = Objects.requireNonNull(
                commandDimension, "commandDimension").strip();
        if (checkedDimension.isEmpty()) {
            throw new IllegalArgumentException("commandDimension cannot be blank");
        }
        this.commandDimension = checkedDimension;
        this.requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (this.requests.isEmpty()) {
            throw new IllegalArgumentException("at least one planting request is required");
        }
        int total = 0;
        for (FarmPlantingRequest request : this.requests) {
            total = Math.addExact(total, request.count());
        }
        if (total > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException("one planting operation cannot exceed 80 cells");
        }
        this.exactCenter = exactCenter == null ? null : exactCenter.immutable();
        this.policy = Objects.requireNonNull(policy, "policy");
        if (policy.kind() != FarmPlantingPolicy.Kind.PRESERVE && exactCenter == null) {
            throw new IllegalArgumentException("planting policy changes require an exact farm");
        }
        this.runState = runState;
        this.plantedCounts = new int[this.requests.size()];
        this.acquisitionCompleted = new boolean[this.requests.size()];
        this.acquisitionCheckpoints =
                new FarmPlantingItemAcquisitionTask.Checkpoint[this.requests.size()];
        for (int i = 0; i < this.acquisitionCheckpoints.length; i++) {
            this.acquisitionCheckpoints[i] = new FarmPlantingItemAcquisitionTask.Checkpoint();
        }
    }

    @Override
    protected void onStart() {
        boolean resuming = resumeStartPending;
        resumeStartPending = false;
        acquisitionChild = null;
        mutationChild = null;
        scan = null;
        openTargets = List.of();
        targetIndex = 0;
        if (!resuming) {
            phase = PlantFarmPhase.RESOLVE;
            descriptors = List.of();
            candidates = List.of();
            candidateIndex = 0;
            farm = null;
            center = exactCenter;
            exitEdge = null;
            requestIndex = 0;
            pendingMutationReceipt = null;
            pendingReceiptTicks = 0;
            destinationCommitted = false;
            mutationAttempted = false;
            terminal = false;
            successful = false;
            completionPending = false;
            failureReason = PlantFarmReason.NONE;
            pendingFailureReason = PlantFarmReason.NONE;
            pendingFailurePhase = PlantFarmPhase.FAILED;
            terminalOutcome = null;
            waypointResult = null;
            finalObservation = null;
            wholeTicks = 0;
            openSlotsAfter = -1;
            liveCandidateScans = 0;
            liveFullScans = 0;
            liveRepairRejected = 0;
        } else if (!terminal) {
            phase = resumePhase(
                    farm != null,
                    completionPending,
                    pendingFailureReason != PlantFarmReason.NONE);
            failureReason = PlantFarmReason.NONE;
            finalObservation = null;
        }
        updateProgress();
    }

    @Override
    protected Task onTick() {
        if (terminal) {
            return null;
        }
        wholeTicks++;
        if (pendingMutationReceipt != null) {
            mutationAttempted |= pendingMutationReceipt.hasIssuedMutation();
            if (!settleRetainedReceipt()) {
                updateProgress();
                return null;
            }
            if (terminal) {
                return null;
            }
        }
        if (wholeTicks >= WHOLE_TIMEOUT_TICKS) {
            if (phase == PlantFarmPhase.RESCAN || phase == PlantFarmPhase.COMMIT) {
                if (completionPending && pendingFailureReason == PlantFarmReason.NONE) {
                    completionPending = false;
                    pendingFailureReason = PlantFarmReason.OPERATION_TIMEOUT;
                    pendingFailurePhase = PlantFarmPhase.PLANT;
                }
            } else {
                requestFailure(PlantFarmReason.OPERATION_TIMEOUT);
                return null;
            }
        }
        if (!Objects.equals(commandDimension, currentDimension())) {
            requestFailure(PlantFarmReason.DIMENSION_CHANGED);
            return null;
        }
        if (!controller.getModSettings().getEllieGpsEnabled()) {
            requestFailure(PlantFarmReason.ELLIEGPS_DISABLED);
            return null;
        }
        if (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null) {
            requestFailure(PlantFarmReason.STORE_UNAVAILABLE);
            return null;
        }

        Task next = switch (phase) {
            case RESOLVE -> tickResolve();
            case TRAVEL -> tickTravel();
            case SCAN -> tickScan();
            case ACQUIRE -> tickAcquire();
            case PLANT -> tickPlant();
            case RESCAN -> tickRescan();
            case COMMIT -> tickCommit();
            case DONE, FAILED -> {
                requestFailure(PlantFarmReason.INTERNAL_CONTRACT_FAILURE);
                yield null;
            }
        };
        updateProgress();
        return next;
    }

    private Task tickResolve() {
        PlantFarmReason descriptorFailure = resolveDescriptors();
        if (descriptorFailure != PlantFarmReason.NONE) {
            requestFailure(descriptorFailure);
            return null;
        }
        EllieGPSStore store = EllieGPSStore.get();
        if (exactCenter != null) {
            WaypointRecord exact = store.byPosition(commandDimension, exactCenter);
            PlantFarmReason failure = FarmPlantingSelector.exactMetadataFailure(
                    exact, requests, policy);
            if (failure != PlantFarmReason.NONE) {
                requestFailure(failure);
                return null;
            }
            candidates = List.of(exact.copy());
        } else {
            WaypointSearchResult search = WaypointSearchService.find(
                    "",
                    commandDimension,
                    Set.of(WaypointTypes.FARM),
                    false,
                    WaypointSearchOrder.NEAREST,
                    commandAnchor,
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            PlantFarmReason searchFailure = searchFailure(search.status());
            if (searchFailure != PlantFarmReason.NONE) {
                requestFailure(searchFailure);
                return null;
            }
            FarmPlantingSelector.Selection selection = FarmPlantingSelector.automatic(
                    search.records(), commandDimension, commandAnchor, requests);
            if (!selection.successful()) {
                requestFailure(selection.failure());
                return null;
            }
            candidates = selection.candidates();
        }
        candidateIndex = 0;
        freezeCandidate(candidates.get(0));
        phase = PlantFarmPhase.TRAVEL;
        return null;
    }

    private Task tickTravel() {
        FarmCropScanner.ScanResult observed = FarmCropScanner.scan(controller.getWorld(), farm);
        FarmObservationStatus status = observed.observationResult().status();
        if (status == FarmObservationStatus.UNLOADED) {
            setDebugState("Travelling to recorded farm " + center.toShortString());
            return new GetToBlockTask(approachStance());
        }
        if (status != FarmObservationStatus.OBSERVED) {
            rejectCandidateOrFail(observationFailure(status));
            return null;
        }
        scan = observed;
        phase = PlantFarmPhase.SCAN;
        return null;
    }

    private Task tickScan() {
        FarmCropScanner.ScanResult current = scan;
        scan = null;
        if (current == null) {
            current = FarmCropScanner.scan(controller.getWorld(), farm);
        }
        FarmObservationStatus status = current.observationResult().status();
        if (status == FarmObservationStatus.UNLOADED) {
            phase = PlantFarmPhase.TRAVEL;
            return null;
        }
        if (status != FarmObservationStatus.OBSERVED) {
            rejectCandidateOrFail(observationFailure(status));
            return null;
        }
        liveCandidateScans++;
        FarmWaypointObservation observation = current.observationResult().observation();
        if (observation.farmlandCount() != FarmPlotPolicy.SOIL_CELL_COUNT
                || current.openCount() + current.recognizedCount()
                != FarmPlotPolicy.SOIL_CELL_COUNT) {
            liveRepairRejected++;
            rejectCandidateOrFail(PlantFarmReason.FARM_NEEDS_REPAIR);
            return null;
        }
        int remaining = remainingTotal();
        if (current.openCount() == 0) {
            liveFullScans++;
            rejectCandidateOrFail(exactCenter == null
                    ? PlantFarmReason.ALL_RECORDED_FARMS_FULL
                    : PlantFarmReason.EXACT_FARM_FULL);
            return null;
        }
        if (current.openCount() < remaining) {
            rejectCandidateOrFail(PlantFarmReason.INSUFFICIENT_OPEN_SLOTS);
            return null;
        }

        WaypointMutationResult refreshed;
        try {
            refreshed = FarmWaypointService.registerOrRefresh(
                    observation, Objects.toString(farm.origin, WaypointRecord.ORIGIN_BOT_PLACED));
        } catch (RuntimeException failedRefresh) {
            requestFailure(PlantFarmReason.WAYPOINT_JSON_FAILURE);
            return null;
        }
        waypointResult = refreshed;
        if (!mutationCommitted(refreshed.status())) {
            requestFailure(refreshFailure(refreshed.status()));
            return null;
        }
        if (refreshed.current() != null) {
            farm = refreshed.current().copy();
        }
        destinationCommitted = true;

        if (requestIndex >= requests.size()) {
            completionPending = true;
            phase = PlantFarmPhase.RESCAN;
            return null;
        }
        FarmPlantingRequest request = requests.get(requestIndex);
        int requestRemaining = request.count() - plantedCounts[requestIndex];
        if (requestRemaining <= 0) {
            requestIndex++;
            phase = PlantFarmPhase.SCAN;
            return null;
        }
        openTargets = FarmPlantingOrder.retreatOrder(
                center, current.openCells(), exitEdge);
        targetIndex = 0;
        int held = inventoryCount(descriptors.get(requestIndex).item());
        if (held >= requestRemaining) {
            acquisitionCompleted[requestIndex] = true;
            phase = PlantFarmPhase.PLANT;
        } else if (acquisitionCompleted[requestIndex]) {
            // A higher-priority food task may legitimately consume carrot/potato stock while this
            // root is suspended. Start a new bounded acquisition receipt for the now-smaller
            // remaining request; the root's cumulative deadline still prevents an infinite loop.
            acquisitionCompleted[requestIndex] = false;
            acquisitionCheckpoints[requestIndex] =
                    new FarmPlantingItemAcquisitionTask.Checkpoint();
            phase = PlantFarmPhase.ACQUIRE;
        } else {
            phase = PlantFarmPhase.ACQUIRE;
        }
        return null;
    }

    private Task tickAcquire() {
        if (requestIndex >= requests.size()) {
            requestFailure(PlantFarmReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        int remaining = requests.get(requestIndex).count() - plantedCounts[requestIndex];
        Item item = descriptors.get(requestIndex).item();
        if (inventoryCount(item) >= remaining) {
            acquisitionCompleted[requestIndex] = true;
            acquisitionChild = null;
            // Acquisition may have taken the NPC far from the frozen farm or allowed intervening
            // world changes. Return through the authoritative whole-footprint scan before planting.
            phase = PlantFarmPhase.SCAN;
            return null;
        }
        if (acquisitionChild == null) {
            acquisitionChild = new FarmPlantingItemAcquisitionTask(
                    item,
                    remaining,
                    center,
                    commandDimension,
                    acquisitionCheckpoints[requestIndex]);
            return acquisitionChild;
        }
        if (!acquisitionChild.isFinished()) {
            return acquisitionChild;
        }
        if (acquisitionChild.isSuccessful() && inventoryCount(item) >= remaining) {
            acquisitionCompleted[requestIndex] = true;
            acquisitionChild = null;
            phase = PlantFarmPhase.SCAN;
            return null;
        }
        PlantFarmReason failure = switch (acquisitionChild.outcome()) {
            case DEADLINE_EXCEEDED -> PlantFarmReason.ACQUISITION_TIMEOUT;
            case DIMENSION_CHANGED -> PlantFarmReason.DIMENSION_CHANGED;
            case WORLD_GUARDS_UNAVAILABLE -> PlantFarmReason.STORE_UNAVAILABLE;
            case SOURCE_EXHAUSTED, SOURCE_DISCOVERY_FAILED ->
                    PlantFarmReason.PLANTABLE_UNAVAILABLE;
            case CANCELLED -> PlantFarmReason.CANCELLED_OPERATOR;
            case RUNNING, ACQUIRED -> PlantFarmReason.INTERNAL_CONTRACT_FAILURE;
        };
        acquisitionChild = null;
        requestFailure(failure);
        return null;
    }

    private Task tickPlant() {
        if (requestIndex >= requests.size()) {
            completionPending = true;
            phase = PlantFarmPhase.RESCAN;
            return null;
        }
        FarmPlantingRequest request = requests.get(requestIndex);
        if (plantedCounts[requestIndex] >= request.count()) {
            requestIndex++;
            phase = PlantFarmPhase.SCAN;
            return null;
        }
        if (mutationChild != null) {
            if (!mutationChild.isFinished()) {
                return mutationChild;
            }
            PlantFarmBlockTask completed = mutationChild;
            mutationChild = null;
            mutationAttempted |= completed.hasIssuedMutation();
            if (completed.isSuccessful()) {
                accountPlantingReceipt(completed);
                return null;
            }
            if (!completed.hasIssuedMutation()
                    && completed.reason() == FarmTaskReason.MANIFEST_DRIFT) {
                phase = PlantFarmPhase.SCAN;
                return null;
            }
            requestFailure(mapMutationFailure(completed.reason(), completed.hasIssuedMutation()));
            return null;
        }
        if (targetIndex >= openTargets.size()) {
            requestFailure(PlantFarmReason.INSUFFICIENT_OPEN_SLOTS);
            return null;
        }
        BlockPos target = openTargets.get(targetIndex++);
        if (!controller.getWorld().getBlockState(target).isAir()
                || !controller.getWorld().getBlockState(target.below())
                .is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
            phase = PlantFarmPhase.SCAN;
            return null;
        }
        BlockPos stance = chooseStance(target);
        if (stance == null) {
            requestFailure(PlantFarmReason.NO_SAFE_STANCE);
            return null;
        }
        mutationChild = new PlantFarmBlockTask(
                target, stance, descriptors.get(requestIndex));
        return mutationChild;
    }

    private Task tickRescan() {
        FarmCropScanner.ScanResult current = FarmCropScanner.scan(controller.getWorld(), farm);
        FarmObservationStatus status = current.observationResult().status();
        if (status == FarmObservationStatus.UNLOADED
                && wholeTicks < WHOLE_TIMEOUT_TICKS + TERMINAL_RESCAN_TRAVEL_GRACE_TICKS) {
            setDebugState("Returning to frozen farm for terminal metadata reconciliation");
            return new GetToBlockTask(approachStance());
        }
        if (status != FarmObservationStatus.OBSERVED) {
            finalizeFailure(observationFailure(status), PlantFarmPhase.RESCAN);
            return null;
        }
        finalObservation = current.observationResult().observation();
        openSlotsAfter = current.openCount();
        phase = phaseAfterObservedRescan();
        return null;
    }

    private Task tickCommit() {
        if (finalObservation == null) {
            finalizeFailure(PlantFarmReason.INTERNAL_CONTRACT_FAILURE, PlantFarmPhase.COMMIT);
            return null;
        }
        FarmPlantingRestrictionUpdate restriction = completionPending
                ? restrictionUpdate(policy)
                : FarmPlantingRestrictionUpdate.preserve();
        try {
            waypointResult = FarmWaypointService.registerOrRefresh(
                    finalObservation,
                    Objects.toString(farm.origin, WaypointRecord.ORIGIN_BOT_PLACED),
                    restriction);
        } catch (RuntimeException failedCommit) {
            finalizeFailure(PlantFarmReason.WAYPOINT_JSON_FAILURE, PlantFarmPhase.COMMIT);
            return null;
        }
        if (!mutationCommitted(waypointResult.status())) {
            finalizeFailure(refreshFailure(waypointResult.status()), PlantFarmPhase.COMMIT);
            return null;
        }
        if (completionPending) {
            succeed();
        } else {
            PlantFarmReason reason = pendingFailureReason == PlantFarmReason.NONE
                    ? PlantFarmReason.INTERNAL_CONTRACT_FAILURE
                    : pendingFailureReason;
            PlantFarmPhase failurePhase = pendingFailureReason == PlantFarmReason.NONE
                    ? PlantFarmPhase.COMMIT
                    : pendingFailurePhase;
            finalizeFailure(reason, failurePhase);
        }
        return null;
    }

    private PlantFarmReason resolveDescriptors() {
        if (!descriptors.isEmpty()) {
            return PlantFarmReason.NONE;
        }
        ArrayList<FarmPlantingItemResolver.Descriptor> resolved =
                new ArrayList<>(requests.size());
        for (FarmPlantingRequest request : requests) {
            ResourceLocation id = ResourceLocation.tryParse(request.plantingItemId());
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            FarmPlantingItemResolver.Result result = FarmPlantingItemResolver.resolve(item);
            if (!result.supported()) {
                return result.status() == FarmPlantingItemResolver.Status.EXCLUDED_STEM
                        ? PlantFarmReason.STEM_CROP_EXCLUDED
                        : PlantFarmReason.UNSUPPORTED_PLANTABLE;
            }
            if (!result.descriptor().plantingItemId().toString()
                    .equals(request.plantingItemId())) {
                return PlantFarmReason.UNSUPPORTED_PLANTABLE;
            }
            resolved.add(result.descriptor());
        }
        descriptors = List.copyOf(resolved);
        return PlantFarmReason.NONE;
    }

    private void freezeCandidate(WaypointRecord candidate) {
        farm = Objects.requireNonNull(candidate, "candidate").copy();
        center = Objects.requireNonNull(farm.canonicalBlockPos(), "farm center").immutable();
        exitEdge = FarmPlantingOrder.selectExitEdge(center, commandAnchor);
        scan = null;
        openTargets = List.of();
        targetIndex = 0;
    }

    private void rejectCandidateOrFail(PlantFarmReason reason) {
        boolean hasNextCandidate = candidateIndex + 1 < candidates.size();
        if (!mayTryNextCandidate(
                exactCenter != null,
                destinationCommitted,
                mutationAttempted,
                hasNextCandidate)) {
            PlantFarmReason terminalReason = reason;
            if (exactCenter == null && !mutationAttempted && !hasNextCandidate) {
                if (liveCandidateScans > 0 && liveFullScans == liveCandidateScans) {
                    terminalReason = PlantFarmReason.ALL_RECORDED_FARMS_FULL;
                } else if (liveCandidateScans > 0 && liveRepairRejected == liveCandidateScans) {
                    terminalReason = PlantFarmReason.FARM_NEEDS_REPAIR;
                }
            }
            requestFailure(terminalReason);
            return;
        }
        candidateIndex++;
        freezeCandidate(candidates.get(candidateIndex));
        phase = PlantFarmPhase.TRAVEL;
    }

    private BlockPos approachStance() {
        return center.relative(exitEdge, FarmPlotPolicy.HYDRATION_RADIUS + 1)
                .above().immutable();
    }

    private BlockPos chooseStance(BlockPos target) {
        for (BlockPos candidate : FarmPlotGeometry.stanceCandidates(center, target)) {
            if (FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void accountPlantingReceipt(PlantFarmBlockTask receipt) {
        if (requestIndex >= requests.size()
                || receipt.descriptor().item() != descriptors.get(requestIndex).item()
                || plantedCounts[requestIndex] >= requests.get(requestIndex).count()) {
            requestFailure(PlantFarmReason.INTERNAL_CONTRACT_FAILURE);
            return;
        }
        plantedCounts[requestIndex]++;
        mutationAttempted = true;
    }

    private boolean settleRetainedReceipt() {
        PlantFarmBlockTask retained = pendingMutationReceipt;
        if (!retained.settlePendingTransition()) {
            pendingMutationReceipt = null;
            requestFailure(mapMutationFailure(retained.reason(), retained.hasIssuedMutation()));
            return false;
        }
        if (retained.isFinished()) {
            pendingMutationReceipt = null;
            pendingReceiptTicks = 0;
            if (retained.isSuccessful()) {
                accountPlantingReceipt(retained);
            } else {
                requestFailure(mapMutationFailure(retained.reason(), retained.hasIssuedMutation()));
            }
            return true;
        }
        pendingReceiptTicks++;
        if (pendingReceiptTicks <= TRANSIENT_RECEIPT_SETTLE_TICKS) {
            setDebugState("Settling planting receipt after interruption");
            return false;
        }
        pendingMutationReceipt = null;
        pendingReceiptTicks = 0;
        phase = PlantFarmPhase.SCAN;
        return true;
    }

    private int remainingTotal() {
        int remaining = 0;
        for (int i = 0; i < requests.size(); i++) {
            remaining = Math.addExact(
                    remaining, requests.get(i).count() - plantedCounts[i]);
        }
        return remaining;
    }

    private int inventoryCount(Item item) {
        return controller.getItemStorage().getItemCountInventoryOnly(item);
    }

    private void requestFailure(PlantFarmReason reason) {
        if (terminal) {
            return;
        }
        PlantFarmReason checked = Objects.requireNonNull(reason, "reason");
        PlantFarmPhase failedDuring = phase == PlantFarmPhase.DONE
                ? PlantFarmPhase.FAILED
                : phase;
        boolean mayHaveMutatedFarm = mutationAttempted
                || (pendingMutationReceipt != null
                && pendingMutationReceipt.hasIssuedMutation())
                || (mutationChild != null && mutationChild.hasIssuedMutation());
        mutationAttempted |= mayHaveMutatedFarm;
        boolean controllerAvailable = controller != null;
        boolean frozenFarmKnown = farm != null && center != null;
        boolean sameDimension = mayHaveMutatedFarm
                && frozenFarmKnown
                && controllerAvailable
                && Objects.equals(commandDimension, currentDimension());
        if (failureTransition(
                mayHaveMutatedFarm,
                frozenFarmKnown,
                phase,
                sameDimension,
                controllerAvailable) == FailureTransition.RESCAN) {
            completionPending = false;
            pendingFailureReason = checked;
            pendingFailurePhase = failedDuring;
            phase = PlantFarmPhase.RESCAN;
            return;
        }
        finalizeFailure(checked, failedDuring);
    }

    private void succeed() {
        if (terminal) {
            return;
        }
        terminal = true;
        successful = true;
        failureReason = PlantFarmReason.NONE;
        phase = PlantFarmPhase.DONE;
        terminalOutcome = new PlantFarmOutcome(
                true, true, PlantFarmReason.NONE, PlantFarmPhase.DONE,
                commandDimension, center, requests, plantedSnapshot(),
                openSlotsAfter, waypointResult);
        updateProgress();
    }

    private void finalizeFailure(PlantFarmReason reason, PlantFarmPhase failedDuring) {
        if (terminal) {
            return;
        }
        terminal = true;
        successful = false;
        failureReason = Objects.requireNonNull(reason, "reason");
        PlantFarmPhase outcomePhase = failedDuring == PlantFarmPhase.DONE
                ? PlantFarmPhase.FAILED
                : failedDuring;
        phase = outcomePhase;
        terminalOutcome = new PlantFarmOutcome(
                true, false, failureReason, outcomePhase,
                commandDimension, center, requests, plantedSnapshot(),
                openSlotsAfter, waypointResult);
        updateProgress();
    }

    private List<FarmPlantingRequest> plantedSnapshot() {
        ArrayList<FarmPlantingRequest> result = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            if (plantedCounts[i] > 0) {
                result.add(new FarmPlantingRequest(
                        requests.get(i).plantingItemId(), plantedCounts[i]));
            }
        }
        return List.copyOf(result);
    }

    private void terminalizeCancellation() {
        if (terminal) {
            return;
        }
        PlantFarmPhase cancelledDuring = phase == PlantFarmPhase.DONE
                ? PlantFarmPhase.FAILED
                : phase;
        settleActiveReceiptForTerminal();
        if (terminal) {
            return;
        }
        PlantFarmReason terminalReason = PlantFarmReason.CANCELLED_OPERATOR;
        boolean controllerAvailable = controller != null;
        boolean frozenFarmKnown = farm != null && center != null;
        boolean sameDimension = mutationAttempted
                && frozenFarmKnown
                && controllerAvailable
                && Objects.equals(commandDimension, currentDimension());
        FailureTransition transition = cancellationTransition(
                mutationAttempted,
                frozenFarmKnown,
                sameDimension,
                controllerAvailable);
        if (mutationAttempted && (!controllerAvailable || farm == null || center == null)) {
            terminalReason = PlantFarmReason.STORE_UNAVAILABLE;
        } else if (mutationAttempted && !sameDimension) {
            terminalReason = PlantFarmReason.DIMENSION_CHANGED;
        } else if (transition == FailureTransition.RESCAN) {
            phase = PlantFarmPhase.RESCAN;
            FarmCropScanner.ScanResult current = FarmCropScanner.scan(controller.getWorld(), farm);
            if (current.observationResult().status() == FarmObservationStatus.OBSERVED) {
                finalObservation = current.observationResult().observation();
                openSlotsAfter = current.openCount();
                phase = phaseAfterObservedRescan();
                try {
                    waypointResult = FarmWaypointService.registerOrRefresh(
                            finalObservation,
                            Objects.toString(farm.origin, WaypointRecord.ORIGIN_BOT_PLACED));
                    if (!mutationCommitted(waypointResult.status())) {
                        terminalReason = refreshFailure(waypointResult.status());
                    }
                } catch (RuntimeException failedCommit) {
                    waypointResult = null;
                    terminalReason = PlantFarmReason.WAYPOINT_JSON_FAILURE;
                }
            } else {
                terminalReason = observationFailure(current.observationResult().status());
            }
        }
        finalizeFailure(terminalReason, cancelledDuring);
    }

    private void settleActiveReceiptForTerminal() {
        PlantFarmBlockTask active = pendingMutationReceipt != null
                ? pendingMutationReceipt
                : mutationChild;
        pendingMutationReceipt = null;
        mutationChild = null;
        if (active == null || !active.hasIssuedMutation()) {
            return;
        }
        mutationAttempted = true;
        if (active.settlePendingTransition() && active.isFinished() && active.isSuccessful()) {
            accountPlantingReceipt(active);
        }
    }

    @Override
    public boolean prepareForTransientResume(TaskSuspensionCause cause) {
        Objects.requireNonNull(cause, "cause");
        if (terminal) {
            return false;
        }
        // The acquisition child may have crossed its success terminal one tick before the parent
        // could consume that result. Preserve that fact so food consumption during the overlay
        // causes a fresh bounded acquisition checkpoint instead of rebinding an ACQUIRED receipt.
        if (acquisitionChild != null && requestIndex < acquisitionCompleted.length
                && acquisitionChild.isFinished() && acquisitionChild.isSuccessful()) {
            acquisitionCompleted[requestIndex] = true;
        }
        if (mutationChild != null) {
            mutationAttempted |= mutationChild.hasIssuedMutation();
            if (!mutationChild.settlePendingTransition()) {
                PlantFarmReason childFailure = mapMutationFailure(
                        mutationChild.reason(), mutationChild.hasIssuedMutation());
                requestFailure(childFailure);
                resumeStartPending = !terminal;
                quiesce();
                return !terminal;
            }
            if (mutationChild.isFinished()) {
                if (mutationChild.isSuccessful()) {
                    accountPlantingReceipt(mutationChild);
                } else {
                    PlantFarmReason childFailure = mapMutationFailure(
                            mutationChild.reason(), mutationChild.hasIssuedMutation());
                    requestFailure(childFailure);
                    resumeStartPending = !terminal;
                    quiesce();
                    return !terminal;
                }
            } else if (mutationChild.hasIssuedMutation()) {
                mutationChild.prepareForTransientDetach();
                pendingMutationReceipt = mutationChild;
                pendingReceiptTicks = 0;
            }
        }
        resumeStartPending = true;
        quiesce();
        return true;
    }

    @Override
    public void onTransientResumeAbandoned() {
        resumeStartPending = false;
        terminalizeCancellation();
        quiesce();
    }

    @Override
    public void afterChildrenStopped() {
        acquisitionChild = null;
        mutationChild = null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        resumeStartPending = false;
        terminalizeCancellation();
        quiesce();
    }

    private void quiesce() {
        if (controller != null) {
            controller.getInputControls().release(
                    com.player2.playerengine.automaton.api.utils.input.Input.CLICK_LEFT);
            controller.getInputControls().release(
                    com.player2.playerengine.automaton.api.utils.input.Input.CLICK_RIGHT);
            controller.getBaritone().getPathingBehavior().forceCancel();
            if (controller.getBaritone().getCustomGoalProcess().isActive()) {
                controller.getBaritone().getCustomGoalProcess().onLostControl();
            }
        }
    }

    private void updateProgress() {
        if (runState != null && !runState.isTerminal()) {
            runState.setFarmProgress(describeProgress());
        }
    }

    public String describeProgress() {
        if (terminal && !successful) {
            return "farm planting failed: " + failureReason.controlledReason();
        }
        return switch (phase) {
            case RESOLVE -> "resolving a compatible recorded farm";
            case TRAVEL -> "travelling to the selected farm";
            case SCAN -> "checking live farm capacity";
            case ACQUIRE -> "acquiring planting items for crop "
                    + Math.min(requestIndex + 1, requests.size()) + "/" + requests.size();
            case PLANT -> "planting crop " + Math.min(requestIndex + 1, requests.size())
                    + "/" + requests.size() + " (" + plantedTotal() + "/" + requestedTotal() + ")";
            case RESCAN -> "rescanning planted farm";
            case COMMIT -> "saving exact farm crop and capacity metadata";
            case DONE -> "farm planting complete";
            case FAILED -> "farm planting failed";
        };
    }

    private int plantedTotal() {
        int total = 0;
        for (int value : plantedCounts) {
            total = Math.addExact(total, value);
        }
        return total;
    }

    private int requestedTotal() {
        int total = 0;
        for (FarmPlantingRequest request : requests) {
            total = Math.addExact(total, request.count());
        }
        return total;
    }

    private String currentDimension() {
        return controller.getWorld().dimension().location().toString();
    }

    private static PlantFarmReason mapMutationFailure(
            FarmTaskReason reason, boolean issuedMutation) {
        if (reason == FarmTaskReason.CHUNK_UNLOADED) {
            return PlantFarmReason.CHUNK_UNLOADED;
        }
        if (reason == FarmTaskReason.DIMENSION_CHANGED) {
            return PlantFarmReason.DIMENSION_CHANGED;
        }
        if (reason == FarmTaskReason.STORE_UNAVAILABLE) {
            return PlantFarmReason.STORE_UNAVAILABLE;
        }
        if (reason == FarmTaskReason.CANCELLED_OPERATOR) {
            return PlantFarmReason.CANCELLED_OPERATOR;
        }
        if (issuedMutation && reason == FarmTaskReason.MANIFEST_DRIFT) {
            return PlantFarmReason.RECEIPT_MISMATCH;
        }
        return reason == FarmTaskReason.INTERACTION_DENIED
                ? PlantFarmReason.INTERACTION_DENIED
                : PlantFarmReason.FARM_NEEDS_REPAIR;
    }

    private static PlantFarmReason observationFailure(FarmObservationStatus status) {
        return switch (status) {
            case OBSERVED -> PlantFarmReason.NONE;
            case UNLOADED -> PlantFarmReason.CHUNK_UNLOADED;
            case STALE_CENTER -> PlantFarmReason.STALE_FARM;
            case UNSUPPORTED_DATA -> PlantFarmReason.UNSUPPORTED_FARM_DATA;
            case HANDLER_UNAVAILABLE -> PlantFarmReason.STORE_UNAVAILABLE;
        };
    }

    private static PlantFarmReason searchFailure(WaypointSearchStatus status) {
        return switch (status) {
            case INDEXED, AUTHORITATIVE_SPATIAL, FULL_STORE_FALLBACK -> PlantFarmReason.NONE;
            case FAILED_STORE_UNAVAILABLE -> PlantFarmReason.STORE_UNAVAILABLE;
            case FAILED_SCAN_LIMIT -> PlantFarmReason.SEARCH_SCAN_LIMIT;
            case FAILED_SEARCH_ERROR -> PlantFarmReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    private static PlantFarmReason refreshFailure(WaypointMutationStatus status) {
        return switch (status) {
            case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT -> PlantFarmReason.TYPE_CONFLICT;
            case FAILED_STORE_UNAVAILABLE -> PlantFarmReason.STORE_UNAVAILABLE;
            case FAILED_JSON_COMMIT -> PlantFarmReason.WAYPOINT_JSON_FAILURE;
            default -> PlantFarmReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    private static boolean mutationCommitted(WaypointMutationStatus status) {
        return status == WaypointMutationStatus.COMMITTED
                || status == WaypointMutationStatus.COMMITTED_INDEX_DEGRADED
                || status == WaypointMutationStatus.NO_CHANGE
                || status == WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED;
    }

    private static FarmPlantingRestrictionUpdate restrictionUpdate(FarmPlantingPolicy policy) {
        return switch (policy.kind()) {
            case PRESERVE -> FarmPlantingRestrictionUpdate.preserve();
            case CLEAR -> FarmPlantingRestrictionUpdate.clear();
            case SET -> FarmPlantingRestrictionUpdate.set(policy.plantingItemId());
        };
    }

    @Override
    public boolean isFinished() {
        return terminal;
    }

    @Override
    public boolean isTerminal() {
        return terminal;
    }

    @Override
    public boolean isSuccessful() {
        return terminal && successful;
    }

    @Override
    public String controlledReason() {
        return failureReason.controlledReason();
    }

    public PlantFarmOutcome outcome() {
        if (terminalOutcome != null) {
            return terminalOutcome;
        }
        return new PlantFarmOutcome(
                false, false, PlantFarmReason.NONE, phase,
                commandDimension, center, requests, plantedSnapshot(),
                openSlotsAfter, waypointResult);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlantFarmTask task
                && task.commandAnchor.equals(commandAnchor)
                && task.commandDimension.equals(commandDimension)
                && task.requests.equals(requests)
                && Objects.equals(task.exactCenter, exactCenter)
                && task.policy.equals(policy);
    }

    @Override
    protected String toDebugString() {
        return exactCenter == null
                ? "Plant ordered crops at nearest compatible farm"
                : "Plant ordered crops at farm " + exactCenter.toShortString();
    }
}
