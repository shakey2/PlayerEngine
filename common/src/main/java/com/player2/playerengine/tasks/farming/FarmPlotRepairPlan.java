package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, task-local repair manifest for one already-selected 9x9 farm plot.
 *
 * <p>The manifest deliberately contains no persistence or model-feedback integration. Every target
 * is derived from the one frozen center and exists only while setup validates or repairs that farm.
 */
public final class FarmPlotRepairPlan {
    public static final int VERTICAL_LAYER_COUNT = 5;
    public static final int OBSERVATION_COUNT = FarmPlotPolicy.PLOT_CELL_COUNT
            * VERTICAL_LAYER_COUNT;
    public static final int MAX_CLEAR_ACTIONS = FarmPlotPolicy.PLOT_CELL_COUNT
            * FarmPlotPolicy.REPAIR_HEADROOM_BLOCKS
            + FarmPlotPolicy.MAX_REPAIR_COLUMNS + 1;
    private static final long MAX_ACTION_HORIZONTAL_DISTANCE_SQUARED = 9L;

    public enum Status {
        READY,
        UNSAFE,
        REPAIR_LIMIT_EXCEEDED
    }

    public enum UnsafeReason {
        NONE,
        UNREADABLE,
        UNSAFE_SUPPORT,
        UNSAFE_OBSTRUCTION,
        NON_CENTER_FLUID,
        CROP_CONFLICT,
        NO_RETURN_STANCE,
        NO_ACTION_STANCE,
        REPAIR_LIMIT_EXCEEDED
    }

    public enum ClearPurpose {
        HEADROOM,
        REPLACE_SURFACE,
        OPEN_CENTER
    }

