package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
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
import com.player2.playerengine.tasks.agentic.FarmHoeAcquisitionTask;
import com.player2.playerengine.tasks.agentic.FarmRepairToolAcquisitionTask;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskSuspensionCause;
import com.player2.playerengine.tasks.base.TrackedTaskOutcomeProvider;
import com.player2.playerengine.tasks.base.TransientlyResumableTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Finite, tracked composition of deterministic site selection and verified physical farm setup. */
public final class SetupFarmTask extends Task
        implements TrackedTaskOutcomeProvider, TransientlyResumableTask {

    public record Progress(
            FarmTaskPhase phase,
            BlockPos center,
            int cleared,
            int clearTotal,
            int filled,
            int fillTotal,
            int tilled,
            int tillTotal,
            int resourceTicks,
            boolean waterRequired) {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            if (center != null) {
                center = center.immutable();
            }
        }
    }

    enum ResourceStage {
        ENSURE_BUILD_DIRT,
        ENSURE_BUCKET,
        ACQUIRE_WATER,
        ENSURE_HOE,
        ENSURE_REPAIR_TOOLS,
        ENSURE_REPAIR_DIRT,
        REACQUIRE_HOE,
        COMPLETE
    }

    private enum ResourcePurpose {
        PREPARE_SITE,
        COMPLETE_SETUP,
        REPLACE_HOE,
        REPAIR_PREFLIGHT
    }

    private enum ResourceReturnMode {
        NONE,
        CONTINUE_SETUP,
        FINALIZE_FAILURE
    }

    enum RecordedResumeStatus {
        NONE,
        SELECTED,
        UNLOADED
    }

    enum ExactCenterStatus {
        NEW_FORCED,
        RECORDED,
        UNLOADED,
        TYPE_CONFLICT,
        UNSUPPORTED_DATA
    }

    record RecordedResumeResolution(
            RecordedResumeStatus status,
            String id,
            String dimension,
            BlockPos center) {
        RecordedResumeResolution {
            Objects.requireNonNull(status, "status");
            if (status == RecordedResumeStatus.SELECTED) {
                if (id == null || id.isBlank() || dimension == null || dimension.isBlank()) {
                    throw new IllegalArgumentException("selected recorded farm identity is required");
                }
                center = Objects.requireNonNull(center, "center").immutable();
            } else if (id != null || dimension != null || center != null) {
                throw new IllegalArgumentException(
                        "non-selected recorded farm cannot carry an identity");
            }
        }

        static RecordedResumeResolution none() {
            return new RecordedResumeResolution(RecordedResumeStatus.NONE, null, null, null);
        }

        static RecordedResumeResolution unloaded() {
            return new RecordedResumeResolution(RecordedResumeStatus.UNLOADED, null, null, null);
        }

        static RecordedResumeResolution selected(WaypointRecord record, BlockPos center) {
            return new RecordedResumeResolution(
                    RecordedResumeStatus.SELECTED,
                    record.id,
                    record.dimension,
                    center);
        }
    }

    record ExactCenterResolution(
            ExactCenterStatus status,
            RecordedResumeResolution recorded) {
        ExactCenterResolution {
            Objects.requireNonNull(status, "status");
            if ((status == ExactCenterStatus.RECORDED) != (recorded != null)) {
                throw new IllegalArgumentException(
                        "only an exact recorded farm may carry a durable identity");
            }
        }

        static ExactCenterResolution status(ExactCenterStatus status) {
            return new ExactCenterResolution(status, null);
        }

        static ExactCenterResolution recorded(WaypointRecord record, BlockPos center) {
            return new ExactCenterResolution(
                    ExactCenterStatus.RECORDED,
                    RecordedResumeResolution.selected(record, center));
        }
    }

    private record RecordedFarmCandidate(
            WaypointRecord record,
            BlockPos center,
            long spatialDistanceSquared) {
        private RecordedFarmCandidate {
            Objects.requireNonNull(record, "record");
            center = Objects.requireNonNull(center, "center").immutable();
        }
    }

    /** Stable task-local handle captured from the accepted prepared EllieGPS mutation. */
    private record PreparedFarmHandle(String id, String dimension, BlockPos center) {
        private PreparedFarmHandle {
            if (id == null || id.isBlank() || dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("prepared farm identity cannot be blank");
            }
            center = Objects.requireNonNull(center, "center").immutable();
        }
    }

    private final BlockPos commandAnchor;
    private final String commandDimension;
    private final BlockPos exactCenter;
    private final AgenticRunState runState;
    private FarmTaskWatchdog watchdog = new FarmTaskWatchdog();

    private FarmTaskPhase phase = FarmTaskPhase.PRECHECK;
    private FarmSiteWorldView worldView;
    private FarmSitePlanner sitePlanner;
    private FarmSitePlan sitePlan;
    private ResourceStage resourceStage;
    private ResourcePurpose resourcePurpose;
    private Task resourceChild;
    private Task mutationChild;
    private Task pendingMutationReceipt;
    private int pendingMutationReceiptTicks;
    private boolean acquisitionProtectionPushed;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason failureReason = FarmTaskReason.NONE;
    private FarmTaskOutcome terminalOutcome;
    private WaypointMutationResult waypointResult;
    private FarmWaypointObservation verifiedObservation;
    private boolean commitAttempted;
    private boolean preparedCommitAttempted;
    private PreparedFarmHandle preparedFarmHandle;
    private boolean sourcePlaced;
    private boolean resumeTillAfterResources;
    private int resourceTicks;
    private int clearIndex;
    private int fillIndex;
    private int tillIndex;
    private int cleared;
    private int filled;
    private int tilled;
    private int clearTotal;
    private int fillTotal;
    private int tillTotal;
    private FarmPlotRepairPlan repairPlan;
    private int repairBreakIndex;
    private int repairFillIndex;
    private int repairMutationPasses;
    private int repairResourceTrips;
    private int requiredRepairDirt;
    private FarmRepairToolPlan requiredRepairTools;
    private List<BlockPos> activeTillTargets = List.of();
    private boolean tillPlanReady;
    private boolean resumeStartPending;
    private BlockPos resumeCenter;
    private ResourceReturnMode resourceReturnMode = ResourceReturnMode.NONE;
    private BlockPos resourceReturnStance;
    private List<BlockPos> resourceReturnCandidates = List.of();
    private int resourceReturnCandidateIndex;
    private int resourceReturnCandidateTicks;
    private Task resourceReturnChild;
    private int resourceReturnTicks;
    private FarmTaskReason pendingResourceFailure = FarmTaskReason.NONE;
    private FarmReturnStatus resourceReturnStatus = FarmReturnStatus.NOT_REQUIRED;
    private FarmHoeAcquisitionTask.Checkpoint initialHoeCheckpoint =
            new FarmHoeAcquisitionTask.Checkpoint();
    private FarmHoeAcquisitionTask.Checkpoint replacementHoeCheckpoint =
            new FarmHoeAcquisitionTask.Checkpoint();
    private FarmRepairToolAcquisitionTask.Checkpoint repairToolCheckpoint =
            new FarmRepairToolAcquisitionTask.Checkpoint();

    public SetupFarmTask(BlockPos commandAnchor, String commandDimension) {
        this(commandAnchor, commandDimension, null, null);
    }

    public SetupFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            BlockPos exactCenter) {
        this(commandAnchor, commandDimension, exactCenter, null);
    }

    public SetupFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            BlockPos exactCenter,
            AgenticRunState runState) {
        this.commandAnchor = Objects.requireNonNull(commandAnchor, "commandAnchor").immutable();
        String checkedDimension = Objects.requireNonNull(commandDimension, "commandDimension").strip();
        if (checkedDimension.isEmpty()) {
            throw new IllegalArgumentException("commandDimension cannot be blank");
        }
        this.commandDimension = checkedDimension;
        this.exactCenter = exactCenter == null ? null : exactCenter.immutable();
        this.runState = runState;
    }

    @Override
    protected void onStart() {
        boolean resuming = resumeStartPending;
        FarmTaskPhase interruptedPhase = phase;
        boolean resumingReturn = resuming
                && resourceReturnMode != ResourceReturnMode.NONE
                && sitePlan != null;
        boolean resumingPreparedResources = resuming
                && !resumingReturn
                && preparedFarmHandle != null
                && sitePlan != null
                && interruptedPhase == FarmTaskPhase.ACQUIRE_RESOURCES
                && resourceStage != null
                && resourcePurpose != null;
        boolean resumingPreparedCheckpoint = resuming
                && !resumingReturn
                && !resumingPreparedResources
                && preparedFarmHandle != null
                && sitePlan != null;
        boolean restoringPreparedState = resumingReturn
                || resumingPreparedResources
                || resumingPreparedCheckpoint;
        resumeStartPending = false;
        if (!resuming) {
            watchdog = new FarmTaskWatchdog();
            resumeCenter = null;
            sourcePlaced = false;
            cleared = 0;
            filled = 0;
            tilled = 0;
            clearTotal = 0;
            fillTotal = 0;
            tillTotal = 0;
            resourceTicks = 0;
            repairMutationPasses = 0;
            repairResourceTrips = 0;
            requiredRepairDirt = 0;
            requiredRepairTools = null;
            preparedFarmHandle = null;
            waypointResult = null;
            pendingMutationReceipt = null;
            pendingMutationReceiptTicks = 0;
            resourceReturnMode = ResourceReturnMode.NONE;
            resourceReturnStance = null;
            resourceReturnChild = null;
            resourceReturnTicks = 0;
            pendingResourceFailure = FarmTaskReason.NONE;
            resourceReturnStatus = FarmReturnStatus.NOT_REQUIRED;
            resourceReturnCandidates = List.of();
            resourceReturnCandidateIndex = 0;
            resourceReturnCandidateTicks = 0;
            initialHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            replacementHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
        }
        phase = restoringPreparedState
                ? FarmTaskPhase.ACQUIRE_RESOURCES
                : FarmTaskPhase.PRECHECK;
        terminal = false;
        successful = false;
        failureReason = FarmTaskReason.NONE;
        terminalOutcome = null;
        verifiedObservation = null;
        commitAttempted = false;
        preparedCommitAttempted = false;
        if (!resuming) {
            resumeTillAfterResources = false;
        }
        clearIndex = 0;
        fillIndex = 0;
        tillIndex = 0;
        repairPlan = null;
        repairBreakIndex = 0;
        repairFillIndex = 0;
        activeTillTargets = List.of();
        tillPlanReady = false;
        worldView = null;
        if (restoringPreparedState) {
            try {
                worldView = FarmSiteWorldView.live(controller.getWorld());
            } catch (RuntimeException unavailableWorldView) {
                if (pendingResourceFailure == FarmTaskReason.NONE) {
                    pendingResourceFailure = FarmTaskReason.STORE_UNAVAILABLE;
                    resourceReturnMode = ResourceReturnMode.FINALIZE_FAILURE;
                }
            }
        }
        sitePlanner = null;
        if (!restoringPreparedState) {
            sitePlan = null;
            resourceStage = null;
            resourcePurpose = null;
        }
        resourceChild = null;
        mutationChild = null;
        acquisitionProtectionPushed = false;
        resourceReturnChild = null;
        if (restoringPreparedState) {
            protectSiteForAcquisition();
        }
        if (resumingPreparedCheckpoint) {
            resourcePurpose = ResourcePurpose.COMPLETE_SETUP;
            beginResourceReturn(ResourceReturnMode.CONTINUE_SETUP, FarmTaskReason.NONE);
        }
        if (resuming) {
            watchdog.restartFromCheckpoint();
        } else {
            watchdog.reset();
        }
        updateProgress();
    }

    @Override
    protected Task onTick() {
        if (terminal) {
            return null;
        }
        watchdog.tick();
        // A detached mutation receipt belongs to the old physical action, not to whichever phase
        // the resumable root reconstructs next. Settle it before resource-return or any fresh child
        // can run, so a late transition is accounted exactly once and can never be mistaken for a
        // later repair action.
        if (pendingMutationReceipt != null
                && !settleRetainedMutationReceiptOnResume()) {
            updateProgress();
            return null;
        }
        if (resourceReturnMode != ResourceReturnMode.NONE) {
            if (!Objects.equals(commandDimension, currentDimension())) {
                resourceReturnStatus = FarmReturnStatus.UNAVAILABLE;
                FarmTaskReason reason = pendingResourceFailure == FarmTaskReason.NONE
                        ? FarmTaskReason.DIMENSION_CHANGED
                        : pendingResourceFailure;
                finalizeFailure(reason);
                updateProgress();
                return null;
            }
            Task next = tickResourceReturn();
            updateProgress();
            return next;
        }
        if (!Objects.equals(commandDimension, currentDimension())) {
            fail(FarmTaskReason.DIMENSION_CHANGED);
            return null;
        }
        if (phase != FarmTaskPhase.PRECHECK
                && !controller.getModSettings().getEllieGpsEnabled()) {
            fail(FarmTaskReason.ELLIEGPS_DISABLED);
            return null;
        }
        if (phase != FarmTaskPhase.PRECHECK
                && (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null)) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return null;
        }
        if (preparedFarmHandle != null
                && requiresPreparedWaypoint(phase)
                && resolvePreparedFarmWaypoint() == null) {
            fail(FarmTaskReason.FARM_NOT_FOUND);
            return null;
        }
        if (watchdog.wholeSetupExpired()) {
            fail(FarmTaskReason.SETUP_TIMEOUT);
            return null;
        }

        Task next = switch (phase) {
            case PRECHECK -> tickPrecheck();
            case SELECT_SITE -> tickSiteSelection();
            case ACQUIRE_RESOURCES -> tickResources();
            case REVALIDATE -> tickRevalidation();
            case CLEAR -> tickClear(false);
            case FILL -> tickFill();
            case OPEN_CENTER -> tickClear(true);
            case REGISTER_PREPARED -> tickRegisterPrepared();
            case REPAIR -> tickRepair();
            case PLACE_WATER -> tickPlaceWater();
            case TILL -> tickTill();
            case VERIFY -> tickVerify();
            case COMMIT -> tickCommit();
            case DONE, FAILED,
                    HARVEST_RESOLVE, HARVEST_SCAN, HARVEST_BREAK,
                    HARVEST_PICKUP, HARVEST_RESCAN -> {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                yield null;
            }
        };
        updateProgress();
        return next;
    }

    private Task tickPrecheck() {
        if (!controller.getModSettings().getEllieGpsEnabled()) {
            fail(FarmTaskReason.ELLIEGPS_DISABLED);
            return null;
        }
        if (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return null;
        }
        try {
            FarmTaskReason environmentFailure = waterEnvironmentFailure(
                    FarmSiteWorldView.live(controller.getWorld()).observeWorld(commandAnchor));
            if (environmentFailure != FarmTaskReason.NONE) {
                fail(environmentFailure);
                return null;
            }
        } catch (RuntimeException unavailable) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return null;
        }
        if (exactCenter != null && resumeCenter == null) {
            return beginExactCenter();
        }
        if (shouldResolveRecordedResume(exactCenter, resumeCenter)) {
            WaypointSearchResult search = WaypointSearchService.find(
                    "",
                    commandDimension,
                    Set.of(WaypointTypes.FARM),
                    true,
                    WaypointSearchOrder.NEAREST,
                    commandAnchor,
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            FarmTaskReason searchFailure = recordedResumeSearchFailure(search.status());
            if (searchFailure != FarmTaskReason.NONE) {
                fail(searchFailure);
                return null;
            }
            final RecordedResumeResolution recorded;
            try {
                recorded = chooseRecordedResume(
                        search.records(),
                        commandDimension,
                        commandAnchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        this::farmEnvelopeLoaded);
            } catch (RuntimeException invalidRecord) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
            if (recorded.status() == RecordedResumeStatus.UNLOADED) {
                // A known unfinished farm must not silently create a second plot merely because
                // its complete repair envelope is not currently loaded.
                fail(FarmTaskReason.CHUNK_UNLOADED);
                return null;
            }
            if (recorded.status() == RecordedResumeStatus.SELECTED) {
                return beginRecordedResume(recorded);
            }
        }
        BlockPos planningCenter = resumeCenter == null ? exactCenter : resumeCenter;
        if (planningCenter != null) {
            if (!farmEnvelopeLoaded(planningCenter)) {
                fail(FarmTaskReason.CHUNK_UNLOADED);
                return null;
            }
        }
        try {
            worldView = FarmSiteWorldView.live(controller.getWorld());
            if (resumeCenter != null) {
                sitePlanner = FarmSitePlanner.resume(worldView, commandAnchor, resumeCenter);
            } else if (planningCenter == null) {
                sitePlanner = FarmSitePlanner.auto(worldView, resolveAutoPlanningAnchor());
            } else {
                sitePlanner = FarmSitePlanner.explicit(worldView, commandAnchor, planningCenter);
            }
        } catch (RuntimeException viewFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        enterPhase(FarmTaskPhase.SELECT_SITE);
        return null;
    }

    private Task beginExactCenter() {
        final WaypointRecord positionOccupant;
        final WaypointRecord canonicalIdOccupant;
        try {
            positionOccupant = EllieGPSStore.get().byPosition(commandDimension, exactCenter);
            canonicalIdOccupant = EllieGPSStore.get().byId(
                    WaypointRecord.idFor(commandDimension, exactCenter));
        } catch (RuntimeException lookupFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        ExactCenterResolution resolution = resolveExactCenter(
                positionOccupant,
                canonicalIdOccupant,
                commandDimension,
                exactCenter,
                farmEnvelopeLoaded(exactCenter));
        return switch (resolution.status()) {
            case RECORDED -> beginRecordedResume(resolution.recorded(), true);
            case NEW_FORCED -> beginForcedRepair(exactCenter);
            case UNLOADED -> {
                fail(FarmTaskReason.CHUNK_UNLOADED);
                yield null;
            }
            case TYPE_CONFLICT -> {
                fail(FarmTaskReason.TYPE_CONFLICT);
                yield null;
            }
            case UNSUPPORTED_DATA -> {
                fail(FarmTaskReason.UNSUPPORTED_DATA);
                yield null;
            }
        };
    }

    static ExactCenterResolution resolveExactCenter(
            WaypointRecord occupant,
            String dimension,
            BlockPos center,
            boolean envelopeLoaded) {
        return resolveExactCenter(occupant, occupant, dimension, center, envelopeLoaded);
    }

    static ExactCenterResolution resolveExactCenter(
            WaypointRecord positionOccupant,
            WaypointRecord canonicalIdOccupant,
            String dimension,
            BlockPos center,
            boolean envelopeLoaded) {
        String requiredDimension = Objects.requireNonNull(dimension, "dimension").strip();
        BlockPos requiredCenter = Objects.requireNonNull(center, "center").immutable();
        if (requiredDimension.isEmpty()) {
            throw new IllegalArgumentException("dimension cannot be blank");
        }
        if ((positionOccupant != null && !WaypointTypes.FARM.equals(positionOccupant.type))
                || (canonicalIdOccupant != null
                && !WaypointTypes.FARM.equals(canonicalIdOccupant.type))) {
            return ExactCenterResolution.status(ExactCenterStatus.TYPE_CONFLICT);
        }
        if (positionOccupant != null || canonicalIdOccupant != null) {
            if (positionOccupant == null
                    || canonicalIdOccupant == null
                    || !sameWaypointIdentity(canonicalIdOccupant, positionOccupant)) {
                // A one-sided canonical-ID/position record is malformed authoritative data. Forced
                // registration must never overwrite it merely because one lookup cannot see it.
                return ExactCenterResolution.status(ExactCenterStatus.UNSUPPORTED_DATA);
            }
            WaypointRecord occupant = canonicalIdOccupant;
            if (!WaypointTypes.FARM.equals(occupant.type)) {
                return ExactCenterResolution.status(ExactCenterStatus.TYPE_CONFLICT);
            }
            final FarmWaypointData data;
            final BlockPos storedCenter;
            try {
                data = occupant.farmData();
                storedCenter = occupant.canonicalBlockPos();
            } catch (RuntimeException malformed) {
                return ExactCenterResolution.status(ExactCenterStatus.UNSUPPORTED_DATA);
            }
            if (occupant.schemaVersion != WaypointRecord.WAYPOINT_SCHEMA_VERSION
                    || occupant.pos == null
                    || occupant.pos.length != 3
                    || !requiredDimension.equals(occupant.dimension)
                    || !requiredCenter.equals(storedCenter)
                    || occupant.id == null
                    || !occupant.id.equals(WaypointRecord.idFor(requiredDimension, requiredCenter))
                    || data == null
                    || !data.isSupportedVersion()
                    || data.radius() != FarmPlotPolicy.HYDRATION_RADIUS) {
                return ExactCenterResolution.status(ExactCenterStatus.UNSUPPORTED_DATA);
            }
            return envelopeLoaded
                    ? ExactCenterResolution.recorded(occupant, requiredCenter)
                    : ExactCenterResolution.status(ExactCenterStatus.UNLOADED);
        }
        return ExactCenterResolution.status(envelopeLoaded
                ? ExactCenterStatus.NEW_FORCED
                : ExactCenterStatus.UNLOADED);
    }

    static boolean shouldResolveRecordedResume(BlockPos explicitCenter, BlockPos transientCenter) {
        return explicitCenter == null && transientCenter == null;
    }

    static FarmTaskReason recordedResumeSearchFailure(WaypointSearchStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case INDEXED, AUTHORITATIVE_SPATIAL, FULL_STORE_FALLBACK -> FarmTaskReason.NONE;
            case FAILED_STORE_UNAVAILABLE -> FarmTaskReason.STORE_UNAVAILABLE;
            case FAILED_SCAN_LIMIT -> FarmTaskReason.SEARCH_SCAN_LIMIT;
            case FAILED_SEARCH_ERROR -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    static RecordedResumeResolution chooseRecordedResume(
            List<WaypointRecord> records,
            String dimension,
            BlockPos anchor,
            int searchRadius,
            Predicate<BlockPos> footprintLoaded) {
        Objects.requireNonNull(records, "records");
        String requiredDimension = Objects.requireNonNull(dimension, "dimension").strip();
        if (requiredDimension.isEmpty()) {
            throw new IllegalArgumentException("dimension cannot be blank");
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(footprintLoaded, "footprintLoaded");
        if (searchRadius < 0) {
            throw new IllegalArgumentException("searchRadius cannot be negative");
        }

        long maximumDistanceSquared = (long) searchRadius * searchRadius;
        ArrayList<RecordedFarmCandidate> candidates = new ArrayList<>();
        for (WaypointRecord record : records) {
            if (record == null
                    || !record.stale
                    || record.schemaVersion != WaypointRecord.WAYPOINT_SCHEMA_VERSION
                    || record.id == null
                    || record.id.isBlank()
                    || !WaypointTypes.FARM.equals(record.type)
                    || !requiredDimension.equals(record.dimension)
                    || record.pos == null
                    || record.pos.length != 3) {
                continue;
            }
            final FarmWaypointData data;
            final BlockPos center;
            try {
                data = record.farmData();
                center = record.canonicalBlockPos();
            } catch (RuntimeException malformed) {
                continue;
            }
            if (data == null
                    || !data.isSupportedVersion()
                    || data.radius() != FarmPlotPolicy.HYDRATION_RADIUS
                    || center == null
                    || !record.id.equals(WaypointRecord.idFor(requiredDimension, center))) {
                continue;
            }
            long horizontalDistanceSquared =
                    recordedFarmHorizontalDistanceSquared(anchor, center);
            if (horizontalDistanceSquared <= maximumDistanceSquared) {
                candidates.add(new RecordedFarmCandidate(
                        record, center, recordedFarmDistanceSquared(anchor, center)));
            }
        }
        candidates.sort(Comparator
                .comparingLong(RecordedFarmCandidate::spatialDistanceSquared)
                .thenComparing(candidate -> candidate.record().id));
        for (RecordedFarmCandidate candidate : candidates) {
            if (footprintLoaded.test(candidate.center())) {
                return RecordedResumeResolution.selected(
                        candidate.record(), candidate.center());
            }
        }
        return candidates.isEmpty()
                ? RecordedResumeResolution.none()
                : RecordedResumeResolution.unloaded();
    }

    static long recordedFarmDistanceSquared(BlockPos anchor, BlockPos center) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(center, "center");
        long horizontal = recordedFarmHorizontalDistanceSquared(anchor, center);
        if (horizontal == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long dy = (long) center.getY() - anchor.getY();
        long vertical = saturatedSquare(dy);
        return Long.MAX_VALUE - horizontal < vertical
                ? Long.MAX_VALUE
                : horizontal + vertical;
    }

    private static long recordedFarmHorizontalDistanceSquared(
            BlockPos anchor,
            BlockPos center) {
        long dx = (long) center.getX() - anchor.getX();
        long dz = (long) center.getZ() - anchor.getZ();
        return saturatedAdd(saturatedSquare(dx), saturatedSquare(dz));
    }

    private static long saturatedSquare(long value) {
        return value > 3_037_000_499L || value < -3_037_000_499L
                ? Long.MAX_VALUE
                : value * value;
    }

    private static long saturatedAdd(long left, long right) {
        return left == Long.MAX_VALUE
                || right == Long.MAX_VALUE
                || Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }

    private Task beginRecordedResume(RecordedResumeResolution recorded) {
        return beginRecordedResume(recorded, false);
    }

    private Task beginRecordedResume(
            RecordedResumeResolution recorded,
            boolean refreshPreparedRecord) {
        BlockPos center = recorded.center();
        preparedFarmHandle = new PreparedFarmHandle(
                recorded.id(), recorded.dimension(), center);
        if (resolvePreparedFarmWaypointAtExactPosition() == null) {
            fail(FarmTaskReason.FARM_NOT_FOUND);
            return null;
        }
        final FarmSiteWorldView liveView;
        final FarmPlotRepairPlan.ScanResult currentShape;
        final FarmSitePlan continuationPlan;
        try {
            liveView = FarmSiteWorldView.live(controller.getWorld());
            FarmTaskReason worldFailure = recordedResumeSafetyFailure(
                    liveView.observeWorld(center), false);
            if (worldFailure != FarmTaskReason.NONE) {
                fail(worldFailure);
                return null;
            }
            FarmTaskReason orphanFailure = retireOrphanedFarmProtection(center);
            if (orphanFailure != FarmTaskReason.NONE) {
                fail(orphanFailure);
                return null;
            }
            currentShape = FarmPlotRepairPlan.scan(liveView, center);
            if (!currentShape.ready()) {
                fail(currentShape.reason() == FarmPlotRepairPlan.UnsafeReason.UNREADABLE
                        ? FarmTaskReason.CHUNK_UNLOADED
                        : FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            continuationPlan = buildRecordedResumeContext(
                    liveView, center, currentShape.plan());
            FarmTaskReason safetyFailure = recordedResumeSafetyFailure(
                    continuationPlan.snapshot().worldObservation(),
                    FarmFreezePolicy.wouldFreeze(continuationPlan.snapshot()));
            if (safetyFailure != FarmTaskReason.NONE) {
                fail(safetyFailure);
                return null;
            }
        } catch (RuntimeException unavailable) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }

        if (refreshPreparedRecord) {
            FarmTaskReason registrationFailure = registerPreparedCheckpoint(center);
            if (registrationFailure != FarmTaskReason.NONE) {
                fail(registrationFailure);
                return null;
            }
        }

        return enterRepairContext(center, liveView, continuationPlan);
    }

    private Task beginForcedRepair(BlockPos center) {
        final FarmSiteWorldView liveView;
        final FarmPlotRepairPlan.ScanResult currentShape;
        final FarmSitePlan continuationPlan;
        try {
            liveView = FarmSiteWorldView.live(controller.getWorld());
            FarmTaskReason worldFailure = recordedResumeSafetyFailure(
                    liveView.observeWorld(center), false);
            if (worldFailure != FarmTaskReason.NONE) {
                fail(worldFailure);
                return null;
            }
            currentShape = FarmPlotRepairPlan.scan(liveView, center);
            if (!currentShape.ready()) {
                fail(exactRepairFailureReason(currentShape.reason()));
                return null;
            }
            continuationPlan = buildRecordedResumeContext(
                    liveView, center, currentShape.plan());
            FarmTaskReason safetyFailure = recordedResumeSafetyFailure(
                    continuationPlan.snapshot().worldObservation(),
                    FarmFreezePolicy.wouldFreeze(continuationPlan.snapshot()));
            if (safetyFailure != FarmTaskReason.NONE) {
                fail(exactWorldSafetyFailure(safetyFailure));
                return null;
            }
        } catch (RuntimeException unavailable) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }

        // Explicit player authority bypasses only new-site fitness. The exact plot is made durable
        // before any block or inventory mutation, then joins the same bounded repair state machine.
        FarmTaskReason registrationFailure = registerPreparedCheckpoint(center);
        if (registrationFailure != FarmTaskReason.NONE) {
            fail(registrationFailure);
            return null;
        }
        FarmTaskReason orphanFailure = retireOrphanedFarmProtection(center);
        if (orphanFailure != FarmTaskReason.NONE) {
            fail(orphanFailure);
            return null;
        }

        return enterRepairContext(center, liveView, continuationPlan);
    }

    /** Pure, bounded mapping for a rejected player-selected fixed farm footprint. */
    static FarmTaskReason exactRepairFailureReason(
            FarmPlotRepairPlan.UnsafeReason unsafeReason) {
        if (unsafeReason == null) {
            return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        return switch (unsafeReason) {
            case UNREADABLE -> FarmTaskReason.CHUNK_UNLOADED;
            case UNSAFE_SUPPORT -> FarmTaskReason.EXACT_SITE_UNSAFE_SUPPORT;
            case UNSAFE_OBSTRUCTION -> FarmTaskReason.EXACT_SITE_UNSAFE_OBSTRUCTION;
            case NON_CENTER_FLUID -> FarmTaskReason.EXACT_SITE_FLUID_CONFLICT;
            case CROP_CONFLICT -> FarmTaskReason.EXACT_SITE_CROP_CONFLICT;
            case REPAIR_LIMIT_EXCEEDED ->
                    FarmTaskReason.EXACT_SITE_REPAIR_LIMIT_EXCEEDED;
            case NO_RETURN_STANCE, NO_ACTION_STANCE ->
                    FarmTaskReason.EXACT_SITE_NO_SAFE_STANCE;
            case NONE -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    private Task enterRepairContext(
            BlockPos center,
            FarmSiteWorldView liveView,
            FarmSitePlan continuationPlan) {

        worldView = liveView;
        resumeCenter = center.immutable();
        sitePlan = continuationPlan;
        sitePlanner = null;
        repairPlan = null;
        repairBreakIndex = 0;
        repairFillIndex = 0;
        activeTillTargets = List.of();
        tillPlanReady = false;
        clearTotal = cleared;
        fillTotal = filled;
        tillTotal = tilled;
        watchdog.markProgress();
        // The exact prepared waypoint is now the durable lifecycle boundary. Bypass new-site
        // construction and continue from live 405-cell repair facts plus the 605-cell snapshot.
        enterPhase(FarmTaskPhase.REPAIR);
        return null;
    }

    static FarmTaskReason recordedResumeSafetyFailure(
            FarmSiteWorldView.WorldObservation world,
            boolean freezeRisk) {
        Objects.requireNonNull(world, "world");
        if (!world.protectionStoreAvailable() || !world.waypointStoreAvailable()) {
            return FarmTaskReason.STORE_UNAVAILABLE;
        }
        if (world.waypointCollision()) {
            return FarmTaskReason.TYPE_CONFLICT;
        }
        FarmTaskReason environmentFailure = waterEnvironmentFailure(world);
        if (environmentFailure != FarmTaskReason.NONE) {
            return environmentFailure;
        }
        return freezeRisk ? FarmTaskReason.FREEZE_RISK : FarmTaskReason.NONE;
    }

    /** Universal preflight for a water-backed farm, including coordinate-less setup. */
    static FarmTaskReason waterEnvironmentFailure(FarmSiteWorldView.WorldObservation world) {
        Objects.requireNonNull(world, "world");
        return world.ultraWarm() ? FarmTaskReason.WATER_EVAPORATES : FarmTaskReason.NONE;
    }

    /** Invocation-aware mapping: only a newly forced exact site gets exact retry guidance. */
    static FarmTaskReason exactWorldSafetyFailure(FarmTaskReason reason) {
        return reason == FarmTaskReason.FREEZE_RISK
                ? FarmTaskReason.EXACT_SITE_FREEZE_RISK
                : Objects.requireNonNull(reason, "reason");
    }

    static boolean shouldRetireOrphanedFarmProtection(
            boolean protectedPosition,
            boolean airBlock) {
        return protectedPosition && airBlock;
    }

    private FarmTaskReason retireOrphanedFarmProtection(BlockPos center) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        if (store == null) {
            return FarmTaskReason.STORE_UNAVAILABLE;
        }
        for (BlockPos position : FarmPlotRepairPlan.scanEnvelope(center)) {
            if (!controller.getChunkTracker().isChunkLoaded(position)) {
                return FarmTaskReason.CHUNK_UNLOADED;
            }
            boolean protectedPosition = store.contains(commandDimension, position);
            if (shouldRetireOrphanedFarmProtection(
                    protectedPosition,
                    protectedPosition
                            && controller.getWorld().getBlockState(position).isAir())) {
                // A placement marker whose exact world cell is now air cannot protect a live
                // player structure. This 405-cell repair also migrates markers orphaned by older
                // bot-break builds or by a lost receipt across restart.
                store.remove(commandDimension, position);
            }
        }
        return FarmTaskReason.NONE;
    }

    private FarmSitePlan buildRecordedResumeContext(
            FarmSiteWorldView liveView,
            BlockPos center,
            FarmPlotRepairPlan currentShape) {
        List<BlockPos> returnStances = currentShape.returnStanceCandidates();
        if (returnStances.isEmpty()) {
            throw new IllegalStateException("recorded farm has no safe return stance");
        }
        BlockPos returnStance = returnStances.get(0);
        LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations =
                new LinkedHashMap<>();
        for (BlockPos position : FarmPlotGeometry.scanEnvelope(center)) {
            observations.put(position, liveView.observe(position));
        }
        FarmSiteSnapshot snapshot = new FarmSiteSnapshot(
                center,
                true,
                liveView.observeWorld(center),
                observations);
        boolean waterRequired = !currentShape.centerHasWaterSource();
        FarmSitePlan.WaterAction waterAction = waterRequired
                ? new FarmSitePlan.WaterAction(
                        center,
                        returnStance,
                        center.below(),
                        stateFingerprint(controller.getWorld().getBlockState(center)))
                : null;
        return new FarmSitePlan(
                commandAnchor,
                center,
                true,
                waterRequired,
                0,
                0,
                currentShape.repairColumns(),
                List.of(),
                List.of(),
                List.of(),
                waterAction,
                returnStance,
                snapshot);
    }

    private boolean farmEnvelopeLoaded(BlockPos center) {
        for (BlockPos position : FarmPlotGeometry.scanEnvelope(center)) {
            if (!controller.getChunkTracker().isChunkLoaded(position)) {
                return false;
            }
        }
        return true;
    }

    private BlockPos resolveAutoPlanningAnchor() {
        ArrayList<BlockPos> surfaceGrass = new ArrayList<>();
        BlockPos surfaceFallback = commandAnchor;
        if (controller.getChunkTracker().isChunkLoaded(commandAnchor)) {
            int freeY = controller.getWorld().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    commandAnchor.getX(), commandAnchor.getZ());
            surfaceFallback = new BlockPos(commandAnchor.getX(), freeY - 1, commandAnchor.getZ());
        }
        for (BlockPos column : FarmPlotGeometry.autoCandidateCenters(commandAnchor)) {
            if (!controller.getChunkTracker().isChunkLoaded(column)) {
                continue;
            }
            int freeY = controller.getWorld().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(), column.getZ());
            BlockPos surface = new BlockPos(column.getX(), freeY - 1, column.getZ());
            if (controller.getChunkTracker().isChunkLoaded(surface)
                    && controller.getWorld().getBlockState(surface).is(Blocks.GRASS_BLOCK)) {
                surfaceGrass.add(surface.immutable());
            }
        }
        return chooseSurfaceGrassAnchor(surfaceFallback, surfaceGrass);
    }

    static BlockPos chooseSurfaceGrassAnchor(BlockPos anchor, List<BlockPos> surfaceGrass) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(surfaceGrass, "surfaceGrass");
        Map<Integer, List<BlockPos>> byLevel = new HashMap<>();
        for (BlockPos candidate : surfaceGrass) {
            if (candidate != null) {
                byLevel.computeIfAbsent(candidate.getY(), ignored -> new ArrayList<>())
                        .add(candidate);
            }
        }
        Integer selectedY = byLevel.entrySet().stream()
                .min(Comparator
                        .<Map.Entry<Integer, List<BlockPos>>>comparingInt(
                                entry -> entry.getValue().size())
                        .reversed()
                        .thenComparingLong(entry -> entry.getValue().stream()
                                .mapToLong(candidate ->
                                        FarmPlotGeometry.horizontalDistanceSquared(anchor, candidate))
                                .min()
                                .orElse(Long.MAX_VALUE))
                        .thenComparing(Comparator.comparingInt(
                                (Map.Entry<Integer, List<BlockPos>> entry) -> entry.getKey())
                                .reversed()))
                .map(Map.Entry::getKey)
                .orElse(null);
        return selectedY == null
                ? anchor.immutable()
                : new BlockPos(anchor.getX(), selectedY, anchor.getZ());
    }

    private Task tickSiteSelection() {
        final FarmSitePlanner.TickResult result;
        try {
            result = sitePlanner.onTick();
        } catch (RuntimeException plannerFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        switch (result.status()) {
            case RUNNING -> {
                if (watchdog.phaseExpired(FarmPlotPolicy.SITE_SELECTION_TICKS)) {
                    fail(FarmTaskReason.NO_SUITABLE_SITE);
                }
                return null;
            }
            case SELECTED -> {
                sitePlan = Objects.requireNonNull(result.selectedPlan(), "selectedPlan");
                if (!withinCumulativeMutationBudget(
                                cleared, sitePlan.clearActions().size(),
                                FarmPlotPolicy.MAX_CLEAR_ACTIONS)
                        || !withinCumulativeMutationBudget(
                                filled, sitePlan.fillActions().size(),
                                FarmPlotPolicy.MAX_FILL_ACTIONS)
                        || !withinCumulativeMutationBudget(
                                tilled, sitePlan.tillActions().size(),
                                FarmPlotPolicy.MAX_TILL_ACTIONS)) {
                    // Repeating a mutation already owned by this logical setup (for example a
                    // trampled cell) would exceed the original finite operation contract.
                    fail(FarmTaskReason.MANIFEST_DRIFT);
                    return null;
                }
                clearTotal = cleared + sitePlan.clearActions().size();
                fillTotal = filled + sitePlan.fillActions().size();
                tillTotal = tilled + sitePlan.tillActions().size();
                if (resumeTillAfterResources) {
                    protectSiteForAcquisition();
                    resourcePurpose = ResourcePurpose.REPLACE_HOE;
                    resourceStage = ResourceStage.REACQUIRE_HOE;
                    enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
                } else if (count(Items.DIRT) < sitePlan.requiredDirt()) {
                    protectSiteForAcquisition();
                    resourcePurpose = ResourcePurpose.PREPARE_SITE;
                    resourceStage = ResourceStage.ENSURE_BUILD_DIRT;
                    enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
                } else {
                    enterPhase(FarmTaskPhase.REVALIDATE);
                }
                return null;
            }
            case FREEZE_RISK -> fail(FarmTaskReason.FREEZE_RISK);
            case NO_SUITABLE_SITE, TIMED_OUT -> fail(FarmTaskReason.NO_SUITABLE_SITE);
            case WORLD_VIEW_FAILED -> fail(FarmTaskReason.STORE_UNAVAILABLE);
        }
        return null;
    }

    private Task tickResources() {
        if (resourceStage == null || resourcePurpose == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        resourceTicks++;
        boolean childStopped = resourceChild != null && resourceChild.stopped();
        boolean requirementMet = currentResourceRequirementMet();
        FarmTaskReason terminalReason = resourceTerminalReason(
                resourceStage, childStopped, requirementMet, resourceTicks);
        if (terminalReason != FarmTaskReason.NONE) {
            fail(terminalReason);
            return null;
        }
        if (childStopped) {
            resourceChild = null;
        }
        // The Task framework stops the previous child after a null return. Do not overlap its
        // behaviour-stack frame with the next acquisition or the root protection pop.
        if (resourceChild == null && getSub() != null) {
            return null;
        }

        return switch (resourceStage) {
            case ENSURE_BUILD_DIRT, ENSURE_REPAIR_DIRT -> ensureDirt();
            case ENSURE_BUCKET -> ensureBucket();
            case ACQUIRE_WATER -> acquireWater();
            case ENSURE_HOE -> ensureHoe(false);
            case ENSURE_REPAIR_TOOLS -> ensureRepairTools();
            case REACQUIRE_HOE -> ensureHoe(true);
            case COMPLETE -> completeResourcePhase();
        };
    }

    private Task ensureBucket() {
        if (count(Items.WATER_BUCKET) >= 1 || count(Items.BUCKET) >= 1) {
            resourceChild = null;
            resourceStage = nextSatisfiedResourceStage(ResourceStage.ENSURE_BUCKET);
            watchdog.markProgress();
            return null;
        }
        return ensureCatalogueItem(Items.BUCKET, 1, FarmTaskReason.BUCKET_UNAVAILABLE);
    }

    private Task acquireWater() {
        if (count(Items.WATER_BUCKET) >= 1) {
            resourceChild = null;
            resourceStage = nextSatisfiedResourceStage(ResourceStage.ACQUIRE_WATER);
            watchdog.markProgress();
            return null;
        }
        if (resourceChild == null) {
            resourceChild = new AcquireWaterBucketTask(
                    sitePlan.center(), sitePlan.acquisitionReturnStance());
        }
        if (resourceChild instanceof AcquireWaterBucketTask water && water.isFinished()) {
            if (!water.isSuccessful()) {
                fail(water.reason());
                return null;
            }
            resourceChild = null;
            resourceStage = nextSatisfiedResourceStage(ResourceStage.ACQUIRE_WATER);
            watchdog.markProgress();
            return null;
        }
        return resourceChild;
    }

    private Task ensureHoe(boolean replacement) {
        boolean resumedReplacement = !replacement && replacementHoeCheckpoint.isBound();
        boolean logicalReplacement = replacement || resumedReplacement;
        FarmHoeAcquisitionTask.Checkpoint checkpoint = logicalReplacement
                ? replacementHoeCheckpoint
                : initialHoeCheckpoint;
        int needed = checkpoint.isBound()
                ? checkpoint.requiredDurability()
                : logicalReplacement
                ? Math.max(1, remainingTillTargets())
                : remainingTillTargets();
        if (remainingHoeDurability() >= needed) {
            resourceChild = null;
            if (logicalReplacement) {
                replacementHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            } else {
                initialHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            }
            resourceStage = nextSatisfiedResourceStage(
                    replacement ? ResourceStage.REACQUIRE_HOE : ResourceStage.ENSURE_HOE);
            watchdog.markProgress();
            return null;
        }
        FarmTaskReason unavailable = logicalReplacement
                ? FarmTaskReason.HOE_REACQUIRE_FAILED
                : FarmTaskReason.HOE_UNAVAILABLE;
        int checkpointDurability = checkpoint.isBound()
                ? checkpoint.requiredDurability()
                : needed;
        if (resourceChild == null) {
            try {
                resourceChild = new FarmHoeAcquisitionTask(
                        checkpointDurability, sitePlan.center(), commandDimension, checkpoint);
            } catch (RuntimeException invalidCheckpoint) {
                fail(unavailable);
                return null;
            }
        }
        if (resourceChild instanceof FarmHoeAcquisitionTask acquisition
                && acquisition.isFinished()) {
            if (!acquisition.isSuccessful()) {
                fail(unavailable);
                return null;
            }
            resourceChild = null;
            if (remainingHoeDurability() < needed) {
                if (logicalReplacement) {
                    replacementHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
                } else {
                    initialHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
                }
                watchdog.markProgress();
                return null;
            }
            if (logicalReplacement) {
                replacementHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            } else {
                initialHoeCheckpoint = new FarmHoeAcquisitionTask.Checkpoint();
            }
            resourceStage = nextSatisfiedResourceStage(
                    logicalReplacement ? ResourceStage.REACQUIRE_HOE : ResourceStage.ENSURE_HOE);
            watchdog.markProgress();
            return null;
        }
        if (resourceChild.stopped()) {
            fail(unavailable);
            return null;
        }
        return resourceChild;
    }

    private Task ensureRepairTools() {
        if (requiredRepairTools == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        if (requiredRepairTools.hasUnsatisfiedCustom(controller.getInventory())) {
            fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
            return null;
        }
        if (requiredRepairTools.isSatisfied(controller.getInventory())) {
            resourceChild = null;
            repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
            resourceStage = count(Items.DIRT) < requiredRepairDirt
                    ? ResourceStage.ENSURE_REPAIR_DIRT
                    : ResourceStage.COMPLETE;
            watchdog.markProgress();
            return null;
        }
        if (resourceChild == null) {
            try {
                resourceChild = new FarmRepairToolAcquisitionTask(
                        requiredRepairTools,
                        sitePlan.center(),
                        commandDimension,
                        repairToolCheckpoint);
            } catch (RuntimeException invalidCheckpoint) {
                fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
                return null;
            }
        }
        if (resourceChild instanceof FarmRepairToolAcquisitionTask acquisition
                && acquisition.isFinished()) {
            if (!acquisition.isSuccessful()
                    || !requiredRepairTools.isSatisfied(controller.getInventory())) {
                fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
                return null;
            }
            resourceChild = null;
            repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
            resourceStage = count(Items.DIRT) < requiredRepairDirt
                    ? ResourceStage.ENSURE_REPAIR_DIRT
                    : ResourceStage.COMPLETE;
            watchdog.markProgress();
            return null;
        }
        if (resourceChild.stopped()) {
            fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
            return null;
        }
        return resourceChild;
    }

    private Task ensureDirt() {
        int absoluteDirt = resourceStage == ResourceStage.ENSURE_BUILD_DIRT
                ? sitePlan.requiredDirt()
                : requiredRepairDirt;
        if (count(Items.DIRT) >= absoluteDirt) {
            resourceChild = null;
            ResourceStage completedStage = resourceStage;
            if (completedStage == ResourceStage.ENSURE_REPAIR_DIRT) {
                resourceStage = requiredRepairTools != null
                                && !requiredRepairTools.isSatisfied(controller.getInventory())
                        ? ResourceStage.ENSURE_REPAIR_TOOLS
                        : ResourceStage.COMPLETE;
            } else {
                resourceStage = nextSatisfiedResourceStage(completedStage);
            }
            watchdog.markProgress();
            return null;
        }
        return ensureCatalogueItem(Items.DIRT, absoluteDirt, FarmTaskReason.DIRT_UNAVAILABLE);
    }

    private Task ensureCatalogueItem(
            net.minecraft.world.item.Item item,
            int absoluteCount,
            FarmTaskReason unavailable) {
        if (count(item) >= absoluteCount) {
            resourceChild = null;
            watchdog.markProgress();
            return null;
        }
        if (resourceChild == null) {
            final ResourceTask created;
            try {
                created = TaskCatalogue.getItemTask(item, absoluteCount);
            } catch (RuntimeException catalogueFailure) {
                fail(unavailable);
                return null;
            }
            if (created == null) {
                fail(unavailable);
                return null;
            }
            resourceChild = created;
        }
        if (resourceChild.stopped()) {
            fail(unavailable);
            return null;
        }
        return resourceChild;
    }

    private Task completeResourcePhase() {
        if (getSub() != null) {
            return null;
        }
        if (resourcePurpose == ResourcePurpose.REPAIR_PREFLIGHT) {
            if (requiredRepairTools == null) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
            if (!requiredRepairTools.isSatisfied(controller.getInventory())) {
                resourceStage = ResourceStage.ENSURE_REPAIR_TOOLS;
                watchdog.markProgress();
                return null;
            }
            if (count(Items.DIRT) < requiredRepairDirt) {
                resourceStage = ResourceStage.ENSURE_REPAIR_DIRT;
                watchdog.markProgress();
                return null;
            }
        }
        beginResourceReturn(ResourceReturnMode.CONTINUE_SETUP, FarmTaskReason.NONE);
        return null;
    }

    private void beginResourceReturn(ResourceReturnMode mode, FarmTaskReason failure) {
        if (resourceReturnMode != ResourceReturnMode.NONE) {
            return;
        }
        resourceReturnMode = Objects.requireNonNull(mode, "mode");
        pendingResourceFailure = Objects.requireNonNull(failure, "failure");
        resourceReturnStatus = FarmReturnStatus.NOT_REQUIRED;
        resourceReturnCandidates = buildResourceReturnCandidates();
        resourceReturnCandidateIndex = 0;
        resourceReturnCandidateTicks = 0;
        resourceReturnStance = resourceReturnCandidates.isEmpty()
                ? null
                : resourceReturnCandidates.get(0);
        resourceReturnChild = null;
        resourceReturnTicks = 0;
        resourceChild = null;
        setDebugState(mode == ResourceReturnMode.FINALIZE_FAILURE
                ? "Returning to the marked farm before reporting resource failure"
                : "Returning to the marked farm before validation and repair");
    }

    private Task tickResourceReturn() {
        resourceReturnTicks++;
        resourceReturnCandidateTicks++;
        if (preparedFarmHandle != null && resolvePreparedFarmWaypoint() == null) {
            if (pendingResourceFailure == FarmTaskReason.NONE) {
                pendingResourceFailure = FarmTaskReason.FARM_NOT_FOUND;
            }
            resourceReturnMode = ResourceReturnMode.FINALIZE_FAILURE;
        }
        if (resourceReturnStance == null) {
            resourceReturnStatus = FarmReturnStatus.UNAVAILABLE;
            finishResourceReturn();
            return null;
        }
        if (resourceReturnTicks > FarmPlotPolicy.RESOURCE_RETURN_TICKS) {
            resourceReturnStatus = FarmReturnStatus.TIMED_OUT;
            finishResourceReturn();
            return null;
        }

        BlockPos logicalFeet = controller.getBaritone().getEntityContext().feetPos();
        boolean arrived = preparedFarmHandle == null
                ? FarmStanceNavigation.isAtFrozenStance(logicalFeet, resourceReturnStance)
                : isUsableFarmLevelStance(preparedFarmHandle.center(), logicalFeet);
        if (arrived) {
            if (getSub() != null) {
                resourceReturnChild = null;
                return null;
            }
            finishResourceReturn();
            return null;
        }
        if (preparedFarmHandle != null
                && (resourceReturnCandidateTicks >= FarmPlotPolicy.RESOURCE_RETURN_STANCE_TICKS
                || isLoadedButUnusableReturnStance(
                preparedFarmHandle.center(), resourceReturnStance))) {
            if (!advanceResourceReturnCandidate()) {
                resourceReturnStatus = FarmReturnStatus.UNAVAILABLE;
                finishResourceReturn();
                return null;
            }
            if (getSub() != null) {
                resourceReturnChild = null;
                return null;
            }
        }
        Task active = getSub();
        if (active != null) {
            if (active == resourceReturnChild && !resourceReturnChild.stopped()) {
                return resourceReturnChild;
            }
            resourceReturnChild = null;
            // Detach the acquisition child before the recovery movement owns pathing.
            return null;
        }
        resourceReturnChild = new GetToBlockTask(resourceReturnStance);
        return resourceReturnChild;
    }

    private List<BlockPos> buildResourceReturnCandidates() {
        if (sitePlan == null) {
            return List.of();
        }
        if (preparedFarmHandle == null) {
            return List.of(sitePlan.acquisitionReturnStance().immutable());
        }
        BlockPos center = preparedFarmHandle.center();
        BlockPos origin = controller.getBaritone().getEntityContext().feetPos();
        ArrayList<BlockPos> candidates = new ArrayList<>(FarmPlotPolicy.SOIL_CELL_COUNT);
        for (BlockPos soil : FarmPlotGeometry.soilCells(center)) {
            candidates.add(soil.above().immutable());
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos stance) -> isPerimeterSoil(center, stance.below()) ? 0 : 1)
                .thenComparingLong(stance ->
                        FarmPlotGeometry.horizontalDistanceSquared(origin, stance))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(candidates);
    }

    private static boolean isPerimeterSoil(BlockPos center, BlockPos soil) {
        return Math.abs(soil.getX() - center.getX()) == FarmPlotPolicy.HYDRATION_RADIUS
                || Math.abs(soil.getZ() - center.getZ()) == FarmPlotPolicy.HYDRATION_RADIUS;
    }

    private boolean advanceResourceReturnCandidate() {
        resourceReturnChild = null;
        resourceReturnCandidateTicks = 0;
        resourceReturnCandidateIndex++;
        if (resourceReturnCandidateIndex >= resourceReturnCandidates.size()) {
            resourceReturnStance = null;
            return false;
        }
        resourceReturnStance = resourceReturnCandidates.get(resourceReturnCandidateIndex);
        return true;
    }

    private boolean isLoadedButUnusableReturnStance(BlockPos center, BlockPos stance) {
        return controller.getChunkTracker().isChunkLoaded(stance)
                && controller.getChunkTracker().isChunkLoaded(stance.above())
                && controller.getChunkTracker().isChunkLoaded(stance.below())
                && !isUsableFarmLevelStance(center, stance);
    }

    private boolean isUsableFarmLevelStance(BlockPos center, BlockPos stance) {
        if (center == null || stance == null || stance.getY() != center.getY() + 1) {
            return false;
        }
        BlockPos support = stance.below();
        if (support.equals(center) || !FarmPlotGeometry.contains(center, support)
                || !controller.getChunkTracker().isChunkLoaded(support)
                || !controller.getChunkTracker().isChunkLoaded(stance)
                || !controller.getChunkTracker().isChunkLoaded(stance.above())) {
            return false;
        }
        BlockState supportState = controller.getWorld().getBlockState(support);
        BlockState feetState = controller.getWorld().getBlockState(stance);
        BlockState headState = controller.getWorld().getBlockState(stance.above());
        return !supportState.getCollisionShape(controller.getWorld(), support).isEmpty()
                && supportState.getFluidState().isEmpty()
                && feetState.getCollisionShape(controller.getWorld(), stance).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getCollisionShape(controller.getWorld(), stance.above()).isEmpty()
                && headState.getFluidState().isEmpty();
    }

    private WaypointRecord resolvePreparedFarmWaypoint() {
        if (preparedFarmHandle == null) {
            return null;
        }
        EllieGPSStore store = EllieGPSStore.get();
        WaypointRecord record = store == null ? null : store.byId(preparedFarmHandle.id());
        FarmWaypointData data = record == null ? null : record.farmData();
        BlockPos center = record == null ? null : record.canonicalBlockPos();
        return record != null
                && record.schemaVersion == WaypointRecord.WAYPOINT_SCHEMA_VERSION
                && record.pos != null
                && record.pos.length == 3
                && WaypointTypes.FARM.equals(record.type)
                && Objects.equals(preparedFarmHandle.dimension(), record.dimension)
                && preparedFarmHandle.center().equals(center)
                && record.id != null
                && record.id.equals(WaypointRecord.idFor(record.dimension, center))
                && data != null
                && data.isSupportedVersion()
                && data.radius() == FarmPlotPolicy.HYDRATION_RADIUS
                ? record
                : null;
    }

    private WaypointRecord resolvePreparedFarmWaypointAtExactPosition() {
        WaypointRecord record = resolvePreparedFarmWaypoint();
        EllieGPSStore store = EllieGPSStore.get();
        WaypointRecord occupant = record == null || store == null
                ? null
                : store.byPosition(preparedFarmHandle.dimension(), preparedFarmHandle.center());
        return sameWaypointIdentity(record, occupant) ? record : null;
    }

    static boolean sameWaypointIdentity(WaypointRecord byId, WaypointRecord byPosition) {
        return byId != null
                && byPosition != null
                && byId.id != null
                && !byId.id.isBlank()
                && byId.id.equals(byPosition.id);
    }

    private void finishResourceReturn() {
        ResourceReturnMode completedMode = resourceReturnMode;
        FarmTaskReason primaryFailure = pendingResourceFailure;
        ResourcePurpose completedPurpose = resourcePurpose;
        if (resourceReturnStatus == FarmReturnStatus.NOT_REQUIRED) {
            resourceReturnStatus = FarmReturnStatus.RETURNED;
        }
        resourceReturnMode = ResourceReturnMode.NONE;
        resourceReturnChild = null;
        resourceReturnTicks = 0;
        pendingResourceFailure = FarmTaskReason.NONE;
        releaseAcquisitionProtection();

        // A repair obstruction may be removed or replaced while the bot is away looking for its
        // tool. Before reporting an acquisition failure, compare one fresh bounded scan with the
        // frozen failed requirement. A changed or now-satisfied manifest resumes ordinary repair;
        // an unchanged, still-missing requirement keeps the original truthful failure.
        if (completedMode == ResourceReturnMode.FINALIZE_FAILURE
                && resourceReturnStatus == FarmReturnStatus.RETURNED
                && completedPurpose == ResourcePurpose.REPAIR_PREFLIGHT
                && primaryFailure == FarmTaskReason.REPAIR_TOOL_UNAVAILABLE
                && repairToolFailureBecameStale()) {
            resourcePurpose = null;
            requiredRepairDirt = 0;
            requiredRepairTools = null;
            repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
            repairPlan = null;
            repairBreakIndex = 0;
            repairFillIndex = 0;
            enterPhase(FarmTaskPhase.REPAIR);
            return;
        }

        if (completedMode == ResourceReturnMode.CONTINUE_SETUP
                && resourceReturnStatus == FarmReturnStatus.RETURNED) {
            if (worldView == null) {
                finalizeFailure(FarmTaskReason.STORE_UNAVAILABLE);
                return;
            }
            resourcePurpose = null;
            if (completedPurpose == ResourcePurpose.PREPARE_SITE) {
                enterPhase(FarmTaskPhase.REVALIDATE);
            } else if (completedPurpose == ResourcePurpose.COMPLETE_SETUP
                    || completedPurpose == ResourcePurpose.REPLACE_HOE
                    || completedPurpose == ResourcePurpose.REPAIR_PREFLIGHT) {
                resumeTillAfterResources = false;
                requiredRepairDirt = 0;
                requiredRepairTools = null;
                repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
                repairPlan = null;
                repairBreakIndex = 0;
                repairFillIndex = 0;
                enterPhase(FarmTaskPhase.REPAIR);
            } else {
                finalizeFailure(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            }
            return;
        }
        FarmTaskReason reason = primaryFailure == FarmTaskReason.NONE
                ? FarmTaskReason.RESOURCE_RETURN_FAILED
                : primaryFailure;
        finalizeFailure(reason);
    }

    private boolean repairToolFailureBecameStale() {
        if (worldView == null || requiredRepairTools == null || sitePlan == null) {
            return false;
        }
        final FarmPlotRepairPlan.ScanResult refreshed;
        try {
            refreshed = FarmPlotRepairPlan.scan(worldView, sitePlan.center());
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (!refreshed.ready()) {
            return false;
        }
        FarmRepairToolPlan liveTools = refreshed.plan().toolPlan();
        return repairToolFailureSnapshotIsStale(
                requiredRepairTools.identity(),
                liveTools.identity(),
                liveTools.isSatisfied(controller.getInventory()));
    }

    static boolean repairToolFailureSnapshotIsStale(
            String failedPlanIdentity,
            String livePlanIdentity,
            boolean livePlanSatisfied) {
        return !Objects.equals(failedPlanIdentity, livePlanIdentity) || livePlanSatisfied;
    }

    private Task tickRevalidation() {
        if (!siteChunksLoaded()) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }
        try {
            FarmSitePlanner.RevalidationResult result = FarmSitePlanner.revalidate(sitePlan, worldView);
            if (!result.valid()) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
        } catch (RuntimeException unavailable) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        enterPhase(FarmTaskPhase.CLEAR);
        return null;
    }

    private Task tickClear(boolean centerOnly) {
        List<FarmSitePlan.ClearAction> actions = sitePlan.clearActions();
        if (mutationChild != null) {
            VerifiedBreakBlockTask child = (VerifiedBreakBlockTask) mutationChild;
            if (!child.isFinished()) {
                if (watchdog.mutationStalled()) {
                    fail(FarmTaskReason.INTERACTION_DENIED);
                    return null;
                }
                return child;
            }
            if (!child.isSuccessful()) {
                fail(child.reason());
                return null;
            }
            mutationChild = null;
            if (!incrementCleared()) {
                return null;
            }
            watchdog.markProgress();
            if (centerOnly) {
                clearIndex = actions.size();
            } else {
                clearIndex++;
            }
            return null;
        }

        if (centerOnly) {
            FarmSitePlan.ClearAction centerAction = null;
            for (FarmSitePlan.ClearAction action : actions) {
                if (action.purpose() == FarmSitePlan.ClearPurpose.OPEN_CENTER) {
                    centerAction = action;
                    break;
                }
            }
            if (centerAction == null
                    || controller.getWorld().getBlockState(centerAction.target()).isAir()
                    || FarmInteractionPostconditions.isStandaloneWaterSource(
                    controller.getWorld().getBlockState(centerAction.target()))) {
                enterPhase(FarmTaskPhase.REGISTER_PREPARED);
                return null;
            }
            if (!centerAction.expectedStateFingerprint().equals(
                    stateFingerprint(controller.getWorld().getBlockState(centerAction.target())))) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!requireOpenStance(centerAction.stance())) {
                return null;
            }
            mutationChild = new VerifiedBreakBlockTask(centerAction.target(), centerAction.stance());
            watchdog.markProgress();
            return mutationChild;
        }

        while (clearIndex < actions.size()) {
            FarmSitePlan.ClearAction action = actions.get(clearIndex);
            if (action.purpose() == FarmSitePlan.ClearPurpose.OPEN_CENTER) {
                clearIndex++;
                continue;
            }
            if (controller.getWorld().getBlockState(action.target()).isAir()) {
                clearIndex++;
                watchdog.markProgress();
                continue;
            }
            if (!action.expectedStateFingerprint().equals(
                    stateFingerprint(controller.getWorld().getBlockState(action.target())))) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!requireOpenStance(action.stance())) {
                return null;
            }
            mutationChild = new VerifiedBreakBlockTask(action.target(), action.stance());
            watchdog.markProgress();
            return mutationChild;
        }
        enterPhase(FarmTaskPhase.FILL);
        return null;
    }

    private Task tickFill() {
        List<FarmSitePlan.FillAction> actions = sitePlan.fillActions();
        if (mutationChild != null) {
            PlaceFarmDirtTask child = (PlaceFarmDirtTask) mutationChild;
            if (!child.isFinished()) {
                if (watchdog.mutationStalled()) {
                    fail(FarmTaskReason.INTERACTION_DENIED);
                    return null;
                }
                return child;
            }
            if (!child.isSuccessful()) {
                fail(child.reason());
                return null;
            }
            mutationChild = null;
            if (!incrementFilled()) {
                return null;
            }
            fillIndex++;
            watchdog.markProgress();
            return null;
        }
        while (fillIndex < actions.size()) {
            FarmSitePlan.FillAction action = actions.get(fillIndex);
            BlockState state = controller.getWorld().getBlockState(action.target());
            if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.FARMLAND)) {
                fillIndex++;
                watchdog.markProgress();
                continue;
            }
            if (!state.isAir()) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!requireOpenStance(action.stance())) {
                return null;
            }
            mutationChild = new PlaceFarmDirtTask(action.target(), action.stance());
            watchdog.markProgress();
            return mutationChild;
        }
        enterPhase(sitePlan.waterRequired()
                ? FarmTaskPhase.OPEN_CENTER
                : FarmTaskPhase.REGISTER_PREPARED);
        return null;
    }

    private Task tickRegisterPrepared() {
        if (preparedCommitAttempted) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        preparedCommitAttempted = true;
        final FarmPlotRepairPlan.ScanResult preparedShape;
        try {
            preparedShape = FarmPlotRepairPlan.scan(worldView, sitePlan.center());
        } catch (RuntimeException scanFailure) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (!preparedShape.ready()
                || !preparedShape.plan().clearActions().isEmpty()
                || !preparedShape.plan().fillActions().isEmpty()) {
            fail(preparedShape.reason() == FarmPlotRepairPlan.UnsafeReason.UNREADABLE
                    ? FarmTaskReason.CHUNK_UNLOADED
                    : FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        FarmTaskReason registrationFailure = registerPreparedCheckpoint(sitePlan.center());
        if (registrationFailure != FarmTaskReason.NONE) {
            fail(registrationFailure);
            return null;
        }

        protectSiteForAcquisition();
        resourcePurpose = ResourcePurpose.COMPLETE_SETUP;
        boolean waterMissing = !FarmInteractionPostconditions.isStandaloneWaterSource(
                controller.getWorld().getBlockState(sitePlan.center()));
        resourceStage = initialResourceStage(waterMissing);
        watchdog.markProgress();
        enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
        return null;
    }

    private FarmTaskReason registerPreparedCheckpoint(BlockPos center) {
        final FarmObservationResult scan;
        try {
            scan = FarmCropScanner.observePrepared(controller.getWorld(), center);
        } catch (RuntimeException scannerFailure) {
            return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        if (scan.status() != FarmObservationStatus.OBSERVED || scan.observation() == null) {
            return scan.status() == FarmObservationStatus.UNLOADED
                    ? FarmTaskReason.CHUNK_UNLOADED
                    : FarmTaskReason.MANIFEST_DRIFT;
        }

        final WaypointMutationResult prepared;
        try {
            prepared = FarmWaypointService.registerPrepared(
                    scan.observation(), WaypointRecord.ORIGIN_BOT_PLACED);
        } catch (UncheckedIOException jsonFailure) {
            return FarmTaskReason.WAYPOINT_JSON_FAILURE;
        } catch (RuntimeException internalFailure) {
            return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        waypointResult = prepared;
        if (prepared == null) {
            return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        FarmTaskReason mutationFailure = failureForMutation(prepared.status());
        if (mutationFailure != FarmTaskReason.NONE) {
            return mutationFailure;
        }
        WaypointRecord current = prepared.current();
        BlockPos currentCenter = current == null ? null : current.canonicalBlockPos();
        if (current == null
                || !current.stale
                || !WaypointTypes.FARM.equals(current.type)
                || !Objects.equals(commandDimension, current.dimension)
                || !center.equals(currentCenter)
                || current.id == null
                || !current.id.equals(WaypointRecord.idFor(commandDimension, center))) {
            return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        preparedFarmHandle = new PreparedFarmHandle(
                current.id, current.dimension, currentCenter);
        if (resolvePreparedFarmWaypointAtExactPosition() == null) {
            return FarmTaskReason.FARM_NOT_FOUND;
        }
        if (prepared.status() == WaypointMutationStatus.COMMITTED_INDEX_DEGRADED
                || prepared.status() == WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED) {
            if (runState != null) {
                runState.setWaypointDegraded(
                        DegradationLevel.PARTIAL, "farm_waypoint_index_degraded");
            }
        }
        return FarmTaskReason.NONE;
    }

    private Task tickRepair() {
        if (preparedFarmHandle == null) {
            fail(FarmTaskReason.FARM_NOT_FOUND);
            return null;
        }
        if (mutationChild != null) {
            if (mutationChild instanceof VerifiedBreakBlockTask child) {
                if (!child.isFinished()) {
                    if (watchdog.mutationStalled()) {
                        fail(FarmTaskReason.INTERACTION_DENIED);
                        return null;
                    }
                    return child;
                }
                if (!child.isSuccessful()) {
                    if (child.reason() == FarmTaskReason.MANIFEST_DRIFT
                            && child.attempts() == 0) {
                        mutationChild = null;
                        repairPlan = null;
                        watchdog.markProgress();
                        return null;
                    }
                    if (child.reason() == FarmTaskReason.REPAIR_TOOL_UNAVAILABLE) {
                        mutationChild = null;
                        repairPlan = null;
                        repairBreakIndex = 0;
                        repairFillIndex = 0;
                        watchdog.markProgress();
                        return null;
                    }
                    fail(child.reason());
                    return null;
                }
                mutationChild = null;
                if (!incrementCleared()) {
                    return null;
                }
                repairBreakIndex++;
                watchdog.markProgress();
                return null;
            }
            if (mutationChild instanceof PlaceFarmDirtTask child) {
                if (!child.isFinished()) {
                    if (watchdog.mutationStalled()) {
                        fail(FarmTaskReason.INTERACTION_DENIED);
                        return null;
                    }
                    return child;
                }
                if (!child.isSuccessful()) {
                    if (child.reason() == FarmTaskReason.MANIFEST_DRIFT
                            && child.attempts() == 0) {
                        mutationChild = null;
                        repairPlan = null;
                        watchdog.markProgress();
                        return null;
                    }
                    fail(child.reason());
                    return null;
                }
                mutationChild = null;
                if (!incrementFilled()) {
                    return null;
                }
                repairFillIndex++;
                watchdog.markProgress();
                return null;
            }
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        if (getSub() != null) {
            return null;
        }

        if (repairPlan == null) {
            FarmTaskReason orphanFailure = retireOrphanedFarmProtection(sitePlan.center());
            if (orphanFailure != FarmTaskReason.NONE) {
                fail(orphanFailure);
                return null;
            }
            final FarmPlotRepairPlan.ScanResult result;
            try {
                result = FarmPlotRepairPlan.scan(worldView, sitePlan.center());
            } catch (RuntimeException scanFailure) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!result.ready()) {
                fail(result.reason() == FarmPlotRepairPlan.UnsafeReason.UNREADABLE
                        ? FarmTaskReason.CHUNK_UNLOADED
                        : FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            repairPlan = result.plan();
            repairBreakIndex = 0;
            repairFillIndex = 0;
            clearTotal = Math.max(clearTotal,
                    cleared + repairPlan.clearActions().size());
            fillTotal = Math.max(fillTotal,
                    filled + repairPlan.fillActions().size());

            FarmRepairToolPlan toolPlan = repairPlan.toolPlan();
            if (toolPlan.hasUnsatisfiedCustom(controller.getInventory())) {
                fail(FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
                return null;
            }
            boolean toolsMissing = !toolPlan.isSatisfied(controller.getInventory());
            boolean dirtMissing = count(Items.DIRT) < repairPlan.requiredDirt();
            if (toolsMissing || dirtMissing) {
                if (repairResourceTrips >= FarmPlotPolicy.MAX_REPAIR_RESOURCE_TRIPS) {
                    fail(toolsMissing
                            ? FarmTaskReason.REPAIR_TOOL_UNAVAILABLE
                            : FarmTaskReason.DIRT_UNAVAILABLE);
                    return null;
                }
                repairResourceTrips++;
                requiredRepairDirt = repairPlan.requiredDirt();
                requiredRepairTools = toolPlan;
                repairToolCheckpoint = new FarmRepairToolAcquisitionTask.Checkpoint();
                repairPlan = null;
                protectSiteForAcquisition();
                resourcePurpose = ResourcePurpose.REPAIR_PREFLIGHT;
                resourceStage = toolsMissing
                        ? ResourceStage.ENSURE_REPAIR_TOOLS
                        : ResourceStage.ENSURE_REPAIR_DIRT;
                resourceChild = null;
                enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
                return null;
            }

            boolean needsMutations = !repairPlan.clearActions().isEmpty()
                    || !repairPlan.fillActions().isEmpty();
            if (needsMutations) {
                if (repairMutationPasses >= FarmPlotPolicy.MAX_REPAIR_PASSES) {
                    fail(FarmTaskReason.MANIFEST_DRIFT);
                    return null;
                }
                repairMutationPasses++;
            } else {
                activeTillTargets = repairPlan.tillActions().stream()
                        .map(FarmPlotRepairPlan.TillAction::target)
                        .toList();
                tillPlanReady = true;
                tillIndex = 0;
                tillTotal = tilled + activeTillTargets.size();
                if (!repairPlan.centerHasWaterSource()) {
                    if (count(Items.WATER_BUCKET) < 1) {
                        repairPlan = null;
                        protectSiteForAcquisition();
                        resourcePurpose = ResourcePurpose.COMPLETE_SETUP;
                        resourceStage = ResourceStage.ENSURE_BUCKET;
                        resourceChild = null;
                        enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
                    } else if (remainingHoeDurability() < activeTillTargets.size()) {
                        beginHoeReacquisition();
                    } else {
                        enterPhase(FarmTaskPhase.PLACE_WATER);
                    }
                } else if (remainingHoeDurability() < activeTillTargets.size()) {
                    beginHoeReacquisition();
                } else {
                    enterPhase(FarmTaskPhase.TILL);
                }
                return null;
            }
        }

        List<FarmPlotRepairPlan.ClearAction> breaks = repairPlan.clearActions();
        while (repairBreakIndex < breaks.size()) {
            FarmPlotRepairPlan.ClearAction action = breaks.get(repairBreakIndex);
            BlockState state = controller.getWorld().getBlockState(action.target());
            if (state.isAir()) {
                repairBreakIndex++;
                watchdog.markProgress();
                continue;
            }
            if (!action.expectedStateFingerprint().equals(stateFingerprint(state))
                    || !isLiveOpenMutationStance(action.stance())) {
                repairPlan = null;
                watchdog.markProgress();
                return null;
            }
            mutationChild = VerifiedBreakBlockTask.forFarmRepair(
                    action.target(),
                    action.stance(),
                    action.expectedStateFingerprint(),
                    action.breakToolRequirement());
            watchdog.markProgress();
            return mutationChild;
        }

        RepairPassTransition passTransition = repairPassTransition(
                breaks.size(), repairBreakIndex, repairFillIndex);
        repairBreakIndex = passTransition.nextBreakIndex();
        repairFillIndex = passTransition.nextFillIndex();
        if (passTransition.action() == RepairPassAction.RESCAN_BEFORE_FILL) {
            // Clear actions change live reach, stance, and protection facts. Never execute a fill
            // action frozen before those mutations; rebuild the bounded manifest from the world.
            repairPlan = null;
            watchdog.markProgress();
            return null;
        }
        if (passTransition.action() != RepairPassAction.CONTINUE_FILLS) {
            throw new IllegalStateException("repair clear loop exited before completion");
        }

        List<FarmPlotRepairPlan.FillAction> fills = repairPlan.fillActions();
        while (repairFillIndex < fills.size()) {
            FarmPlotRepairPlan.FillAction action = fills.get(repairFillIndex);
            BlockState state = controller.getWorld().getBlockState(action.target());
            if (state.is(Blocks.DIRT)
                    || state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.FARMLAND)) {
                repairFillIndex++;
                watchdog.markProgress();
                continue;
            }
            if (!state.isAir()
                    || !action.expectedStateFingerprint().equals(stateFingerprint(state))
                    || !isLiveOpenMutationStance(action.stance())) {
                repairPlan = null;
                watchdog.markProgress();
                return null;
            }
            mutationChild = PlaceFarmDirtTask.forFarmRepair(
                    action.target(), action.stance());
            watchdog.markProgress();
            return mutationChild;
        }

        // Discard every target/fingerprint and perform a fresh bounded scan before continuing.
        repairPlan = null;
        repairBreakIndex = 0;
        repairFillIndex = 0;
        watchdog.markProgress();
        return null;
    }

    enum RepairPassAction {
        CONTINUE_BREAKS,
        RESCAN_BEFORE_FILL,
        CONTINUE_FILLS
    }

    record RepairPassTransition(
            RepairPassAction action,
            int nextBreakIndex,
            int nextFillIndex) {
    }

    static RepairPassTransition repairPassTransition(
            int plannedBreaks,
            int completedBreaks,
            int currentFillIndex) {
        if (plannedBreaks < 0 || completedBreaks < 0 || completedBreaks > plannedBreaks
                || currentFillIndex < 0) {
            throw new IllegalArgumentException("invalid repair-break progress");
        }
        if (completedBreaks < plannedBreaks) {
            return new RepairPassTransition(
                    RepairPassAction.CONTINUE_BREAKS, completedBreaks, currentFillIndex);
        }
        if (plannedBreaks > 0) {
            return new RepairPassTransition(
                    RepairPassAction.RESCAN_BEFORE_FILL, 0, 0);
        }
        return new RepairPassTransition(
                RepairPassAction.CONTINUE_FILLS, completedBreaks, currentFillIndex);
    }

    private Task tickPlaceWater() {
        BlockState centerState = controller.getWorld().getBlockState(sitePlan.center());
        if (FarmInteractionPostconditions.isStandaloneWaterSource(centerState)) {
            if (!(mutationChild instanceof PlaceFarmWaterTask child)) {
                enterPhase(FarmTaskPhase.TILL);
                return null;
            }
            if (!child.settlePendingTransition()
                    || !child.isFinished()
                    || !child.isSuccessful()) {
                fail(child.reason() == FarmTaskReason.NONE
                        ? FarmTaskReason.INTERNAL_CONTRACT_FAILURE
                        : child.reason());
                return null;
            }
            if (!consumeFinishedMutation(child)) {
                return null;
            }
            watchdog.markProgress();
            enterPhase(FarmTaskPhase.TILL);
            return null;
        }
        if (mutationChild == null) {
            FarmSitePlan.WaterAction action = sitePlan.waterAction();
            if (!centerState.isAir()) {
                repairPlan = null;
                enterPhase(FarmTaskPhase.REPAIR);
                return null;
            }
            if (count(Items.WATER_BUCKET) < 1) {
                protectSiteForAcquisition();
                resourcePurpose = ResourcePurpose.COMPLETE_SETUP;
                resourceStage = ResourceStage.ENSURE_BUCKET;
                enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
                return null;
            }
            BlockPos preferred = action == null ? null : action.stance();
            BlockPos stance = chooseOpenMutationStance(sitePlan.center(), preferred);
            if (stance == null) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!requireOpenStance(stance)) {
                return null;
            }
            mutationChild = new PlaceFarmWaterTask(
                    sitePlan.center(), sitePlan.center().below(), stance);
            watchdog.markProgress();
            return mutationChild;
        }
        PlaceFarmWaterTask child = (PlaceFarmWaterTask) mutationChild;
        if (!child.isFinished()) {
            if (watchdog.mutationStalled()) {
                fail(FarmTaskReason.INTERACTION_DENIED);
                return null;
            }
            return child;
        }
        if (!child.isSuccessful()) {
            fail(child.reason());
            return null;
        }
        mutationChild = null;
        sourcePlaced = true;
        watchdog.markProgress();
        enterPhase(FarmTaskPhase.TILL);
        return null;
    }

    private BlockPos chooseOpenMutationStance(BlockPos target, BlockPos preferred) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        if (preferred != null) {
            candidates.add(preferred.immutable());
        }
        for (BlockPos candidate : FarmPlotGeometry.stanceCandidates(sitePlan.center(), target)) {
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        for (BlockPos candidate : candidates) {
            if (isLiveOpenMutationStance(candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean isLiveOpenMutationStance(BlockPos stance) {
        if (!FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)) {
            return false;
        }
        BlockPos supportPos = stance.below();
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store != null
                && !FarmStanceNavigation.stanceProtectionDenied(
                store.contains(commandDimension, supportPos),
                store.contains(commandDimension, stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                store.contains(commandDimension, stance.above()));
    }

    private Task tickTill() {
        if (!tillPlanReady) {
            repairPlan = null;
            enterPhase(FarmTaskPhase.REPAIR);
            return null;
        }
        List<BlockPos> actions = activeTillTargets;
        if (mutationChild != null) {
            TillFarmBlockTask child = (TillFarmBlockTask) mutationChild;
            if (!child.isFinished()) {
                if (watchdog.mutationStalled()) {
                    fail(FarmTaskReason.INTERACTION_DENIED);
                    return null;
                }
                return child;
            }
            if (!child.isSuccessful()) {
                if (child.reason() == FarmTaskReason.HOE_UNAVAILABLE) {
                    mutationChild = null;
                    beginHoeReacquisition();
                    return null;
                }
                if (child.reason() == FarmTaskReason.MANIFEST_DRIFT
                        && child.attempts() == 0) {
                    mutationChild = null;
                    repairPlan = null;
                    enterPhase(FarmTaskPhase.REPAIR);
                    return null;
                }
                fail(child.reason());
                return null;
            }
            if (child.freshTill()) {
                if (!incrementTilled()) {
                    return null;
                }
            }
            mutationChild = null;
            tillIndex++;
            watchdog.markProgress();
            return null;
        }

        while (tillIndex < actions.size()) {
            BlockPos target = actions.get(tillIndex);
            BlockState targetState = controller.getWorld().getBlockState(target);
            if (targetState.is(Blocks.FARMLAND)) {
                tillIndex++;
                watchdog.markProgress();
                continue;
            }
            if ((!targetState.is(Blocks.DIRT) && !targetState.is(Blocks.GRASS_BLOCK))
                    || !controller.getWorld().getBlockState(target.above()).isAir()) {
                repairPlan = null;
                enterPhase(FarmTaskPhase.REPAIR);
                return null;
            }
            if (remainingHoeDurability() < 1) {
                beginHoeReacquisition();
                return null;
            }
            BlockPos stance = chooseOpenMutationStance(target, null);
            if (stance == null) {
                fail(FarmTaskReason.MANIFEST_DRIFT);
                return null;
            }
            if (!requireOpenStance(stance)) {
                return null;
            }
            mutationChild = new TillFarmBlockTask(target, stance);
            watchdog.markProgress();
            return mutationChild;
        }
        enterPhase(FarmTaskPhase.VERIFY);
        return null;
    }

    private void beginHoeReacquisition() {
        protectSiteForAcquisition();
        resumeTillAfterResources = true;
        resourcePurpose = ResourcePurpose.REPLACE_HOE;
        resourceStage = ResourceStage.REACQUIRE_HOE;
        resourceChild = null;
        enterPhase(FarmTaskPhase.ACQUIRE_RESOURCES);
    }

    private Task tickVerify() {
        final FarmPlotRepairPlan.ScanResult finalShape;
        try {
            finalShape = FarmPlotRepairPlan.scan(worldView, sitePlan.center());
        } catch (RuntimeException scanFailure) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (!finalShape.ready()) {
            fail(finalShape.reason() == FarmPlotRepairPlan.UnsafeReason.UNREADABLE
                    ? FarmTaskReason.CHUNK_UNLOADED
                    : FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        FarmPlotRepairPlan verifiedShape = finalShape.plan();
        if (!verifiedShape.centerHasWaterSource()
                || !verifiedShape.clearActions().isEmpty()
                || !verifiedShape.fillActions().isEmpty()
                || !verifiedShape.tillActions().isEmpty()) {
            repairPlan = null;
            tillPlanReady = false;
            enterPhase(FarmTaskPhase.REPAIR);
            return null;
        }

        final FarmObservationResult scan;
        try {
            WaypointRecord scanRecord = new WaypointRecord();
            scanRecord.id = WaypointRecord.idFor(commandDimension, sitePlan.center());
            scanRecord.type = WaypointTypes.FARM;
            scanRecord.dimension = commandDimension;
            BlockPos center = sitePlan.center();
            scanRecord.pos = new int[]{center.getX(), center.getY(), center.getZ()};
            scanRecord.data = new FarmWaypointData(
                    FarmPlotPolicy.HYDRATION_RADIUS,
                    FarmPlotPolicy.SOIL_CELL_COUNT,
                    List.of());
            scan = FarmCropScanner.observe(controller.getWorld(), scanRecord);
        } catch (RuntimeException scannerFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        if (scan.status() != FarmObservationStatus.OBSERVED) {
            fail(scan.status() == FarmObservationStatus.UNLOADED
                    ? FarmTaskReason.CHUNK_UNLOADED
                    : FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        verifiedObservation = scan.observation();
        enterPhase(FarmTaskPhase.COMMIT);
        return null;
    }

    private Task tickCommit() {
        if (commitAttempted || verifiedObservation == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        // Position lookup is linear over the bounded authoritative store, so reconcile it only at
        // the two mutation boundaries: initial recorded binding and immediately before commit.
        if (resolvePreparedFarmWaypointAtExactPosition() == null) {
            fail(FarmTaskReason.FARM_NOT_FOUND);
            return null;
        }
        commitAttempted = true;
        try {
            waypointResult = FarmWaypointService.registerOrRefresh(
                    verifiedObservation, WaypointRecord.ORIGIN_BOT_PLACED);
        } catch (UncheckedIOException jsonFailure) {
            fail(FarmTaskReason.WAYPOINT_JSON_FAILURE);
            return null;
        } catch (RuntimeException internalFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        if (waypointResult == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }

        FarmTaskReason mutationFailure = failureForMutation(waypointResult.status());
        if (mutationFailure != FarmTaskReason.NONE) {
            fail(mutationFailure);
            return null;
        }
        if (waypointResult.status() == WaypointMutationStatus.COMMITTED_INDEX_DEGRADED
                || waypointResult.status() == WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED) {
            if (runState != null) {
                runState.setWaypointDegraded(
                        DegradationLevel.PARTIAL, "farm_waypoint_index_degraded");
            }
        }
        succeed();
        return null;
    }

    static FarmTaskReason failureForMutation(WaypointMutationStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case COMMITTED, COMMITTED_INDEX_DEGRADED, NO_CHANGE, NO_CHANGE_INDEX_DEGRADED ->
                    FarmTaskReason.NONE;
            case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT -> FarmTaskReason.TYPE_CONFLICT;
            case FAILED_STORE_UNAVAILABLE -> FarmTaskReason.STORE_UNAVAILABLE;
            case FAILED_JSON_COMMIT -> FarmTaskReason.WAYPOINT_JSON_FAILURE;
            case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    static ResourceStage initialResourceStage(boolean waterRequired) {
        return waterRequired ? ResourceStage.ENSURE_BUCKET : ResourceStage.ENSURE_HOE;
    }

    static boolean requiresPreparedWaypoint(FarmTaskPhase phase) {
        return switch (Objects.requireNonNull(phase, "phase")) {
            case REPAIR, PLACE_WATER, TILL, VERIFY, COMMIT -> true;
            default -> false;
        };
    }

    static ResourceStage nextSatisfiedResourceStage(ResourceStage stage) {
        return switch (Objects.requireNonNull(stage, "stage")) {
            case ENSURE_BUCKET -> ResourceStage.ACQUIRE_WATER;
            case ACQUIRE_WATER -> ResourceStage.ENSURE_HOE;
            case ENSURE_BUILD_DIRT, ENSURE_HOE, ENSURE_REPAIR_TOOLS,
                    ENSURE_REPAIR_DIRT, REACQUIRE_HOE,
                    COMPLETE -> ResourceStage.COMPLETE;
        };
    }

    static FarmTaskReason resourceTerminalReason(
            ResourceStage stage,
            boolean childStopped,
            boolean requirementMet,
            int resourceTicks) {
        Objects.requireNonNull(stage, "stage");
        if (childStopped && !requirementMet) {
            return resourceUnavailableReason(stage);
        }
        if (resourceTicks >= FarmPlotPolicy.RESOURCE_PHASE_TICKS
                && stage != ResourceStage.COMPLETE
                && !requirementMet) {
            return FarmTaskReason.RESOURCE_TIMEOUT;
        }
        return FarmTaskReason.NONE;
    }

    static boolean withinCumulativeMutationBudget(int completed, int remaining, int maximum) {
        return completed >= 0
                && remaining >= 0
                && maximum >= 0
                && completed <= maximum
                && remaining <= maximum - completed;
    }

    private boolean incrementCleared() {
        if (!withinCumulativeMutationBudget(cleared, 1, FarmPlotPolicy.MAX_CLEAR_ACTIONS)) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        cleared++;
        return true;
    }

    private boolean incrementFilled() {
        if (!withinCumulativeMutationBudget(filled, 1, FarmPlotPolicy.MAX_FILL_ACTIONS)) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        filled++;
        return true;
    }

    private boolean incrementTilled() {
        if (!withinCumulativeMutationBudget(tilled, 1, FarmPlotPolicy.MAX_TILL_ACTIONS)) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return false;
        }
        tilled++;
        return true;
    }

    private static FarmTaskReason resourceUnavailableReason(ResourceStage stage) {
        return switch (stage) {
            case ENSURE_BUILD_DIRT, ENSURE_REPAIR_DIRT -> FarmTaskReason.DIRT_UNAVAILABLE;
            case ENSURE_REPAIR_TOOLS -> FarmTaskReason.REPAIR_TOOL_UNAVAILABLE;
            case ENSURE_BUCKET -> FarmTaskReason.BUCKET_UNAVAILABLE;
            case ACQUIRE_WATER -> FarmTaskReason.WATER_SOURCE_NOT_FOUND;
            case ENSURE_HOE -> FarmTaskReason.HOE_UNAVAILABLE;
            case REACQUIRE_HOE -> FarmTaskReason.HOE_REACQUIRE_FAILED;
            case COMPLETE -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    private boolean currentResourceRequirementMet() {
        return switch (resourceStage) {
            case ENSURE_BUILD_DIRT -> count(Items.DIRT) >= sitePlan.requiredDirt();
            case ENSURE_REPAIR_DIRT ->
                    count(Items.DIRT) >= requiredRepairDirt;
            case ENSURE_REPAIR_TOOLS -> requiredRepairTools != null
                    && requiredRepairTools.isSatisfied(controller.getInventory());
            case ENSURE_BUCKET -> count(Items.WATER_BUCKET) >= 1 || count(Items.BUCKET) >= 1;
            case ACQUIRE_WATER -> count(Items.WATER_BUCKET) >= 1;
            case ENSURE_HOE -> remainingHoeDurability() >= remainingTillTargets();
            case REACQUIRE_HOE -> remainingHoeDurability() >= Math.max(1, remainingTillTargets());
            case COMPLETE -> true;
        };
    }

    private int remainingTillTargets() {
        if (sitePlan == null) {
            return 0;
        }
        if (tillPlanReady) {
            return Math.max(0, activeTillTargets.size() - tillIndex);
        }
        // Acquisition happens before the post-travel repair scan. Reserve enough durability for
        // every soil cell so path damage cannot immediately force another tool excursion.
        return FarmPlotPolicy.SOIL_CELL_COUNT;
    }

    private int remainingHoeDurability() {
        return FarmHoePolicy.remainingDurability(controller.getInventory());
    }

    private int count(net.minecraft.world.item.Item item) {
        return controller.getItemStorage().getItemCount(item);
    }

    private static String stateFingerprint(BlockState state) {
        return FarmSiteWorldView.stateFingerprint(state);
    }

    private boolean requireOpenStance(BlockPos stance) {
        if (!controller.getChunkTracker().isChunkLoaded(stance)
                || !controller.getChunkTracker().isChunkLoaded(stance.above())
                || !controller.getChunkTracker().isChunkLoaded(stance.below())) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return false;
        }
        BlockPos supportPos = stance.below();
        if (!FarmStanceNavigation.hasPlannerSafeLiveGeometry(controller, stance)) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return false;
        }
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        if (store == null
                || FarmStanceNavigation.stanceProtectionDenied(
                store.contains(commandDimension, supportPos),
                store.contains(commandDimension, stance),
                FarmStanceNavigation.isPassableCropStance(controller, stance),
                store.contains(commandDimension, stance.above()))) {
            fail(store == null
                    ? FarmTaskReason.STORE_UNAVAILABLE
                    : FarmTaskReason.MANIFEST_DRIFT);
            return false;
        }
        return true;
    }

    private boolean siteChunksLoaded() {
        if (sitePlan == null) {
            return false;
        }
        for (BlockPos position : sitePlan.snapshot().positions()) {
            if (!controller.getChunkTracker().isChunkLoaded(position)) {
                return false;
            }
        }
        return true;
    }

    private void protectSiteForAcquisition() {
        if (acquisitionProtectionPushed) {
            return;
        }
        Set<BlockPos> frozen = new HashSet<>(sitePlan.snapshot().positions());
        controller.getBehaviour().push();
        acquisitionProtectionPushed = true;
        controller.getBehaviour().avoidBlockBreaking(frozen::contains);
        controller.getBehaviour().avoidBlockPlacing(frozen::contains);
    }

    private void releaseAcquisitionProtection() {
        if (acquisitionProtectionPushed && controller != null) {
            controller.getBehaviour().pop();
            acquisitionProtectionPushed = false;
        }
    }

    private void enterPhase(FarmTaskPhase next) {
        phase = Objects.requireNonNull(next, "next");
        watchdog.enterPhase(next);
        mutationChild = null;
    }

    private String currentDimension() {
        return controller.getWorld().dimension().location().toString();
    }

    private BlockPos resolvedCenter() {
        if (sitePlan != null) {
            return sitePlan.center();
        }
        return resumeCenter == null ? exactCenter : resumeCenter;
    }

    private void succeed() {
        terminal = true;
        successful = true;
        failureReason = FarmTaskReason.NONE;
        phase = FarmTaskPhase.DONE;
        terminalOutcome = new FarmTaskOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                commandDimension, sitePlan.center(), cleared, filled, tilled,
                sourcePlaced, waypointResult);
        updateProgress();
    }

    private void fail(FarmTaskReason reason) {
        if (terminal) {
            return;
        }
        FarmTaskReason requested = Objects.requireNonNull(reason, "reason");
        if (phase == FarmTaskPhase.ACQUIRE_RESOURCES
                && sitePlan != null
                && resourceReturnMode == ResourceReturnMode.NONE
                && requested != FarmTaskReason.CANCELLED_OPERATOR) {
            if (controller != null
                    && Objects.equals(commandDimension, currentDimension())
                    && sitePlan.acquisitionReturnStance() != null) {
                beginResourceReturn(ResourceReturnMode.FINALIZE_FAILURE, requested);
                return;
            }
            resourceReturnStatus = FarmReturnStatus.UNAVAILABLE;
        }
        finalizeFailure(requested);
    }

    private void finalizeFailure(FarmTaskReason reason) {
        if (terminal) {
            return;
        }
        FarmTaskReason requestedReason = Objects.requireNonNull(reason, "reason");
        FarmTaskReason receiptFailure = reconcileIssuedReceiptsForTerminalOutcome();
        terminal = true;
        successful = false;
        failureReason = receiptFailure == FarmTaskReason.NONE
                ? requestedReason
                : receiptFailure;
        FarmTaskPhase failurePhase = phase == FarmTaskPhase.DONE
                || (phase == FarmTaskPhase.PRECHECK
                && failureReason == FarmTaskReason.INTERNAL_CONTRACT_FAILURE)
                ? FarmTaskPhase.FAILED
                : phase;
        terminalOutcome = new FarmTaskOutcome(
                true, false, failureReason, failurePhase,
                commandDimension,
                resolvedCenter(),
                cleared, filled, tilled, sourcePlaced, waypointResult, resourceReturnStatus);
        updateProgress();
    }

    private void updateProgress() {
        if (runState != null && !runState.isTerminal()) {
            runState.setFarmProgress(describeProgress());
        }
    }

    @Override
    public boolean prepareForTransientResume(TaskSuspensionCause cause) {
        Objects.requireNonNull(cause, "cause");
        if (terminal) {
            return false;
        }
        if (!reconcileMutationReceiptBeforeSuspend()) {
            quiesceMovementForStop();
            return false;
        }
        // Only a center that completed automatic selection or explicit bounded repair preflight is
        // eligible for trusted resume planning. PRECHECK itself is replayed from frozen command input.
        if (sitePlan != null) {
            resumeCenter = sitePlan.center().immutable();
        }
        resumeStartPending = true;
        quiesceMovementForStop();
        return true;
    }

    @Override
    public void onTransientResumeAbandoned() {
        resumeStartPending = false;
        if (!terminal) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
        quiesceMovementForStop();
    }

    /** Captures or retains an exact child receipt before the framework detaches that child. */
    private boolean reconcileMutationReceiptBeforeSuspend() {
        if (pendingMutationReceipt != null) {
            if (!settleMutationReceipt(pendingMutationReceipt)) {
                fail(receiptReason(pendingMutationReceipt));
                return false;
            }
            if (pendingMutationReceipt.isFinished()
                    && !consumeFinishedMutation(pendingMutationReceipt)) {
                return false;
            }
        }
        if (mutationChild == null) {
            return true;
        }
        if (!settleMutationReceipt(mutationChild)) {
            fail(receiptReason(mutationChild));
            return false;
        }
        if (!mutationChild.isFinished()) {
            FarmMutationReceipt receipt = (FarmMutationReceipt) mutationChild;
            if (receipt.hasIssuedMutation()) {
                receipt.prepareForTransientDetach();
                pendingMutationReceipt = mutationChild;
                pendingMutationReceiptTicks = 0;
            }
            return true;
        }
        return consumeFinishedMutation(mutationChild);
    }

    /** Waits a fixed active-tick window for an input issued immediately before suspension. */
    private boolean settleRetainedMutationReceiptOnResume() {
        Task retained = pendingMutationReceipt;
        if (retained == null) {
            return true;
        }
        if (!settleMutationReceipt(retained)) {
            fail(receiptReason(retained));
            pendingMutationReceipt = null;
            return false;
        }
        if (retained.isFinished()) {
            if (!consumeFinishedMutation(retained)) {
                return false;
            }
            pendingMutationReceiptTicks = 0;
            watchdog.markProgress();
            return true;
        }
        pendingMutationReceiptTicks++;
        if (pendingMutationReceiptTicks <= FarmPlotPolicy.TRANSIENT_RECEIPT_SETTLE_TICKS) {
            setDebugState("Settling farm mutation receipt after interruption");
            return false;
        }
        // InputControls processes a forced click in Baritone's server tick and releases it before
        // the next TaskRunner tick. Past this bound, an unchanged detached input cannot still land.
        pendingMutationReceipt = null;
        pendingMutationReceiptTicks = 0;
        return true;
    }

    private boolean settleMutationReceipt(Task child) {
        return child instanceof FarmMutationReceipt receipt
                && receipt.settlePendingTransition();
    }

    private static FarmTaskReason receiptReason(Task child) {
        if (child instanceof FarmMutationReceipt receipt
                && receipt.reason() != FarmTaskReason.NONE) {
            return receipt.reason();
        }
        return FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
    }

    private boolean consumeFinishedMutation(Task child) {
        FarmTaskReason accountingFailure = accountFinishedMutation(child);
        if (accountingFailure != FarmTaskReason.NONE) {
            fail(accountingFailure);
            return false;
        }
        return true;
    }

    /**
     * Accounts one settled exact receipt without constructing a terminal outcome. This split lets
     * {@link #fail(FarmTaskReason)} reconcile a world transition before it freezes counters and
     * durability into the outcome, without recursively calling itself.
     */
    private FarmTaskReason accountFinishedMutation(Task child) {
        FarmTaskReason result = FarmTaskReason.NONE;
        if (!(child instanceof FarmMutationReceipt receipt)
                || !child.isFinished()
                || !receipt.isSuccessful()) {
            result = receiptReason(child);
        } else if (child instanceof VerifiedBreakBlockTask) {
            if (!withinCumulativeMutationBudget(
                    cleared, 1, FarmPlotPolicy.MAX_CLEAR_ACTIONS)) {
                result = FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
            } else {
                cleared++;
            }
        } else if (child instanceof PlaceFarmDirtTask) {
            if (!withinCumulativeMutationBudget(
                    filled, 1, FarmPlotPolicy.MAX_FILL_ACTIONS)) {
                result = FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
            } else {
                filled++;
            }
        } else if (child instanceof PlaceFarmWaterTask) {
            sourcePlaced = true;
        } else if (child instanceof TillFarmBlockTask till) {
            if (till.freshTill()) {
                if (!withinCumulativeMutationBudget(
                        tilled, 1, FarmPlotPolicy.MAX_TILL_ACTIONS)) {
                    result = FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
                } else {
                    tilled++;
                }
            }
        } else {
            result = FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        }
        if (mutationChild == child) {
            mutationChild = null;
        }
        if (pendingMutationReceipt == child) {
            pendingMutationReceipt = null;
        }
        return result;
    }

    /** Settles both retained and still-active issued receipts before a terminal outcome is frozen. */
    private FarmTaskReason reconcileIssuedReceiptsForTerminalOutcome() {
        Task retained = pendingMutationReceipt;
        Task active = mutationChild;
        pendingMutationReceipt = null;
        pendingMutationReceiptTicks = 0;
        mutationChild = null;

        FarmTaskReason retainedFailure = settleIssuedReceiptForTerminal(retained);
        FarmTaskReason activeFailure = active == retained
                ? FarmTaskReason.NONE
                : settleIssuedReceiptForTerminal(active);
        return retainedFailure != FarmTaskReason.NONE ? retainedFailure : activeFailure;
    }

    private FarmTaskReason settleIssuedReceiptForTerminal(Task child) {
        if (!(child instanceof FarmMutationReceipt receipt) || !receipt.hasIssuedMutation()) {
            return FarmTaskReason.NONE;
        }
        if (!receipt.settlePendingTransition()) {
            return receiptReason(child);
        }
        // An unchanged issued input is safe to discard once movement/click controls are quiesced.
        return child.isFinished()
                ? accountFinishedMutation(child)
                : FarmTaskReason.NONE;
    }

    @Override
    protected void onStop(Task interruptTask) {
        resumeStartPending = false;
        if (!terminal) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
        quiesceMovementForStop();
    }

    @Override
    public void afterChildrenStopped() {
        releaseAcquisitionProtection();
    }

    private void quiesceMovementForStop() {
        if (controller != null) {
            controller.getInputControls().release(
                    com.player2.playerengine.automaton.api.utils.input.Input.CLICK_LEFT);
            controller.getInputControls().release(
                    com.player2.playerengine.automaton.api.utils.input.Input.CLICK_RIGHT);
            controller.getBaritone().getPathingBehavior().forceCancel();
        }
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

    public FarmTaskOutcome outcome() {
        if (terminalOutcome != null) {
            return terminalOutcome;
        }
        return new FarmTaskOutcome(
                false, false, FarmTaskReason.NONE, phase,
                commandDimension,
                resolvedCenter(),
                cleared, filled, tilled, sourcePlaced, waypointResult);
    }

    public Progress progress() {
        return new Progress(
                phase,
                resolvedCenter(),
                cleared,
                clearTotal,
                filled,
                fillTotal,
                tilled,
                tillTotal,
                resourceTicks,
                sitePlan != null && sitePlan.waterRequired());
    }

    public String describeProgress() {
        Progress current = progress();
        if (terminal && !successful) {
            return "farm setup failed: " + failureReason.controlledReason();
        }
        return switch (current.phase()) {
            case PRECHECK -> "checking farm prerequisites";
            case SELECT_SITE -> "selecting a safe farm site";
            case ACQUIRE_RESOURCES -> "acquiring farm resources";
            case REVALIDATE -> "revalidating the selected farm site";
            case CLEAR, OPEN_CENTER -> "clearing farm blocks " + current.cleared()
                    + "/" + current.clearTotal();
            case FILL -> "filling farm gaps " + current.filled() + "/" + current.fillTotal();
            case REGISTER_PREPARED -> "saving the prepared farm return anchor";
            case REPAIR -> "validating and repairing the returned farm plot";
            case PLACE_WATER -> "placing the farm water source";
            case TILL -> "tilling farm soil " + current.tilled() + "/" + current.tillTotal();
            case VERIFY -> "verifying the completed farm";
            case COMMIT -> "refreshing the completed farm waypoint";
            case DONE -> "farm setup complete";
            case FAILED -> "farm setup failed";
            default -> "farm setup in progress";
        };
    }

    public FarmSitePlan sitePlan() {
        return sitePlan;
    }

    public BlockPos commandAnchor() {
        return commandAnchor;
    }

    public String commandDimension() {
        return commandDimension;
    }

    public BlockPos exactCenter() {
        return exactCenter;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SetupFarmTask task
                && task.commandAnchor.equals(commandAnchor)
                && task.commandDimension.equals(commandDimension)
                && Objects.equals(task.exactCenter, exactCenter);
    }

    @Override
    protected String toDebugString() {
        return exactCenter == null
                ? "Set up deterministic farm near " + commandAnchor.toShortString()
                : "Set up deterministic farm at " + exactCenter.toShortString();
    }
}
