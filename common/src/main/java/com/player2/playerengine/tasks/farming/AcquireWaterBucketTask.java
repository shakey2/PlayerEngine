package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.automaton.api.utils.Rotation;
import com.player2.playerengine.automaton.api.utils.input.Input;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, verified acquisition of one water bucket through the NPC's normal use-item path.
 *
 * <p>The selected farm center and its planner-validated surface stance are immutable. Source
 * discovery is limited to three 600-tick legs and exposed surface water no farther than 96 blocks
 * from that center. Once selected, both source and stance are frozen; a later world-state change is
 * a controlled failure rather than an implicit retarget.
 */
public final class AcquireWaterBucketTask extends Task {
    enum SearchWindowCompletion {
        START_WANDER,
        ADVANCE_LEG,
        INVALID
    }

    private static final int WANDER_RESERVED_TICKS_PER_LEG = 160;
    private static final long SEARCH_LEG_WALL_CLOCK_MS = WANDER_RESERVED_TICKS_PER_LEG * 50L;
    private static final int RETRY_DELAY_TICKS = 10;
    static final int LOCAL_SCAN_HORIZONTAL_RADIUS = 10;
    static final int LOCAL_SCAN_VERTICAL_RADIUS = 8;
    static final int SOURCE_SCAN_BLOCKS_PER_TICK = 52;
    static final int MAX_SCANNER_CANDIDATES_PER_WINDOW = 256;
    // Candidate state (1) + protected source envelope (7 * 2) + at most eight
    // standability probes (8 * 3). This intentionally over-counts short-circuit paths.
    static final int MAX_WORLD_READS_PER_SCANNED_POSITION = 39;
    private static final int RETURN_TO_BOUND_RESERVE_TICKS = 100;

    private final BlockPos anchor;
    private final BlockPos surfaceReturnStance;

    private String dimension;
    private BlockPos source;
    private BlockPos stance;
    private BlockState expectedSource;
    private boolean behaviourPushed;
    private boolean terminal;
    private boolean successful;
    private FarmTaskReason reason = FarmTaskReason.NONE;
    private int searchLeg;
    private int searchTicksInLeg;
    private boolean wanderStartedInLeg;
    private boolean postWanderScan;
    private int interactionTicks;
    private int attempts;
    private int ticksSinceAttempt;
    private int emptyBucketsBefore;
    private int waterBucketsBefore;
    private boolean inventoryBaselineCaptured;
    private BlockPos sourceScanCenter;
    private int sourceScanIndex;
    private List<BlockPos> scannerCandidates = List.of();
    private int scannerCandidateIndex;

    public AcquireWaterBucketTask(BlockPos anchor) {
        this(anchor, Objects.requireNonNull(anchor, "anchor").above());
    }

    public AcquireWaterBucketTask(BlockPos anchor, BlockPos surfaceReturnStance) {
        this.anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        this.surfaceReturnStance = Objects.requireNonNull(
                surfaceReturnStance, "surfaceReturnStance").immutable();
    }

