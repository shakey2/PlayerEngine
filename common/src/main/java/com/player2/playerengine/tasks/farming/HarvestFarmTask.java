package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
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
import com.player2.playerengine.tasks.base.TrackedTaskOutcomeProvider;
import com.player2.playerengine.tasks.movement.GetToEntityTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One finite mature-only pass over a typed EllieGPS farm.
 *
 * <p>Every crop action uses normal look/move/break input through
 * {@link VerifiedBreakBlockTask}. World state is never mutated directly.</p>
 */
public final class HarvestFarmTask extends Task implements TrackedTaskOutcomeProvider {
    public static final int WHOLE_TIMEOUT_TICKS = 6_000;
    public static final int PICKUP_TIMEOUT_TICKS = 200;
    public static final int PICKUP_HORIZONTAL_RADIUS = 8;
    public static final int PICKUP_VERTICAL_RADIUS = 4;
    public static final int MAX_PICKUP_ENTITIES = 64;

    private static final int PICKUP_EMPTY_SETTLE_TICKS = 5;

    enum DeadlineDecision {
        RUNNING,
        PICKUP_BUDGET_EXPIRED,
        WHOLE_TIMEOUT
    }

    /** Bounded progress snapshot shared by direct and agentic surfaces. */
    public record Progress(
            FarmTaskPhase phase,
            BlockPos center,
            int matureFound,
            int harvested,
            int raceSkipped,
            int denied,
            int pickupTicks,
            int pickupItemUnitsLeft,
            boolean pickupEntityOverflow) {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            if (center != null) {
                center = center.immutable();
            }
        }
    }

    private final BlockPos commandAnchor;
    private final String commandDimension;
    private final BlockPos exactCenter;
    private final AgenticRunState runState;
    private final FarmActionStateSource stateSource;

    private FarmTaskPhase phase = FarmTaskPhase.HARVEST_RESOLVE;
    private WaypointRecord farm;
    private BlockPos center;
    private List<BlockPos> matureCandidates = List.of();
    private int targetIndex;
    private VerifiedBreakBlockTask breakChild;
    private Task pickupMoveChild;
    private UUID pickupMoveTarget;
    private final Set<UUID> preExistingDropIds = new HashSet<>();
    private final Set<UUID> trackedDropIds = new LinkedHashSet<>();

    private boolean terminal;
    private boolean successful;
    private FarmTaskReason failureReason = FarmTaskReason.NONE;
    private HarvestFarmOutcome terminalOutcome;
    private WaypointMutationResult waypointResult;
    private com.player2.playerengine.agentic.elliegps.FarmWaypointObservation postPassObservation;
    private boolean commitAttempted;
    private boolean pickupEntityOverflow;
    private int wholeTicks;
    private int matureFound;
    private int harvested;
    private int raceSkipped;
    private int denied;
    private int pickupTicks;
    private int pickupEmptyTicks;
    private int pickupItemUnitsLeft;

    public HarvestFarmTask(BlockPos commandAnchor, String commandDimension) {
        this(commandAnchor, commandDimension, null, null, FarmActionStateSource.LIVE);
    }

    public HarvestFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            BlockPos exactCenter) {
        this(commandAnchor, commandDimension, exactCenter, null, FarmActionStateSource.LIVE);
    }

    public HarvestFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            BlockPos exactCenter,
            AgenticRunState runState) {
        this(commandAnchor, commandDimension, exactCenter, runState, FarmActionStateSource.LIVE);
    }

    HarvestFarmTask(
            BlockPos commandAnchor,
            String commandDimension,
            BlockPos exactCenter,
            AgenticRunState runState,
            FarmActionStateSource stateSource) {
        this.commandAnchor = Objects.requireNonNull(commandAnchor, "commandAnchor").immutable();
        String checkedDimension = Objects.requireNonNull(
                commandDimension, "commandDimension").strip();
        if (checkedDimension.isEmpty()) {
            throw new IllegalArgumentException("commandDimension cannot be blank");
        }
        this.commandDimension = checkedDimension;
        this.exactCenter = exactCenter == null ? null : exactCenter.immutable();
        this.runState = runState;
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource");
    }

    @Override
    protected void onStart() {
        phase = FarmTaskPhase.HARVEST_RESOLVE;
        farm = null;
        center = exactCenter;
        matureCandidates = List.of();
        targetIndex = 0;
        breakChild = null;
        pickupMoveChild = null;
        pickupMoveTarget = null;
        preExistingDropIds.clear();
        trackedDropIds.clear();
        terminal = false;
        successful = false;
        failureReason = FarmTaskReason.NONE;
        terminalOutcome = null;
        waypointResult = null;
        postPassObservation = null;
        commitAttempted = false;
        pickupEntityOverflow = false;
        wholeTicks = 0;
        matureFound = 0;
        harvested = 0;
        raceSkipped = 0;
        denied = 0;
        pickupTicks = 0;
        pickupEmptyTicks = 0;
        pickupItemUnitsLeft = 0;
        updateProgress();
    }

    @Override
    protected Task onTick() {
        if (terminal) {
            return null;
        }
        wholeTicks++;

        // The whole deadline is checked before every sub-budget. On a shared expiry tick it wins.
        if (deadlineDecision(wholeTicks, pickupTicks, phase) == DeadlineDecision.WHOLE_TIMEOUT) {
            if (phase == FarmTaskPhase.HARVEST_PICKUP && center != null) {
                pickupItemUnitsLeft = remainingItemUnits(observeEligibleDrops());
                markPickupDegradation();
            }
            fail(FarmTaskReason.HARVEST_TIMEOUT);
            return null;
        }
        if (!controller.getModSettings().getEllieGpsEnabled()) {
            fail(FarmTaskReason.ELLIEGPS_DISABLED);
            return null;
        }
        if (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return null;
        }
        if (!Objects.equals(commandDimension, currentDimension())) {
            fail(FarmTaskReason.DIMENSION_CHANGED);
            return null;
        }
        if (phase != FarmTaskPhase.HARVEST_RESOLVE) {
            if (!farmFootprintLoaded()) {
                fail(FarmTaskReason.CHUNK_UNLOADED);
                return null;
            }
            if (!FarmInteractionPostconditions.isStandaloneWaterSource(
                    controller.getWorld().getBlockState(center))) {
                fail(FarmTaskReason.STALE_CENTER);
                return null;
            }
        }

        Task next = switch (phase) {
            case HARVEST_RESOLVE -> tickResolve();
            case HARVEST_SCAN -> tickScan();
            case HARVEST_BREAK -> tickBreak();
            case HARVEST_PICKUP -> tickPickup();
            case HARVEST_RESCAN -> tickRescan();
            case COMMIT -> tickCommit();
            case PRECHECK, SELECT_SITE, ACQUIRE_RESOURCES, REVALIDATE, CLEAR, FILL,
                    OPEN_CENTER, REGISTER_PREPARED, REPAIR,
                    PLACE_WATER, TILL, VERIFY, DONE, FAILED -> {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                yield null;
            }
        };
        updateProgress();
        return next;
    }

    private Task tickResolve() {
        EllieGPSStore store = EllieGPSStore.get();

        WaypointRecord resolved;
        if (exactCenter != null) {
            resolved = store.byPosition(commandDimension, exactCenter);
            if (resolved == null) {
                fail(FarmTaskReason.FARM_NOT_FOUND);
                return null;
            }
            if (!WaypointTypes.FARM.equals(resolved.type)) {
                fail(FarmTaskReason.TYPE_CONFLICT);
                return null;
            }
            if (resolved.stale) {
                fail(FarmTaskReason.STALE_CENTER);
                return null;
            }
        } else {
            WaypointSearchResult search = WaypointSearchService.find(
                    "", commandDimension, Set.of(WaypointTypes.FARM), false,
                    WaypointSearchOrder.NEAREST, commandAnchor, 1);
            FarmTaskReason searchFailure = failureForSearch(search.status(), search.records().isEmpty());
            if (searchFailure != FarmTaskReason.NONE) {
                fail(searchFailure);
                return null;
            }
            resolved = search.records().get(0);
        }

        if (resolved.farmData() == null || !resolved.farmData().isSupportedVersion()) {
            fail(FarmTaskReason.UNSUPPORTED_DATA);
            return null;
        }
        if (resolved.id == null || resolved.id.isBlank()) {
            fail(FarmTaskReason.UNSUPPORTED_DATA);
            return null;
        }
        BlockPos resolvedCenter = resolved.canonicalBlockPos();
        if (resolvedCenter == null) {
            fail(FarmTaskReason.UNSUPPORTED_DATA);
            return null;
        }
        farm = resolved.copy();
        center = resolvedCenter.immutable();
        if (!farmFootprintLoaded()) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }
        if (!FarmInteractionPostconditions.isStandaloneWaterSource(
                controller.getWorld().getBlockState(center))) {
            fail(FarmTaskReason.STALE_CENTER);
            return null;
        }
        enterPhase(FarmTaskPhase.HARVEST_SCAN);
        return null;
    }

    private Task tickScan() {
        final FarmCropScanner.ScanResult scan;
        try {
            scan = FarmCropScanner.scan(controller.getWorld(), farm, stateSource);
        } catch (RuntimeException scanFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        FarmTaskReason scanFailure = failureForObservation(scan.observationResult().status());
        if (scanFailure != FarmTaskReason.NONE) {
            fail(scanFailure);
            return null;
        }
        matureCandidates = scan.matureCandidates();
        matureFound = matureCandidates.size();
        targetIndex = 0;
        if (matureFound > 0) {
            snapshotPreExistingDrops();
        }
        enterPhase(matureFound == 0
                ? FarmTaskPhase.HARVEST_RESCAN
                : FarmTaskPhase.HARVEST_BREAK);
        return null;
    }

    private Task tickBreak() {
        if (breakChild != null) {
            if (!breakChild.isFinished()) {
                return breakChild;
            }
            if (breakChild.isSuccessful()) {
                harvested++;
            } else if (breakChild.wasPreAttemptTargetDrift()) {
                raceSkipped++;
            } else if (breakChild.reason() == FarmTaskReason.CHUNK_UNLOADED
                    || breakChild.reason() == FarmTaskReason.DIMENSION_CHANGED
                    || breakChild.reason() == FarmTaskReason.STORE_UNAVAILABLE
                    || breakChild.reason() == FarmTaskReason.CANCELLED_OPERATOR) {
                fail(breakChild.reason());
                return null;
            } else {
                denied++;
            }
            targetIndex++;
            breakChild = null;
            // Return null once so the completed child is detached before selecting another target.
            return null;
        }

        while (targetIndex < matureCandidates.size()) {
            BlockPos target = matureCandidates.get(targetIndex);
            if (!controller.getChunkTracker().isChunkLoaded(target)) {
                fail(FarmTaskReason.CHUNK_UNLOADED);
                return null;
            }

            final BlockState actionState;
            try {
                actionState = Objects.requireNonNull(
                        stateSource.read(controller.getWorld(), target),
                        "farm state source returned null");
            } catch (RuntimeException stateFailure) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
            try {
                if (!isActionable(actionState)) {
                    raceSkipped++;
                    targetIndex++;
                    continue;
                }
            } catch (RuntimeException behaviorFailure) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }

            BlockPos stance = chooseHarvestStance(target);
            if (stance == null) {
                denied++;
                targetIndex++;
                continue;
            }
            breakChild = VerifiedBreakBlockTask.forMatureCrop(target, stance);
            return breakChild;
        }

        enterPhase(FarmTaskPhase.HARVEST_PICKUP);
        return null;
    }

    private Task tickPickup() {
        pickupTicks++;
        List<ItemEntity> active = observeEligibleDrops();
        pickupItemUnitsLeft = remainingItemUnits(active);

        if (deadlineDecision(wholeTicks, pickupTicks, phase)
                == DeadlineDecision.PICKUP_BUDGET_EXPIRED) {
            enterPhase(FarmTaskPhase.HARVEST_RESCAN);
            markPickupDegradation();
            return null;
        }

        if (active.isEmpty()) {
            pickupEmptyTicks++;
            pickupMoveChild = null;
            pickupMoveTarget = null;
            if (pickupEmptyTicks >= PICKUP_EMPTY_SETTLE_TICKS) {
                pickupItemUnitsLeft = 0;
                enterPhase(FarmTaskPhase.HARVEST_RESCAN);
                markPickupDegradation();
            }
            return null;
        }

        pickupEmptyTicks = 0;
        ItemEntity target = active.get(0);
        if (!target.getUUID().equals(pickupMoveTarget)) {
            pickupMoveTarget = target.getUUID();
            pickupMoveChild = new GetToEntityTask(target, 1.0);
        }
        return pickupMoveChild;
    }

    private Task tickRescan() {
        final FarmCropScanner.ScanResult scan;
        try {
            scan = FarmCropScanner.scan(controller.getWorld(), farm, stateSource);
        } catch (RuntimeException scanFailure) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        FarmTaskReason scanFailure = failureForObservation(scan.observationResult().status());
        if (scanFailure != FarmTaskReason.NONE) {
            fail(scanFailure);
            return null;
        }
        enterPhase(FarmTaskPhase.COMMIT);
        postPassObservation = scan.observationResult().observation();
        return null;
    }

    private Task tickCommit() {
        if (commitAttempted || postPassObservation == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        commitAttempted = true;

        EllieGPSStore store = EllieGPSStore.get();
        WaypointRecord current = store == null ? null : store.byPosition(commandDimension, center);
        if (store == null) {
            waypointResult = new WaypointMutationResult(
                    WaypointMutationStatus.FAILED_STORE_UNAVAILABLE, null, null);
        } else if (current == null || current.stale
                || !Objects.equals(current.id, farm.id)) {
            waypointResult = new WaypointMutationResult(
                    WaypointMutationStatus.NOT_FOUND, farm.copy(), null);
        } else if (!WaypointTypes.FARM.equals(current.type)) {
            waypointResult = new WaypointMutationResult(
                    WaypointMutationStatus.REJECTED_TYPE_CONFLICT, farm.copy(), current.copy());
        } else {
            try {
                waypointResult = FarmWaypointService.registerOrRefresh(
                        postPassObservation, current.origin);
            } catch (UncheckedIOException jsonFailure) {
                waypointResult = new WaypointMutationResult(
                        WaypointMutationStatus.FAILED_JSON_COMMIT, current.copy(), current.copy());
            } catch (RuntimeException commitFailure) {
                // Checked commit exceptions are deliberately distilled; Throwable content never
                // enters player/model feedback.
                waypointResult = new WaypointMutationResult(
                        WaypointMutationStatus.FAILED_JSON_COMMIT, current.copy(), current.copy());
            }
        }

        if (waypointResult == null) {
            fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
            return null;
        }
        FarmTaskReason mutationFailure = failureForMutation(waypointResult.status());
        if (mutationFailure != FarmTaskReason.NONE) {
            markIndexDegradation();
            fail(mutationFailure);
            return null;
        }
        markIndexDegradation();
        if (denied > 0) {
            failAt(
                    harvested == 0 && denied == matureFound
                            ? FarmTaskReason.ALL_DENIED
                            : FarmTaskReason.PARTIAL_DENIED,
                    FarmTaskPhase.HARVEST_BREAK);
            return null;
        }
        succeed();
        return null;
    }

    static FarmTaskReason failureForSearch(WaypointSearchStatus status, boolean empty) {
        return switch (Objects.requireNonNull(status, "status")) {
            case FAILED_STORE_UNAVAILABLE -> FarmTaskReason.STORE_UNAVAILABLE;
            case FAILED_SCAN_LIMIT -> FarmTaskReason.SEARCH_SCAN_LIMIT;
            case FAILED_SEARCH_ERROR -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
            case INDEXED, AUTHORITATIVE_SPATIAL, FULL_STORE_FALLBACK ->
                    empty ? FarmTaskReason.FARM_NOT_FOUND : FarmTaskReason.NONE;
        };
    }

    /** Exact production action gate, exposed package-private for deterministic race fixtures. */
    static boolean isActionable(BlockState state) {
        Objects.requireNonNull(state, "state");
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT.resolve(state).orElse(null);
        return behavior != null
                && behavior.isMature(state)
                && behavior.action() == HarvestBehavior.HarvestAction.BREAK;
    }

    static DeadlineDecision deadlineDecision(
            int wholeTicks,
            int pickupTicks,
            FarmTaskPhase phase) {
        if (wholeTicks < 0 || pickupTicks < 0) {
            throw new IllegalArgumentException("tick counts cannot be negative");
        }
        Objects.requireNonNull(phase, "phase");
        if (wholeTicks >= WHOLE_TIMEOUT_TICKS) {
            return DeadlineDecision.WHOLE_TIMEOUT;
        }
        if (phase == FarmTaskPhase.HARVEST_PICKUP
                && pickupTicks >= PICKUP_TIMEOUT_TICKS) {
            return DeadlineDecision.PICKUP_BUDGET_EXPIRED;
        }
        return DeadlineDecision.RUNNING;
    }

    static FarmTaskReason failureForObservation(FarmObservationStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case OBSERVED -> FarmTaskReason.NONE;
            case UNLOADED -> FarmTaskReason.CHUNK_UNLOADED;
            case STALE_CENTER -> FarmTaskReason.STALE_CENTER;
            case UNSUPPORTED_DATA -> FarmTaskReason.UNSUPPORTED_DATA;
            case HANDLER_UNAVAILABLE -> FarmTaskReason.INTERNAL_CONTRACT_FAILURE;
        };
    }

    static FarmTaskReason failureForMutation(WaypointMutationStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case COMMITTED, COMMITTED_INDEX_DEGRADED, NO_CHANGE, NO_CHANGE_INDEX_DEGRADED ->
                    FarmTaskReason.NONE;
            case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED -> FarmTaskReason.FARM_NOT_FOUND;
            case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT -> FarmTaskReason.TYPE_CONFLICT;
            case FAILED_STORE_UNAVAILABLE -> FarmTaskReason.STORE_UNAVAILABLE;
            case FAILED_JSON_COMMIT -> FarmTaskReason.WAYPOINT_JSON_FAILURE;
        };
    }

    private void snapshotPreExistingDrops() {
        preExistingDropIds.clear();
        for (ItemEntity item : itemEntitiesInEnvelope()) {
            preExistingDropIds.add(item.getUUID());
        }
    }

    private List<ItemEntity> observeEligibleDrops() {
        List<ItemEntity> candidates = itemEntitiesInEnvelope().stream()
                .filter(entity -> !preExistingDropIds.contains(entity.getUUID()))
                .sorted(dropComparator(center))
                .toList();
        pickupEntityOverflow |= trackEligibleDropIds(
                candidates.stream().map(ItemEntity::getUUID).toList(),
                preExistingDropIds,
                trackedDropIds);
        return candidates.stream()
                .filter(entity -> trackedDropIds.contains(entity.getUUID()))
                .toList();
    }

    static boolean trackEligibleDropIds(
            Iterable<UUID> observed,
            Set<UUID> preExisting,
            Set<UUID> tracked) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(preExisting, "preExisting");
        Objects.requireNonNull(tracked, "tracked");
        boolean overflow = false;
        for (UUID id : observed) {
            UUID checked = Objects.requireNonNull(id, "drop UUID");
            if (preExisting.contains(checked) || tracked.contains(checked)) {
                continue;
            }
            if (tracked.size() < MAX_PICKUP_ENTITIES) {
                tracked.add(checked);
            } else {
                overflow = true;
            }
        }
        return overflow;
    }

    private List<ItemEntity> itemEntitiesInEnvelope() {
        if (center == null) {
            return List.of();
        }
        ServerLevel level = controller.getWorld();
        Vec3 origin = Vec3.atCenterOf(center);
        AABB bounds = new AABB(origin, origin).inflate(
                PICKUP_HORIZONTAL_RADIUS, PICKUP_VERTICAL_RADIUS, PICKUP_HORIZONTAL_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, bounds, entity ->
                entity.isAlive()
                        && !entity.isRemoved()
                        && isInsidePickupEnvelope(center, entity.position()));
    }

    static boolean isInsidePickupEnvelope(BlockPos center, Vec3 position) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(position, "position");
        Vec3 origin = Vec3.atCenterOf(center);
        double dx = position.x - origin.x;
        double dz = position.z - origin.z;
        return dx * dx + dz * dz
                <= (double) PICKUP_HORIZONTAL_RADIUS * PICKUP_HORIZONTAL_RADIUS
                && Math.abs(position.y - origin.y) <= PICKUP_VERTICAL_RADIUS;
    }

    static Comparator<ItemEntity> dropComparator(BlockPos center) {
        Vec3 origin = Vec3.atCenterOf(Objects.requireNonNull(center, "center"));
        return Comparator
                .comparingDouble((ItemEntity entity) -> entity.distanceToSqr(origin))
                .thenComparing(ItemEntity::getUUID);
    }

    private static int remainingItemUnits(List<ItemEntity> active) {
        return saturatingItemUnits(active.stream()
                .map(entity -> Math.max(0, entity.getItem().getCount()))
                .toList());
    }

    static int saturatingItemUnits(Iterable<Integer> counts) {
        Objects.requireNonNull(counts, "counts");
        long total = 0;
        for (Integer count : counts) {
            total += Math.max(0, Objects.requireNonNull(count, "item count"));
            if (total >= 999) {
                return 999;
            }
        }
        return (int) total;
    }

    private BlockPos chooseHarvestStance(BlockPos target) {
        int edge = FarmPlotPolicy.HYDRATION_RADIUS + 1;
        ArrayList<BlockPos> candidates = new ArrayList<>(4);
        candidates.add(new BlockPos(target.getX(), center.getY() + 1, center.getZ() - edge));
        candidates.add(new BlockPos(center.getX() + edge, center.getY() + 1, target.getZ()));
        candidates.add(new BlockPos(target.getX(), center.getY() + 1, center.getZ() + edge));
        candidates.add(new BlockPos(center.getX() - edge, center.getY() + 1, target.getZ()));
        candidates.sort(Comparator
                .comparingLong((BlockPos stance) -> FarmPlotGeometry.horizontalDistanceSquared(
                        stance, target))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        for (BlockPos stance : candidates) {
            if (openStance(stance)) {
                return stance.immutable();
            }
        }
        return null;
    }

    private boolean openStance(BlockPos stance) {
        BlockPos head = stance.above();
        BlockPos supportPos = stance.below();
        if (!controller.getChunkTracker().isChunkLoaded(stance)
                || !controller.getChunkTracker().isChunkLoaded(head)
                || !controller.getChunkTracker().isChunkLoaded(supportPos)) {
            return false;
        }
        BlockState feetState = controller.getWorld().getBlockState(stance);
        BlockState headState = controller.getWorld().getBlockState(head);
        BlockState support = controller.getWorld().getBlockState(supportPos);
        return feetState.isAir()
                && headState.isAir()
                && !support.getCollisionShape(controller.getWorld(), supportPos).isEmpty()
                && support.getFluidState().isEmpty();
    }

    private boolean farmFootprintLoaded() {
        if (center == null) {
            return false;
        }
        for (BlockPos soil : FarmPlotGeometry.allCells(center)) {
            if (!controller.getChunkTracker().isChunkLoaded(soil)
                    || !controller.getChunkTracker().isChunkLoaded(soil.above())) {
                return false;
            }
        }
        return true;
    }

    private void markPickupDegradation() {
        if (runState != null && (pickupItemUnitsLeft > 0 || pickupEntityOverflow)) {
            runState.setFarmDegraded(DegradationLevel.PARTIAL, "farm_pickup_incomplete");
        }
    }

    private void markIndexDegradation() {
        if (runState == null || waypointResult == null) {
            return;
        }
        if (waypointResult.status() == WaypointMutationStatus.COMMITTED_INDEX_DEGRADED
                || waypointResult.status() == WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED
                || waypointResult.status() == WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED) {
            runState.setWaypointDegraded(
                    DegradationLevel.PARTIAL, "farm_waypoint_index_degraded");
        }
    }

    private void enterPhase(FarmTaskPhase next) {
        phase = Objects.requireNonNull(next, "next");
        breakChild = null;
        pickupMoveChild = null;
        pickupMoveTarget = null;
    }

    private String currentDimension() {
        return controller.getWorld().dimension().location().toString();
    }

    private void succeed() {
        terminal = true;
        successful = true;
        failureReason = FarmTaskReason.NONE;
        phase = FarmTaskPhase.DONE;
        terminalOutcome = new HarvestFarmOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                commandDimension, center, matureFound, harvested, raceSkipped, denied,
                pickupItemUnitsLeft, pickupEntityOverflow, waypointResult);
        updateProgress();
    }

    private void fail(FarmTaskReason reason) {
        failAt(reason, phase == FarmTaskPhase.DONE ? FarmTaskPhase.FAILED : phase);
    }

    private void failAt(FarmTaskReason reason, FarmTaskPhase failurePhase) {
        if (terminal) {
            return;
        }
        terminal = true;
        successful = false;
        failureReason = Objects.requireNonNull(reason, "reason");
        failurePhase = Objects.requireNonNull(failurePhase, "failurePhase");
        if (failurePhase == FarmTaskPhase.DONE) {
            failurePhase = FarmTaskPhase.FAILED;
        }
        phase = failurePhase;
        terminalOutcome = new HarvestFarmOutcome(
                true, false, failureReason, failurePhase,
                commandDimension, center, matureFound, harvested, raceSkipped, denied,
                pickupItemUnitsLeft, pickupEntityOverflow, waypointResult);
        updateProgress();
    }

    private void updateProgress() {
        if (runState != null && !runState.isTerminal()) {
            runState.setFarmProgress(describeProgress());
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (!terminal && phase == FarmTaskPhase.HARVEST_PICKUP
                && controller != null && center != null) {
            pickupItemUnitsLeft = remainingItemUnits(observeEligibleDrops());
            markPickupDegradation();
        }
        if (!terminal) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
        if (controller != null) {
            controller.getInputControls().release(
                    com.player2.playerengine.automaton.api.utils.input.Input.CLICK_LEFT);
            controller.getBaritone().getPathingBehavior().forceCancel();
            if (controller.getBaritone().getCustomGoalProcess().isActive()) {
                controller.getBaritone().getCustomGoalProcess().onLostControl();
            }
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

    public HarvestFarmOutcome outcome() {
        if (terminalOutcome != null) {
            return terminalOutcome;
        }
        return new HarvestFarmOutcome(
                false, false, FarmTaskReason.NONE, phase,
                commandDimension, center, matureFound, harvested, raceSkipped, denied,
                pickupItemUnitsLeft, pickupEntityOverflow, waypointResult);
    }

    public Progress progress() {
        return new Progress(
                phase, center, matureFound, harvested, raceSkipped, denied,
                pickupTicks, pickupItemUnitsLeft, pickupEntityOverflow);
    }

    public String describeProgress() {
        String centerText = center == null ? "unresolved" : center.toShortString();
        return "phase=" + phase.name().toLowerCase(java.util.Locale.ROOT)
                + " center=" + centerText
                + " mature=" + matureFound
                + " harvested=" + harvested
                + " skipped=" + raceSkipped
                + " denied=" + denied
                + " pickup_ticks=" + pickupTicks
                + " pickup_left=" + pickupItemUnitsLeft
                + " pickup_overflow=" + pickupEntityOverflow;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof HarvestFarmTask task
                && task.commandAnchor.equals(commandAnchor)
                && task.commandDimension.equals(commandDimension)
                && Objects.equals(task.exactCenter, exactCenter);
    }

    @Override
    protected String toDebugString() {
        return exactCenter == null
                ? "Harvest nearest EllieGPS farm"
                : "Harvest EllieGPS farm at " + exactCenter.toShortString();
    }
}