    public record ClearAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint,
            ClearPurpose purpose,
            FarmBreakToolRequirement breakToolRequirement) {
        public ClearAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(breakToolRequirement, "breakToolRequirement");
        }
    }

    public record FillAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint) {
        public FillAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
        }
    }

    public record TillAction(
            BlockPos target,
            BlockPos stance,
            String expectedStateFingerprint) {
        public TillAction {
            target = immutable(target, "target");
            stance = immutable(stance, "stance");
            Objects.requireNonNull(expectedStateFingerprint, "expectedStateFingerprint");
        }
    }

    public record ScanResult(
            Status status,
            UnsafeReason reason,
            int reads,
            FarmPlotRepairPlan plan) {
        public ScanResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            if (reads != OBSERVATION_COUNT) {
                throw new IllegalArgumentException("repair scan must account for exactly 405 reads");
            }
            if ((status == Status.READY) != (plan != null)
                    || (status == Status.READY) != (reason == UnsafeReason.NONE)) {
                throw new IllegalArgumentException("repair scan status, reason, and plan disagree");
            }
        }

        public boolean ready() {
            return status == Status.READY;
        }
    }

    private record ClearDraft(
            BlockPos target,
            String fingerprint,
            ClearPurpose purpose,
            FarmBreakToolRequirement breakToolRequirement) {
        private ClearDraft {
            target = immutable(target, "target");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(breakToolRequirement, "breakToolRequirement");
        }
    }

    private final BlockPos center;
    private final boolean centerHasWaterSource;
    private final int repairColumns;
    private final List<BlockPos> returnStanceCandidates;
    private final List<ClearAction> clearActions;
    private final FarmRepairToolPlan toolPlan;
    private final List<FillAction> fillActions;
    private final List<TillAction> tillActions;
    private final Set<BlockPos> preservedCrops;

    private FarmPlotRepairPlan(
            BlockPos center,
            boolean centerHasWaterSource,
            int repairColumns,
            List<BlockPos> returnStanceCandidates,
            List<ClearAction> clearActions,
            List<FillAction> fillActions,
            List<TillAction> tillActions,
            Set<BlockPos> preservedCrops) {
        this.center = immutable(center, "center");
        this.centerHasWaterSource = centerHasWaterSource;
        if (repairColumns < 0 || repairColumns > FarmPlotPolicy.MAX_REPAIR_COLUMNS) {
            throw new IllegalArgumentException("repair columns exceed the fixed farm limit");
        }
        this.repairColumns = repairColumns;
        this.returnStanceCandidates = immutablePositions(
                returnStanceCandidates, "returnStanceCandidates");
        this.clearActions = List.copyOf(Objects.requireNonNull(clearActions, "clearActions"));
        this.toolPlan = FarmRepairToolPlan.fromClearActions(this.clearActions);
        this.fillActions = List.copyOf(Objects.requireNonNull(fillActions, "fillActions"));
        this.tillActions = List.copyOf(Objects.requireNonNull(tillActions, "tillActions"));
        Objects.requireNonNull(preservedCrops, "preservedCrops");
        LinkedHashSet<BlockPos> crops = new LinkedHashSet<>();
        for (BlockPos crop : preservedCrops) {
            crops.add(immutable(crop, "preserved crop"));
        }
        this.preservedCrops = Collections.unmodifiableSet(crops);
        if (this.returnStanceCandidates.isEmpty()
                || this.clearActions.size() > MAX_CLEAR_ACTIONS
                || this.fillActions.size() > FarmPlotPolicy.MAX_REPAIR_COLUMNS
                || this.tillActions.size() > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException("repair manifest is outside fixed bounds");
        }
    }

    /** Production entry point over the exact live server level. */
    public static ScanResult scan(ServerLevel level, BlockPos center) {
        return scan(FarmSiteWorldView.live(
                Objects.requireNonNull(level, "level")), center);
    }

    /** Reads the complete 405-cell task-local envelope exactly once, then derives a pure manifest. */
    public static ScanResult scan(FarmSiteWorldView world, BlockPos requestedCenter) {
        Objects.requireNonNull(world, "world");
        BlockPos center = immutable(requestedCenter, "center");
        world.assertServerThread();
        LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations =
                new LinkedHashMap<>();
        for (BlockPos position : scanEnvelope(center)) {
            observations.put(position, world.observe(position));
        }
        return derive(center, observations);
    }

    /** Exact horizontal 9x9 footprint at support, surface, and three headroom layers. */
    public static List<BlockPos> scanEnvelope(BlockPos requestedCenter) {
        BlockPos center = immutable(requestedCenter, "center");
        ArrayList<BlockPos> positions = new ArrayList<>(OBSERVATION_COUNT);
        List<BlockPos> cells = FarmPlotGeometry.allCells(center);
        for (int dy = -1; dy <= 3; dy++) {
            for (BlockPos cell : cells) {
                positions.add(cell.offset(0, dy, 0).immutable());
            }
        }
        return List.copyOf(positions);
    }

    /** Every geometrically eligible farm-level feet position, never the center hole. */
    public static List<BlockPos> farmLevelReturnStanceCandidates(BlockPos requestedCenter) {
        BlockPos center = immutable(requestedCenter, "center");
        ArrayList<BlockPos> candidates = new ArrayList<>(FarmPlotPolicy.SOIL_CELL_COUNT);
        for (BlockPos soil : FarmPlotGeometry.soilCells(center)) {
            candidates.add(soil.above().immutable());
        }
        return List.copyOf(candidates);
    }

    /** Pure geometry gate shared with return navigation. */
    public static boolean isFarmLevelReturnStance(BlockPos center, BlockPos feet) {
        if (center == null || feet == null || feet.getY() != center.getY() + 1) {
            return false;
        }
        BlockPos support = feet.below();
        return !support.equals(center) && FarmPlotGeometry.contains(center, support);
    }

    private static ScanResult derive(
            BlockPos center,
            LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations) {
        List<BlockPos> expected = scanEnvelope(center);
        if (observations.size() != OBSERVATION_COUNT
                || !new ArrayList<>(observations.keySet()).equals(expected)) {
            throw new IllegalArgumentException("repair observations do not match the exact envelope");
        }

        for (Map.Entry<BlockPos, FarmSiteWorldView.CellObservation> entry : observations.entrySet()) {
            BlockPos position = entry.getKey();
            FarmSiteWorldView.CellObservation facts = entry.getValue();
            if (facts == null || !facts.isReadable()) {
                return unsafe(Status.UNSAFE, UnsafeReason.UNREADABLE);
            }
            if (facts.blockEntity() || facts.unbreakable() || facts.fallingHazard()) {
                return unsafe(Status.UNSAFE, UnsafeReason.UNSAFE_OBSTRUCTION);
            }
            boolean allowedCenterSource = position.equals(center)
                    && facts.isStandaloneWaterSource();
            if (facts.hasFluid() && !allowedCenterSource) {
                return unsafe(Status.UNSAFE, UnsafeReason.NON_CENTER_FLUID);
            }
        }

        List<BlockPos> cells = FarmPlotGeometry.allCells(center);
        for (BlockPos surface : cells) {
            if (!safeFoundationSupport(observations.get(surface.below()))) {
                return unsafe(Status.UNSAFE, UnsafeReason.UNSAFE_SUPPORT);
            }
        }

        ArrayList<ClearDraft> headroomClears = new ArrayList<>();
        ArrayList<ClearDraft> surfaceClears = new ArrayList<>();
        LinkedHashSet<BlockPos> fillTargets = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> tillTargets = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> preservedCrops = new LinkedHashSet<>();
        int repairColumns = 0;
        boolean centerHasWater = false;

        // Strict top-down order is frozen into the manifest before any surface replacement.
        for (int dy = 3; dy >= 1; dy--) {
            for (BlockPos surface : cells) {
                BlockPos target = surface.above(dy);
                FarmSiteWorldView.CellObservation facts = observations.get(target);
                if (isCrop(facts)) {
                    boolean canonicalLowerCell = dy == 1
                            && !surface.equals(center)
                            && observations.get(surface).blockKind()
                            == FarmSiteWorldView.BlockKind.FARMLAND;
                    boolean supportedPitcherUpper = dy == 2
                            && isSupportedPitcherUpperPair(
                            surface,
                            center,
                            observations.get(surface),
                            observations.get(surface.above()),
                            facts);
                    if (!canonicalLowerCell && !supportedPitcherUpper) {
                        return unsafe(Status.UNSAFE, UnsafeReason.CROP_CONFLICT);
                    }
                    preservedCrops.add(target);
                } else if (facts.blockKind() != FarmSiteWorldView.BlockKind.AIR) {
                    headroomClears.add(new ClearDraft(
                            target,
                            facts.stateFingerprint(),
                            ClearPurpose.HEADROOM,
                            facts.breakToolRequirement()));
                }
            }
        }

        for (BlockPos surface : cells) {
            FarmSiteWorldView.CellObservation facts = observations.get(surface);
            FarmSiteWorldView.BlockKind kind = facts.blockKind();
            if (surface.equals(center)) {
                if (isCrop(facts)) {
                    return unsafe(Status.UNSAFE, UnsafeReason.CROP_CONFLICT);
                }
                if (kind == FarmSiteWorldView.BlockKind.STANDALONE_WATER) {
                    centerHasWater = true;
                } else if (kind != FarmSiteWorldView.BlockKind.AIR) {
                    surfaceClears.add(new ClearDraft(
                            surface,
                            facts.stateFingerprint(),
                            ClearPurpose.OPEN_CENTER,
                            facts.breakToolRequirement()));
                    if (kind != FarmSiteWorldView.BlockKind.GRASS_BLOCK
                            && kind != FarmSiteWorldView.BlockKind.DIRT
                            && kind != FarmSiteWorldView.BlockKind.FARMLAND) {
                        repairColumns++;
                    }
                }
                continue;
            }

            if (isCrop(facts)) {
                return unsafe(Status.UNSAFE, UnsafeReason.CROP_CONFLICT);
            }

            switch (kind) {
                case GRASS_BLOCK, DIRT -> tillTargets.add(surface);
                case FARMLAND -> {
                }
                case AIR -> {
                    fillTargets.add(surface);
                    tillTargets.add(surface);
                    repairColumns++;
                }
                case CROP -> {
                    return unsafe(Status.UNSAFE, UnsafeReason.CROP_CONFLICT);
                }
                default -> {
                    surfaceClears.add(new ClearDraft(
                            surface,
                            facts.stateFingerprint(),
                            ClearPurpose.REPLACE_SURFACE,
                            facts.breakToolRequirement()));
                    fillTargets.add(surface);
                    tillTargets.add(surface);
                    repairColumns++;
                }
            }
        }

        if (repairColumns > FarmPlotPolicy.MAX_REPAIR_COLUMNS) {
            return unsafe(Status.REPAIR_LIMIT_EXCEEDED, UnsafeReason.REPAIR_LIMIT_EXCEEDED);
        }

        List<BlockPos> returnStances = liveReturnStances(center, observations);
        if (returnStances.isEmpty()) {
            return unsafe(Status.UNSAFE, UnsafeReason.NO_RETURN_STANCE);
        }

        ArrayList<ClearAction> clearActions = new ArrayList<>(
                headroomClears.size() + surfaceClears.size());
        for (ClearDraft draft : concat(headroomClears, surfaceClears)) {
            BlockPos stance = chooseActionStance(center, draft.target(), returnStances);
            if (stance == null) {
                return unsafe(Status.UNSAFE, UnsafeReason.NO_ACTION_STANCE);
            }
            clearActions.add(new ClearAction(
                    draft.target(),
                    stance,
                    draft.fingerprint(),
                    draft.purpose(),
                    draft.breakToolRequirement()));
        }
        if (clearActions.size() > MAX_CLEAR_ACTIONS) {
            return unsafe(Status.UNSAFE, UnsafeReason.UNSAFE_OBSTRUCTION);
        }

        String airFingerprint = FarmSiteWorldView.stateFingerprint(Blocks.AIR.defaultBlockState());
        String dirtFingerprint = FarmSiteWorldView.stateFingerprint(Blocks.DIRT.defaultBlockState());
        ArrayList<FillAction> fillActions = new ArrayList<>(fillTargets.size());
        for (BlockPos target : fillTargets) {
            BlockPos stance = chooseActionStance(center, target, returnStances);
            if (stance == null) {
                return unsafe(Status.UNSAFE, UnsafeReason.NO_ACTION_STANCE);
            }
            boolean brokenFirst = surfaceClears.stream()
                    .anyMatch(clear -> clear.target().equals(target));
            fillActions.add(new FillAction(
                    target,
                    stance,
                    brokenFirst ? airFingerprint : observations.get(target).stateFingerprint()));
        }

        ArrayList<TillAction> tillActions = new ArrayList<>(tillTargets.size());
        for (BlockPos target : tillTargets) {
            BlockPos stance = chooseActionStance(center, target, returnStances);
            if (stance == null) {
                return unsafe(Status.UNSAFE, UnsafeReason.NO_ACTION_STANCE);
            }
            tillActions.add(new TillAction(
                    target,
                    stance,
                    fillTargets.contains(target)
                            ? dirtFingerprint
                            : observations.get(target).stateFingerprint()));
        }

        return new ScanResult(
                Status.READY,
                UnsafeReason.NONE,
                OBSERVATION_COUNT,
                new FarmPlotRepairPlan(
                        center,
                        centerHasWater,
                        repairColumns,
                        returnStances,
                        clearActions,
                        fillActions,
                        tillActions,
                        preservedCrops));
    }

    private static List<ClearDraft> concat(
            List<ClearDraft> first, List<ClearDraft> second) {
        ArrayList<ClearDraft> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }

    private static ScanResult unsafe(Status status, UnsafeReason reason) {
        return new ScanResult(status, reason, OBSERVATION_COUNT, null);
    }

    private static boolean safeFoundationSupport(FarmSiteWorldView.CellObservation facts) {
        return facts != null
                && facts.isReadable()
                && facts.solidTopSupport()
                && facts.nonWaterloggable()
                && !facts.hasFluid()
                && !facts.playerProtected()
                && !facts.blockEntity()
                && !facts.unbreakable()
                && !facts.fallingHazard();
    }

    private static List<BlockPos> liveReturnStances(
            BlockPos center,
            Map<BlockPos, FarmSiteWorldView.CellObservation> observations) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (BlockPos feet : farmLevelReturnStanceCandidates(center)) {
            FarmSiteWorldView.CellObservation support = observations.get(feet.below());
            FarmSiteWorldView.CellObservation feetFacts = observations.get(feet);
            FarmSiteWorldView.CellObservation headFacts = observations.get(feet.above());
            if (safeFarmSurfaceSupport(support)
                    && safeFeetCell(feetFacts)
                    && safeHeadCell(headFacts)) {
                candidates.add(feet.immutable());
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean safeFarmSurfaceSupport(FarmSiteWorldView.CellObservation facts) {
        if (facts == null
                || !facts.isReadable()
                || facts.playerProtected()
                || !facts.nonWaterloggable()
                || facts.hasFluid()
                || facts.blockEntity()
                || facts.unbreakable()
                || facts.fallingHazard()) {
            return false;
        }
        // Farmland's lowered collision shape is intentionally not a full sturdy top, but it is a
        // canonical walkable farm support. Restricting this to known soil kinds keeps that narrow.
        return facts.blockKind() == FarmSiteWorldView.BlockKind.GRASS_BLOCK
                || facts.blockKind() == FarmSiteWorldView.BlockKind.DIRT
                || facts.blockKind() == FarmSiteWorldView.BlockKind.FARMLAND;
    }

    private static boolean safeFeetCell(FarmSiteWorldView.CellObservation facts) {
        boolean crop = isCrop(facts);
        return facts != null
                && facts.isReadable()
                // Walking through a collision-empty crop is not a crop mutation. Allow it as a
                // stance so a fully planted farm remains repairable while preserving every crop.
                && (!facts.playerProtected() || crop)
                && !facts.hasFluid()
                && !facts.blockEntity()
                && !facts.unbreakable()
                && !facts.fallingHazard()
                && (facts.collisionEmpty()
                || crop && PitcherCropHarvestBehavior.isLowerFingerprint(
                facts.stateFingerprint()));
    }

    private static boolean safeHeadCell(FarmSiteWorldView.CellObservation facts) {
        return facts != null
                && facts.isReadable()
                && !facts.playerProtected()
                && !facts.hasFluid()
                && !facts.blockEntity()
                && !facts.unbreakable()
                && !facts.fallingHazard()
                && facts.collisionEmpty();
    }

    private static boolean isCrop(FarmSiteWorldView.CellObservation facts) {
        return facts != null
                && (facts.recognizedCrop()
                || facts.blockKind() == FarmSiteWorldView.BlockKind.CROP);
    }

    /** Only a matching vanilla pitcher lower/upper pair may occupy the second crop layer. */
    static boolean isSupportedPitcherUpperPair(
            BlockPos surface,
            BlockPos center,
            FarmSiteWorldView.CellObservation support,
            FarmSiteWorldView.CellObservation lower,
            FarmSiteWorldView.CellObservation upper) {
        if (surface == null || center == null || surface.equals(center)
                || support == null
                || support.blockKind() != FarmSiteWorldView.BlockKind.FARMLAND
                || !isCrop(lower)
                || !isCrop(upper)
                || !PitcherCropHarvestBehavior.isLowerFingerprint(lower.stateFingerprint())
                || !PitcherCropHarvestBehavior.isUpperFingerprint(upper.stateFingerprint())) {
            return false;
        }
        java.util.OptionalInt lowerAge = PitcherCropHarvestBehavior.ageFromFingerprint(
                lower.stateFingerprint());
        java.util.OptionalInt upperAge = PitcherCropHarvestBehavior.ageFromFingerprint(
                upper.stateFingerprint());
        return lowerAge.isPresent()
                && upperAge.isPresent()
                && lowerAge.getAsInt() >= 3
                && lowerAge.getAsInt() == upperAge.getAsInt();
    }

    private static BlockPos chooseActionStance(
            BlockPos center,
            BlockPos target,
            List<BlockPos> candidates) {
        BlockPos selected = null;
        long selectedDistance = Long.MAX_VALUE;
        BlockPos targetSurface = new BlockPos(target.getX(), center.getY(), target.getZ());
        for (BlockPos candidate : candidates) {
            if (candidate.below().equals(targetSurface)) {
                continue;
            }
            long distance = FarmPlotGeometry.horizontalDistanceSquared(candidate, target);
            if (distance > MAX_ACTION_HORIZONTAL_DISTANCE_SQUARED) {
                continue;
            }
            if (selected == null || distance < selectedDistance) {
                selected = candidate;
                selectedDistance = distance;
            }
        }
        return selected == null ? null : selected.immutable();
    }

    public BlockPos center() {
        return center;
    }

    public boolean centerHasWaterSource() {
        return centerHasWaterSource;
    }

    public int repairColumns() {
        return repairColumns;
    }

    public int requiredDirt() {
        return fillActions.size();
    }

    public List<BlockPos> returnStanceCandidates() {
        return returnStanceCandidates;
    }

    public List<ClearAction> clearActions() {
        return clearActions;
    }

    public FarmRepairToolPlan toolPlan() {
        return toolPlan;
    }

    public List<FillAction> fillActions() {
        return fillActions;
    }

    public List<TillAction> tillActions() {
        return tillActions;
    }

    public Set<BlockPos> preservedCrops() {
        return preservedCrops;
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions, String name) {
        Objects.requireNonNull(positions, name);
        ArrayList<BlockPos> copy = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            copy.add(immutable(position, name + " entry"));
        }
        return List.copyOf(copy);
    }

    private static BlockPos immutable(BlockPos position, String name) {
        return Objects.requireNonNull(position, name).immutable();
    }
}