    @Override
    protected void onStart() {
        this.dimension = currentDimension();
        this.source = null;
        this.stance = null;
        this.expectedSource = null;
        this.terminal = false;
        this.successful = false;
        this.reason = FarmTaskReason.NONE;
        this.searchLeg = 0;
        this.searchTicksInLeg = 0;
        this.wanderStartedInLeg = false;
        this.postWanderScan = false;
        this.interactionTicks = 0;
        this.attempts = 0;
        this.ticksSinceAttempt = RETRY_DELAY_TICKS;
        this.inventoryBaselineCaptured = false;
        this.behaviourPushed = false;
        resetSourceScan();

        if (this.controller.getItemStorage().getItemCount(Items.WATER_BUCKET) > 0) {
            succeed();
            return;
        }
        if (this.controller.getItemStorage().getItemCount(Items.BUCKET) < 1) {
            fail(FarmTaskReason.BUCKET_UNAVAILABLE);
            return;
        }
        if (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return;
        }

        this.controller.getBehaviour().push();
        this.behaviourPushed = true;
        this.controller.getBehaviour().setRayTracingFluidHandling(
                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY);
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
        if (EllieGPSStore.get() == null || PlayerPlacedBlockStore.get() == null) {
            fail(FarmTaskReason.STORE_UNAVAILABLE);
            return null;
        }

        if (source == null) {
            if (searchLeg >= FarmPlotPolicy.WATER_SEARCH_LEGS) {
                fail(FarmTaskReason.WATER_SOURCE_NOT_FOUND);
                return null;
            }
            // Count every active parent tick, including wander and return-to-bound child ticks.
            // User-chain suspension pauses this budget instead of consuming the reserved post-scan.
            searchTicksInLeg++;
            if (searchTicksInLeg >= FarmPlotPolicy.WATER_SEARCH_TICKS_PER_LEG) {
                advanceSearchLeg();
                return null;
            }
            Task child = getSub();
            if (!isPlayerAtLiveSurface()) {
                if (wanderStartedInLeg && !postWanderScan) {
                    postWanderScan = true;
                }
                resetSourceScan();
                this.setDebugState("Returning to the selected farm surface before water search");
                return new GetToBlockTask(surfaceReturnStance);
            }
            if (wanderStartedInLeg && !postWanderScan
                    && child instanceof TimeoutWanderTask wander) {
                if (!wander.isFinished()
                        && searchTicksInLeg < FarmPlotPolicy.WATER_SEARCH_TICKS_PER_LEG
                                - postScanReserveTicks()) {
                    return wander;
                }
                postWanderScan = true;
                resetSourceScan();
                // Detach the completed wander before beginning its final local scan.
                return null;
            }
            if (!withinSearchRadius(this.controller.getPlayer().blockPosition())) {
                this.setDebugState("Returning inside bounded water search");
                return new GetToBlockTask(surfaceReturnStance);
            }
            SourceSearchBatch batch = findSourceBatch();
            if (batch.selected().isPresent()) {
                SourceAndStance pair = batch.selected().get();
                this.source = pair.source();
                this.stance = pair.stance();
                this.expectedSource = this.controller.getWorld().getBlockState(source);
                this.interactionTicks = 0;
                this.setDebugState("Moving to verified water source");
                return new GetToBlockTask(stance);
            }
            if (!batch.complete()) {
                this.setDebugState("Scanning a bounded local area for water (leg "
                        + (searchLeg + 1) + "/" + FarmPlotPolicy.WATER_SEARCH_LEGS + ")");
                return null;
            }
            return switch (windowCompletion(wanderStartedInLeg, postWanderScan)) {
                case START_WANDER -> {
                    wanderStartedInLeg = true;
                    float wanderDistance = boundedWanderDistance(
                            anchor,
                            this.controller.getPlayer().blockPosition(),
                            32.0F + searchLeg * 16.0F,
                            FarmPlotPolicy.WATER_SEARCH_MAX_DISTANCE);
                    if (wanderDistance < 1.0F) {
                        postWanderScan = true;
                        resetSourceScan();
                        yield null;
                    }
                    this.setDebugState("Searching for water (leg " + (searchLeg + 1) + "/"
                            + FarmPlotPolicy.WATER_SEARCH_LEGS + ")");
                    yield new TimeoutWanderTask(
                            wanderDistance, false, SEARCH_LEG_WALL_CLOCK_MS);
                }
                case ADVANCE_LEG -> {
                    advanceSearchLeg();
                    yield null;
                }
                case INVALID -> {
                    fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                    yield null;
                }
            };
        }

        if (inventoryBaselineCaptured) {
            int emptyAfter = count(Items.BUCKET);
            int waterAfter = count(Items.WATER_BUCKET);
            if (FarmInteractionPostconditions.waterPickupInventoryDelta(
                    emptyBucketsBefore, emptyAfter, waterBucketsBefore, waterAfter)) {
                succeed();
                return null;
            }
            if (emptyAfter != emptyBucketsBefore || waterAfter != waterBucketsBefore) {
                fail(FarmTaskReason.INTERNAL_CONTRACT_FAILURE);
                return null;
            }
        }

        if (!this.controller.getChunkTracker().isChunkLoaded(source)
                || !this.controller.getChunkTracker().isChunkLoaded(stance)) {
            fail(FarmTaskReason.CHUNK_UNLOADED);
            return null;
        }

        if (!FarmStanceNavigation.isAtFrozenStance(this.controller, stance)) {
            this.setDebugState("Moving to fixed water stance");
            return new GetToBlockTask(stance);
        }
        interactionTicks = advanceInteractionTicks(interactionTicks, true);
        ticksSinceAttempt++;
        if (interactionTicks > FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!isSafeSource(source)
                || !this.controller.getWorld().getBlockState(source).equals(expectedSource)) {
            fail(FarmTaskReason.MANIFEST_DRIFT);
            return null;
        }
        if (attempts >= FarmPlotPolicy.MUTATION_MAX_ATTEMPTS) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (ticksSinceAttempt < RETRY_DELAY_TICKS) {
            return null;
        }
        if (!this.controller.getSlotHandler().forceEquipItem(Items.BUCKET)
                || !this.controller.getPlayer().getMainHandItem().is(Items.BUCKET)) {
            fail(FarmTaskReason.BUCKET_UNAVAILABLE);
            return null;
        }

        Optional<Rotation> reach = LookHelper.getReach(this.controller, source);
        if (reach.isEmpty()) {
            fail(FarmTaskReason.INTERACTION_DENIED);
            return null;
        }
        if (!LookHelper.isLookingAt(this.controller, reach.get())) {
            LookHelper.lookAt(this.controller, reach.get());
            return null;
        }

        HitResult mouseOver = controller.getBaritone().getEntityContext().objectMouseOver();
        Optional<InteractionResult> interaction =
                FarmInteractionDispatcher.dispatchMainHandBlockThenItemAtBlock(
                controller,
                mouseOver,
                source,
                () -> {
                    if (!inventoryBaselineCaptured) {
                        emptyBucketsBefore = count(Items.BUCKET);
                        waterBucketsBefore = count(Items.WATER_BUCKET);
                        inventoryBaselineCaptured = true;
                    }
                    attempts++;
                    ticksSinceAttempt = 0;
                });
        if (interaction.isEmpty()) {
            LookHelper.lookAt(controller, reach.get());
            return null;
        }
        this.setDebugState("Picking up verified water source");
        return null;
    }

    private SourceSearchBatch findSourceBatch() {
        BlockPos playerCenter = this.controller.getPlayer().blockPosition().immutable();
        if (sourceScanCenter == null) {
            sourceScanCenter = freezeSourceScanCenter(
                    null,
                    surfaceScanCenter(playerCenter, surfaceHeight(playerCenter)));
            sourceScanIndex = 0;
            scannerCandidateIndex = 0;
            ArrayList<BlockPos> boundedKnown = new ArrayList<>(
                    this.controller.getBlockScanner().getKnownLocationsBounded(
                            MAX_SCANNER_CANDIDATES_PER_WINDOW, Blocks.WATER));
            boundedKnown.removeIf(candidate -> !withinSearchRadius(candidate)
                    || !this.controller.getChunkTracker().isChunkLoaded(candidate)
                    || !isExposedSurfaceSource(candidate));
            boundedKnown.sort(sourcePriorityComparator(anchor));
            scannerCandidates = List.copyOf(boundedKnown);
        }

        int visited = 0;
        int volume = localScanVolume();
        while ((scannerCandidateIndex < scannerCandidates.size() || sourceScanIndex < volume)
                && visited < SOURCE_SCAN_BLOCKS_PER_TICK) {
            BlockPos candidate = scannerCandidateIndex < scannerCandidates.size()
                    ? scannerCandidates.get(scannerCandidateIndex++)
                    : localScanPosition(sourceScanCenter, sourceScanIndex++);
            visited++;
            if (!withinSearchRadius(candidate)
                    || !this.controller.getChunkTracker().isChunkLoaded(candidate)
                    || !isExposedSurfaceSource(candidate)) {
                continue;
            }
            BlockState state = this.controller.getWorld().getBlockState(candidate);
            if (!state.is(Blocks.WATER)
                    || !state.getFluidState().isSource()
                    || !(state.getBlock() instanceof net.minecraft.world.level.block.BucketPickup)
                    || !isSafeSource(candidate, state)) {
                continue;
            }
            BlockPos selectedStance = chooseStance(candidate);
            if (selectedStance != null) {
                return new SourceSearchBatch(
                        Optional.of(new SourceAndStance(candidate.immutable(), selectedStance)),
                        scannerCandidateIndex >= scannerCandidates.size()
                                && sourceScanIndex >= volume);
            }
        }
        return new SourceSearchBatch(
                Optional.empty(),
                scannerCandidateIndex >= scannerCandidates.size() && sourceScanIndex >= volume);
    }

    private boolean isSafeSource(BlockPos candidate) {
        if (!this.controller.getChunkTracker().isChunkLoaded(candidate)
                || isKnownFarmCenter(candidate)) {
            return false;
        }
        BlockState state = this.controller.getWorld().getBlockState(candidate);
        return isSafeSource(candidate, state);
    }

    private boolean isSafeSource(BlockPos candidate, BlockState state) {
        if (isKnownFarmCenter(candidate) || !isExposedSurfaceSource(candidate)) {
            return false;
        }
        if (!state.is(Blocks.WATER)
                || !state.getFluidState().isSource()
                || !(state.getBlock() instanceof net.minecraft.world.level.block.BucketPickup)) {
            return false;
        }
        for (BlockPos envelope : sourceEnvelope(candidate)) {
            if (!this.controller.getChunkTracker().isChunkLoaded(envelope)
                    || isProtected(envelope)
                    || this.controller.getWorld().getBlockState(envelope).hasBlockEntity()
                    || this.controller.getWorld().getBlockEntity(envelope) != null) {
                return false;
            }
        }
        return true;
    }

    private void advanceSearchLeg() {
        searchLeg++;
        searchTicksInLeg = 0;
        wanderStartedInLeg = false;
        postWanderScan = false;
        resetSourceScan();
    }

    private void resetSourceScan() {
        sourceScanCenter = null;
        sourceScanIndex = 0;
        scannerCandidates = List.of();
        scannerCandidateIndex = 0;
    }

    static int localScanVolume() {
        int width = LOCAL_SCAN_HORIZONTAL_RADIUS * 2 + 1;
        int height = LOCAL_SCAN_VERTICAL_RADIUS * 2 + 1;
        return width * width * height;
    }

    static BlockPos freezeSourceScanCenter(BlockPos existing, BlockPos current) {
        Objects.requireNonNull(current, "current");
        return existing == null ? current.immutable() : existing;
    }

    static int wanderReservedTicksPerLeg() {
        return WANDER_RESERVED_TICKS_PER_LEG;
    }

    static int postScanReserveTicks() {
        int scanCandidates = localScanVolume() + MAX_SCANNER_CANDIDATES_PER_WINDOW;
        int scanTicks = (scanCandidates + SOURCE_SCAN_BLOCKS_PER_TICK - 1)
                / SOURCE_SCAN_BLOCKS_PER_TICK;
        // Two transition ticks: detach the wander, then observe a completed return child.
        return scanTicks + RETURN_TO_BOUND_RESERVE_TICKS + 2;
    }

    static int returnToBoundReserveTicks() {
        return RETURN_TO_BOUND_RESERVE_TICKS;
    }

    static int latestReservedPostScanWorkTick() {
        int scanTicks = postScanReserveTicks() - RETURN_TO_BOUND_RESERVE_TICKS - 2;
        int detachTick = FarmPlotPolicy.WATER_SEARCH_TICKS_PER_LEG - postScanReserveTicks();
        return detachTick + RETURN_TO_BOUND_RESERVE_TICKS + scanTicks;
    }

    static float boundedWanderDistance(
            BlockPos anchor,
            BlockPos current,
            float desired,
            double maxAnchorDistance) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(current, "current");
        if (desired < 0.0F || maxAnchorDistance <= 0.0) {
            throw new IllegalArgumentException("water-search wander bounds are invalid");
        }
        double currentDistance = Math.sqrt(current.distSqr(anchor));
        double available = Math.max(0.0, maxAnchorDistance - currentDistance - 4.0);
        return (float) Math.min(desired, available);
    }

    static SearchWindowCompletion windowCompletion(
            boolean wanderStartedInLeg,
            boolean postWanderScan) {
        if (!wanderStartedInLeg && !postWanderScan) {
            return SearchWindowCompletion.START_WANDER;
        }
        if (wanderStartedInLeg && postWanderScan) {
            return SearchWindowCompletion.ADVANCE_LEG;
        }
        return SearchWindowCompletion.INVALID;
    }

    static BlockPos localScanPosition(BlockPos center, int index) {
        Objects.requireNonNull(center, "center");
        int volume = localScanVolume();
        if (index < 0 || index >= volume) {
            throw new IllegalArgumentException("local water scan index is out of range");
        }
        int width = LOCAL_SCAN_HORIZONTAL_RADIUS * 2 + 1;
        int plane = width * width;
        int dy = centerOutVerticalOffset(index / plane);
        int withinPlane = index % plane;
        int dz = withinPlane / width - LOCAL_SCAN_HORIZONTAL_RADIUS;
        int dx = withinPlane % width - LOCAL_SCAN_HORIZONTAL_RADIUS;
        return center.offset(dx, dy, dz).immutable();
    }

    static int centerOutVerticalOffset(int planeIndex) {
        int planes = LOCAL_SCAN_VERTICAL_RADIUS * 2 + 1;
        if (planeIndex < 0 || planeIndex >= planes) {
            throw new IllegalArgumentException("local water scan plane is out of range");
        }
        if (planeIndex == 0) {
            return 0;
        }
        int magnitude = (planeIndex + 1) / 2;
        return (planeIndex & 1) == 1 ? -magnitude : magnitude;
    }

    static BlockPos surfaceScanCenter(BlockPos horizontalCenter, int surfaceHeight) {
        Objects.requireNonNull(horizontalCenter, "horizontalCenter");
        return new BlockPos(horizontalCenter.getX(), surfaceHeight - 1, horizontalCenter.getZ());
    }

    static boolean isExposedSurfaceSource(int sourceY, int surfaceHeight) {
        return sourceY + 1 == surfaceHeight;
    }

    static boolean isAtLiveSurface(int feetY, int surfaceHeight) {
        return Math.abs(feetY - surfaceHeight) <= FarmPlotPolicy.WATER_SURFACE_FEET_TOLERANCE;
    }

    static int advanceInteractionTicks(int currentTicks, boolean atFrozenStance) {
        if (currentTicks < 0) {
            throw new IllegalArgumentException("interaction ticks cannot be negative");
        }
        return atFrozenStance ? Math.addExact(currentTicks, 1) : currentTicks;
    }

    static Comparator<BlockPos> sourcePriorityComparator(BlockPos anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return Comparator
                .comparingInt((BlockPos candidate) -> Math.abs(candidate.getY() - anchor.getY()))
                .thenComparingLong(candidate -> FarmPlotGeometry.horizontalDistanceSquared(
                        candidate, anchor))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
    }

    private BlockPos chooseStance(BlockPos candidate) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = candidate.relative(direction);
            if (isStandable(neighbor)) {
                return neighbor.immutable();
            }
            BlockPos bankTop = neighbor.above();
            if (isStandable(bankTop)) {
                return bankTop.immutable();
            }
        }
        return null;
    }

    private boolean isStandable(BlockPos position) {
        Level level = this.controller.getWorld();
        return this.controller.getChunkTracker().isChunkLoaded(position)
                && this.controller.getChunkTracker().isChunkLoaded(position.below())
                && level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
                && level.getBlockState(position.below()).isCollisionShapeFullBlock(level, position.below())
                && !isProtected(position)
                && !isProtected(position.above())
                && !isProtected(position.below());
    }

    private boolean isKnownFarmCenter(BlockPos candidate) {
        EllieGPSStore store = EllieGPSStore.get();
        return store == null
                || store.hasTypeAtPosition(WaypointTypes.FARM, dimension, candidate);
    }

    private List<BlockPos> sourceEnvelope(BlockPos candidate) {
        ArrayList<BlockPos> result = new ArrayList<>(7);
        result.add(candidate);
        result.add(candidate.above());
        result.add(candidate.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            result.add(candidate.relative(direction));
        }
        return result;
    }

    private boolean withinSearchRadius(BlockPos position) {
        double max = FarmPlotPolicy.WATER_SEARCH_MAX_DISTANCE;
        return position.distSqr(anchor) <= max * max;
    }

    private int surfaceHeight(BlockPos position) {
        return this.controller.getWorld().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, position.getX(), position.getZ());
    }

    private boolean isExposedSurfaceSource(BlockPos candidate) {
        return isExposedSurfaceSource(candidate.getY(), surfaceHeight(candidate));
    }

    private boolean isPlayerAtLiveSurface() {
        BlockPos feet = this.controller.getBaritone().getEntityContext().feetPos();
        return feet.equals(surfaceReturnStance)
                || isAtLiveSurface(feet.getY(), surfaceHeight(feet));
    }

    private boolean isProtected(BlockPos pos) {
        PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
        return store == null || store.contains(dimension, pos);
    }

    private int count(net.minecraft.world.item.Item item) {
        return this.controller.getItemStorage().getItemCount(item);
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
        releaseControls();
        if (!terminal && interruptTask == null) {
            fail(FarmTaskReason.CANCELLED_OPERATOR);
        }
    }

    private void releaseControls() {
        if (controller == null) {
            return;
        }
        controller.getInputControls().release(Input.CLICK_RIGHT);
        controller.getBaritone().getPathingBehavior().forceCancel();
        if (controller.getBaritone().getCustomGoalProcess().isActive()) {
            controller.getBaritone().getCustomGoalProcess().onLostControl();
        }
        if (controller.getBaritone().getExploreProcess().isActive()) {
            controller.getBaritone().getExploreProcess().onLostControl();
        }
        if (behaviourPushed) {
            controller.getBehaviour().pop();
            behaviourPushed = false;
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

    public BlockPos anchor() {
        return anchor;
    }

    public BlockPos surfaceReturnStance() {
        return surfaceReturnStance;
    }

    public BlockPos source() {
        return source;
    }

    public BlockPos stance() {
        return stance;
    }

    public int attempts() {
        return attempts;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof AcquireWaterBucketTask task
                && task.anchor.equals(anchor)
                && task.surfaceReturnStance.equals(surfaceReturnStance);
    }

    @Override
    protected String toDebugString() {
        return "Acquire verified water bucket from anchor " + anchor.toShortString();
    }

    private record SourceAndStance(BlockPos source, BlockPos stance) {
    }

    private record SourceSearchBatch(Optional<SourceAndStance> selected, boolean complete) {
        private SourceSearchBatch {
            Objects.requireNonNull(selected, "selected");
        }
    }
}
