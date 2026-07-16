package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Incremental, deterministic, server-thread farm-site cursor. It freezes at most 2,048 view reads
 * per tick and resumes at the exact environment/cell field where the previous tick stopped.
 */
public final class FarmSitePlanner {

    public enum Status {
        RUNNING,
        SELECTED,
        NO_SUITABLE_SITE,
        FREEZE_RISK,
        TIMED_OUT,
        WORLD_VIEW_FAILED
    }

    public record TickResult(
            Status status,
            int readsThisTick,
            int totalReads,
            int candidatesCompleted,
            int freezeRejectedCandidates,
            int candidateLimit,
            FarmSitePlan selectedPlan) {
    }

    public record RevalidationResult(
            boolean valid,
            int reads,
            String expectedFingerprint,
            String observedFingerprint) {
    }

    private final FarmSiteWorldView worldView;
    private final BlockPos anchor;
    private final boolean explicit;
    private final boolean resumeMode;
    private final List<BlockPos> candidates;

    private CandidateCursor cursor;
    private int nextCandidate;
    private int candidatesCompleted;
    private int freezeRejectedCandidates;
    private int ticks;
    private int totalReads;
    private Status status = Status.RUNNING;
    private FarmSitePlan bestPlan;

    private FarmSitePlanner(
            FarmSiteWorldView worldView,
            BlockPos anchor,
            boolean explicit,
            boolean resumeMode,
            List<BlockPos> candidates) {
        this.worldView = Objects.requireNonNull(worldView, "worldView");
        this.anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        this.explicit = explicit;
        this.resumeMode = resumeMode;
        if (resumeMode && !explicit) {
            throw new IllegalArgumentException("resume planning requires an explicit center");
        }
        this.candidates = List.copyOf(candidates);
        if (this.candidates.isEmpty()
                || this.candidates.size() > FarmPlotPolicy.MAX_CANDIDATE_CENTERS) {
            throw new IllegalArgumentException("candidate list is outside fixed bounds");
        }
    }

    public static FarmSitePlanner auto(FarmSiteWorldView worldView, BlockPos anchor) {
        return new FarmSitePlanner(
                worldView,
                anchor,
                false,
                false,
                FarmPlotGeometry.autoCandidateCenters(anchor));
    }

    public static FarmSitePlanner explicit(
            FarmSiteWorldView worldView,
            BlockPos anchor,
            BlockPos exactCenter) {
        Objects.requireNonNull(exactCenter, "exactCenter");
        return new FarmSitePlanner(
                worldView,
                anchor,
                true,
                false,
                List.of(exactCenter.immutable()));
    }

    /**
     * Rebuilds the remaining manifest for an already-selected center after a transient interruption.
     * Initial grass eligibility has already been proven for this logical operation; every current
     * safety, protection, mutation-cap, repair-cap, and freeze check still applies.
     */
    public static FarmSitePlanner resume(
            FarmSiteWorldView worldView,
            BlockPos anchor,
            BlockPos exactCenter) {
        Objects.requireNonNull(exactCenter, "exactCenter");
        return new FarmSitePlanner(
                worldView,
                anchor,
                true,
                true,
                List.of(exactCenter.immutable()));
    }

    public TickResult onTick() {
        return onTick(FarmPlotPolicy.MAX_WORLD_READS_PER_TICK);
    }

    /** Testable partition seam; production callers use {@link #onTick()}. */
    public TickResult onTick(int requestedReadBudget) {
        if (status != Status.RUNNING) {
            return result(0);
        }
        if (requestedReadBudget <= 0) {
            throw new IllegalArgumentException("requestedReadBudget must be positive");
        }
        worldView.assertServerThread();
        ticks++;
        int budget = Math.min(requestedReadBudget, FarmPlotPolicy.MAX_WORLD_READS_PER_TICK);
        int reads = 0;
        try {
            while (reads < budget && status == Status.RUNNING) {
                if (cursor == null) {
                    if (nextCandidate >= candidates.size()) {
                        finishSelection();
                        break;
                    }
                    cursor = new CandidateCursor(candidates.get(nextCandidate++), explicit);
                }

                if (cursor.worldObservation == null) {
                    cursor.worldObservation = worldView.observeWorld(cursor.center);
                    reads++;
                    totalReads++;
                    if (rejectWorld(cursor.worldObservation)) {
                        candidatesCompleted++;
                        cursor = null;
                    }
                    continue;
                }

                if (cursor.nextPosition < cursor.positions.size()) {
                    BlockPos position = cursor.positions.get(cursor.nextPosition++);
                    cursor.observations.put(position, worldView.observe(position));
                    reads++;
                    totalReads++;
                }

                if (cursor.nextPosition == cursor.positions.size()) {
                    FarmSiteSnapshot snapshot = cursor.snapshot();
                    FarmSitePlan candidate = derivePlan(anchor, snapshot, resumeMode);
                    if (candidate == null && FarmFreezePolicy.wouldFreeze(snapshot)) {
                        freezeRejectedCandidates++;
                    }
                    if (candidate != null && (bestPlan == null
                            || planComparator(anchor).compare(candidate, bestPlan) < 0)) {
                        bestPlan = candidate;
                    }
                    candidatesCompleted++;
                    cursor = null;
                }
            }
        } catch (RuntimeException viewFailure) {
            status = Status.WORLD_VIEW_FAILED;
        }

        if (status == Status.RUNNING && cursor == null && nextCandidate >= candidates.size()) {
            finishSelection();
        }
        if (status == Status.RUNNING && ticks >= FarmPlotPolicy.SITE_SELECTION_TICKS) {
            status = Status.TIMED_OUT;
        }
        return result(reads);
    }

    public boolean isFinished() {
        return status != Status.RUNNING;
    }

    public Status status() {
        return status;
    }

    public FarmSitePlan selectedPlan() {
        return bestPlan;
    }

    public int ticks() {
        return ticks;
    }

    public int totalReads() {
        return totalReads;
    }

    /**
     * Re-reads the exact frozen envelope. The complete manifest is 606 reads, comfortably below the
     * per-tick cap; exact equality, rather than digest equality alone, decides validity.
     */
    public static RevalidationResult revalidate(
            FarmSitePlan plan,
            FarmSiteWorldView worldView) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(worldView, "worldView");
        worldView.assertServerThread();
        FarmSiteSnapshot expected = plan.snapshot();
        FarmSiteWorldView.WorldObservation world = worldView.observeWorld(plan.center());
        LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations = new LinkedHashMap<>();
        int reads = 1;
        for (BlockPos position : expected.positions()) {
            if (reads >= FarmPlotPolicy.MAX_WORLD_READS_PER_TICK) {
                throw new IllegalStateException("farm revalidation exceeded the per-tick read cap");
            }
            observations.put(position, worldView.observe(position));
            reads++;
        }
        FarmSiteSnapshot current = new FarmSiteSnapshot(
                plan.center(), plan.explicit(), world, observations);
        return new RevalidationResult(
                plan.matches(current),
                reads,
                expected.fingerprint(),
                current.fingerprint());
    }

    private void finishSelection() {
        if (bestPlan != null) {
            status = Status.SELECTED;
        } else if (freezeRejectedCandidates > 0
                && freezeRejectedCandidates == candidatesCompleted) {
            status = Status.FREEZE_RISK;
        } else {
            status = Status.NO_SUITABLE_SITE;
        }
    }

    private TickResult result(int reads) {
        return new TickResult(
                status,
                reads,
                totalReads,
                candidatesCompleted,
                freezeRejectedCandidates,
                candidates.size(),
                bestPlan);
    }

    private static FarmSitePlan derivePlan(
            BlockPos anchor,
            FarmSiteSnapshot snapshot,
            boolean resumeMode) {
        FarmSiteWorldView.WorldObservation world = snapshot.worldObservation();
        if (rejectWorld(world)) {
            return null;
        }

        BlockPos center = snapshot.center();
        boolean explicit = snapshot.explicit();
        for (BlockPos position : snapshot.positions()) {
            FarmSiteWorldView.CellObservation facts = snapshot.observation(position);
            if (facts == null || !facts.isReadable()) {
                return null;
            }
            if (facts.hasFluid()
                    && !(explicit && position.equals(center) && facts.isStandaloneWaterSource())) {
                return null;
            }
            if (facts.fallingHazard()) {
                return null;
            }
        }

        LinkedHashMap<BlockPos, FarmSitePlan.ClearPurpose> clearTargets = new LinkedHashMap<>();
        ArrayList<BlockPos> fillTargets = new ArrayList<>();
        ArrayList<BlockPos> tillTargets = new ArrayList<>();
        int grass = 0;
        int farmland = 0;
        int repairs = 0;
        boolean centerHasSource = false;

        for (BlockPos surface : FarmPlotGeometry.allCells(center)) {
            FarmSiteWorldView.CellObservation support = snapshot.observation(surface.below());
            FarmSiteWorldView.CellObservation target = snapshot.observation(surface);
            FarmSiteWorldView.CellObservation above = snapshot.observation(surface.above());
            FarmSiteWorldView.CellObservation upper = snapshot.observation(surface.above(2));
            FarmSiteWorldView.CellObservation third = snapshot.observation(surface.above(3));
            if (!safeRequiredCell(support)
                    || !safeRequiredCell(target)
                    || !(safeRequiredCell(above)
                    || safePreservedCrop(surface, center, target, above))
                    || !safeRequiredCell(upper)
                    || !safeRequiredCell(third)
                    || !support.solidTopSupport()
                    || !support.nonWaterloggable()
                    || support.hasFluid()) {
                return null;
            }

            boolean repairColumn = false;
            if (!deriveHeadroom(
                    surface, center, target, above, upper, third, clearTargets)) {
                return null;
            }

            boolean raisedSurface = above.blockKind() == FarmSiteWorldView.BlockKind.GRASS_BLOCK
                    || above.blockKind() == FarmSiteWorldView.BlockKind.DIRT
                    || above.blockKind() == FarmSiteWorldView.BlockKind.STONE;
            FarmSiteWorldView.BlockKind evidenceSurface = raisedSurface
                    ? above.blockKind()
                    : target.blockKind();
            if (evidenceSurface == FarmSiteWorldView.BlockKind.GRASS_BLOCK) {
                grass++;
            }

            if (surface.equals(center)) {
                switch (target.blockKind()) {
                    case STANDALONE_WATER -> {
                        if (!explicit || !target.isStandaloneWaterSource()) {
                            return null;
                        }
                        centerHasSource = true;
                    }
                    case GRASS_BLOCK, DIRT -> clearTargets.put(
                            surface, FarmSitePlan.ClearPurpose.OPEN_CENTER);
                    case STONE -> {
                        clearTargets.put(surface, FarmSitePlan.ClearPurpose.OPEN_CENTER);
                        repairColumn = true;
                    }
                    case AIR -> {
                    }
                    case REPLACEABLE_VEGETATION -> {
                        clearTargets.put(surface, FarmSitePlan.ClearPurpose.OPEN_CENTER);
                    }
                    default -> {
                        return null;
                    }
                }
            } else {
                switch (target.blockKind()) {
                    case GRASS_BLOCK, DIRT -> tillTargets.add(surface);
                    case STONE -> {
                        clearTargets.put(surface, FarmSitePlan.ClearPurpose.REPLACE_STONE);
                        fillTargets.add(surface);
                        tillTargets.add(surface);
                        repairColumn = true;
                    }
                    case AIR -> {
                        fillTargets.add(surface);
                        tillTargets.add(surface);
                        repairColumn = true;
                    }
                    case REPLACEABLE_VEGETATION -> {
                        clearTargets.put(surface, FarmSitePlan.ClearPurpose.VEGETATION);
                        fillTargets.add(surface);
                        tillTargets.add(surface);
                        repairColumn = true;
                    }
                    case FARMLAND -> {
                        if (!explicit) {
                            return null;
                        }
                        farmland++;
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (repairColumn) {
                repairs++;
            }
        }

        int siteEvidence = explicit ? grass + farmland : grass;
        if ((!resumeMode && siteEvidence < FarmPlotPolicy.MIN_NEW_SITE_GRASS_COLUMNS)
                || repairs > FarmPlotPolicy.MAX_REPAIR_COLUMNS
                || clearTargets.size() > FarmPlotPolicy.MAX_INITIAL_CLEAR_ACTIONS
                || FarmFreezePolicy.wouldFreeze(snapshot)) {
            return null;
        }

        List<Map.Entry<BlockPos, FarmSitePlan.ClearPurpose>> sortedClear =
                new ArrayList<>(clearTargets.entrySet());
        sortedClear.sort(Comparator
                .<Map.Entry<BlockPos, FarmSitePlan.ClearPurpose>>comparingInt(e -> e.getKey().getY())
                .reversed()
                .thenComparingInt(e -> e.getKey().getZ())
                .thenComparingInt(e -> e.getKey().getX()));

        ArrayList<FarmSitePlan.ClearAction> clearActions = new ArrayList<>();
        ArrayList<Map.Entry<BlockPos, FarmSitePlan.ClearPurpose>> pendingClear =
                new ArrayList<>();
        Map.Entry<BlockPos, FarmSitePlan.ClearPurpose> deferredCenterOpen = null;
        for (Map.Entry<BlockPos, FarmSitePlan.ClearPurpose> entry : sortedClear) {
            if (entry.getValue() == FarmSitePlan.ClearPurpose.OPEN_CENTER) {
                deferredCenterOpen = entry;
            } else {
                pendingClear.add(entry);
            }
        }

        // A contiguous one-block raised layer must be peeled from an outside edge inward. Freeze
        // that executable order now: each selected clear may expose a later action's standing cell.
        Set<BlockPos> unstableClearSupports = new HashSet<>(fillTargets);
        unstableClearSupports.addAll(clearTargets.keySet());
        Set<BlockPos> pendingLevelSupports = new HashSet<>();
        for (Map.Entry<BlockPos, FarmSitePlan.ClearPurpose> entry : clearTargets.entrySet()) {
            if (entry.getValue() == FarmSitePlan.ClearPurpose.LEVEL_SURFACE) {
                pendingLevelSupports.add(entry.getKey());
            }
        }
        Set<BlockPos> simulatedCleared = new HashSet<>();
        while (!pendingClear.isEmpty()) {
            boolean scheduled = false;
            for (int index = 0; index < pendingClear.size(); index++) {
                Map.Entry<BlockPos, FarmSitePlan.ClearPurpose> entry = pendingClear.get(index);
                BlockPos stance = chooseStance(
                        snapshot,
                        entry.getKey(),
                        unstableClearSupports,
                        simulatedCleared,
                        Set.of(),
                        true,
                        pendingLevelSupports);
                if (stance == null) {
                    continue;
                }
                clearActions.add(new FarmSitePlan.ClearAction(
                        entry.getKey(),
                        stance,
                        snapshot.observation(entry.getKey()).stateFingerprint(),
                        entry.getValue()));
                simulatedCleared.add(entry.getKey());
                pendingClear.remove(index);
                scheduled = true;
                break;
            }
            if (!scheduled) {
                return null;
            }
        }

        ArrayList<FarmSitePlan.FillAction> fillActions = new ArrayList<>();
        Set<BlockPos> pendingFillSupports = new HashSet<>(fillTargets);
        pendingFillSupports.addAll(clearTargets.keySet());
        Set<BlockPos> simulatedFilled = new HashSet<>();
        for (BlockPos target : fillTargets) {
            BlockPos stance = chooseStance(
                    snapshot,
                    target,
                    pendingFillSupports,
                    simulatedCleared,
                    simulatedFilled,
                    false,
                    Set.of());
            if (stance == null) {
                return null;
            }
            fillActions.add(new FarmSitePlan.FillAction(
                    target, stance, snapshot.observation(target).stateFingerprint()));
            pendingFillSupports.remove(target);
            simulatedFilled.add(target);
        }

        if (deferredCenterOpen != null) {
            BlockPos stance = chooseStance(
                    snapshot,
                    deferredCenterOpen.getKey(),
                    Set.of(),
                    simulatedCleared,
                    simulatedFilled,
                    false,
                    Set.of());
            if (stance == null) {
                return null;
            }
            clearActions.add(new FarmSitePlan.ClearAction(
                    deferredCenterOpen.getKey(),
                    stance,
                    snapshot.observation(deferredCenterOpen.getKey()).stateFingerprint(),
                    deferredCenterOpen.getValue()));
        }

        ArrayList<FarmSitePlan.TillAction> tillActions = new ArrayList<>();
        for (BlockPos target : tillTargets) {
            BlockPos stance = chooseStance(
                    snapshot, target, Set.of(), simulatedCleared, simulatedFilled,
                    false, Set.of());
            if (stance == null) {
                return null;
            }
            String expectedPreTill = fillTargets.contains(target)
                    ? FarmSiteWorldView.stateFingerprint(Blocks.DIRT.defaultBlockState())
                    : snapshot.observation(target).stateFingerprint();
            tillActions.add(new FarmSitePlan.TillAction(
                    target, stance, expectedPreTill));
        }

        boolean waterRequired = !centerHasSource;
        FarmSitePlan.WaterAction waterAction = null;
        if (waterRequired) {
            BlockPos stance = chooseStance(
                    snapshot, center, Set.of(), simulatedCleared, simulatedFilled,
                    false, Set.of());
            if (stance == null) {
                return null;
            }
            waterAction = new FarmSitePlan.WaterAction(
                    center,
                    stance,
                    center.below(),
                    snapshot.observation(center).stateFingerprint());
        }

        BlockPos acquisitionReturnStance;
        if (!clearActions.isEmpty()) {
            acquisitionReturnStance = clearActions.get(0).stance();
        } else if (!fillActions.isEmpty()) {
            acquisitionReturnStance = fillActions.get(0).stance();
        } else if (waterAction != null) {
            acquisitionReturnStance = waterAction.stance();
        } else if (!tillActions.isEmpty()) {
            acquisitionReturnStance = tillActions.get(0).stance();
        } else {
            acquisitionReturnStance = chooseStance(
                    snapshot, center, Set.of(), Set.of(), Set.of(), false, Set.of());
        }
        if (acquisitionReturnStance == null) {
            return null;
        }

        return new FarmSitePlan(
                anchor,
                center,
                explicit,
                waterRequired,
                grass,
                farmland,
                repairs,
                clearActions,
                fillActions,
                tillActions,
                waterAction,
                acquisitionReturnStance,
                snapshot);
    }

    private static boolean rejectWorld(FarmSiteWorldView.WorldObservation world) {
        return world.ultraWarm()
                || !world.protectionStoreAvailable()
                || !world.waypointStoreAvailable()
                || world.waypointCollision();
    }

    private static boolean deriveHeadroom(
            BlockPos surface,
            BlockPos center,
            FarmSiteWorldView.CellObservation target,
            FarmSiteWorldView.CellObservation above,
            FarmSiteWorldView.CellObservation upper,
            FarmSiteWorldView.CellObservation third,
            LinkedHashMap<BlockPos, FarmSitePlan.ClearPurpose> clearTargets) {
        if (above.recognizedCrop()) {
            return safePreservedCrop(surface, center, target, above)
                    && !upper.recognizedCrop()
                    && !third.recognizedCrop()
                    && upper.blockKind() == FarmSiteWorldView.BlockKind.AIR
                    && third.blockKind() == FarmSiteWorldView.BlockKind.AIR;
        }
        switch (above.blockKind()) {
            case AIR -> {
            }
            case REPLACEABLE_VEGETATION -> clearTargets.put(
                    surface.above(), FarmSitePlan.ClearPurpose.VEGETATION);
            case GRASS_BLOCK, DIRT, STONE -> {
                if (target.blockKind() == FarmSiteWorldView.BlockKind.AIR
                        || target.blockKind() == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION
                        || above.unbreakable()) {
                    return false;
                }
                clearTargets.put(surface.above(), FarmSitePlan.ClearPurpose.LEVEL_SURFACE);
            }
            default -> {
                return false;
            }
        }
        switch (upper.blockKind()) {
            case AIR -> {
            }
            case REPLACEABLE_VEGETATION -> {
                clearTargets.put(surface.above(2), FarmSitePlan.ClearPurpose.VEGETATION);
            }
            default -> {
                return false;
            }
        }
        switch (third.blockKind()) {
            case AIR -> {
                return true;
            }
            case REPLACEABLE_VEGETATION -> {
                clearTargets.put(surface.above(3), FarmSitePlan.ClearPurpose.VEGETATION);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean safeRequiredCell(FarmSiteWorldView.CellObservation facts) {
        return facts != null
                && facts.isReadable()
                && !facts.playerProtected()
                && !facts.blockEntity()
                && !facts.fallingHazard()
                && !facts.unbreakable();
    }

    private static boolean safePreservedCrop(
            BlockPos surface,
            BlockPos center,
            FarmSiteWorldView.CellObservation target,
            FarmSiteWorldView.CellObservation crop) {
        return !surface.equals(center)
                && target.blockKind() == FarmSiteWorldView.BlockKind.FARMLAND
                && crop != null
                && crop.recognizedCrop()
                && crop.isReadable()
                && crop.collisionEmpty()
                && !crop.hasFluid()
                && !crop.blockEntity()
                && !crop.fallingHazard()
                && !crop.unbreakable();
    }

    private static BlockPos chooseStance(
            FarmSiteSnapshot snapshot,
            BlockPos target,
            Set<BlockPos> forbiddenSupports,
            Set<BlockPos> simulatedCleared,
            Set<BlockPos> simulatedFilled,
            boolean allowElevated,
            Set<BlockPos> pendingLevelSupports) {
        BlockPos center = snapshot.center();
        List<BlockPos> candidates = allowElevated
                ? FarmPlotGeometry.clearStanceCandidates(center, target)
                : FarmPlotGeometry.stanceCandidates(center, target);
        for (BlockPos stance : candidates) {
            BlockPos supportPosition = stance.below();
            boolean intactLevelSupport = pendingLevelSupports.contains(supportPosition)
                    && !simulatedCleared.contains(supportPosition);
            if (forbiddenSupports.contains(supportPosition) && !intactLevelSupport) {
                continue;
            }
            if (simulatedCleared.contains(supportPosition)
                    && !simulatedFilled.contains(supportPosition)) {
                continue;
            }
            FarmSiteWorldView.CellObservation feet = snapshot.observation(stance);
            FarmSiteWorldView.CellObservation head = snapshot.observation(stance.above());
            FarmSiteWorldView.CellObservation support = snapshot.observation(stance.below());
            if (!safeStanceCell(stance, feet, simulatedCleared)
                    || !safeStanceCell(stance.above(), head, simulatedCleared)
                    || !safeRequiredCell(support)
                    || support.hasFluid()) {
                continue;
            }
            boolean eventualSoil = FarmPlotGeometry.contains(center, stance.below())
                    && !stance.below().equals(center);
            boolean stableNow = simulatedFilled.contains(supportPosition)
                    || (support.solidTopSupport() && support.nonWaterloggable());
            FarmSiteWorldView.BlockKind kind = support.blockKind();
            boolean normalizableNow = eventualSoil
                    && (kind == FarmSiteWorldView.BlockKind.GRASS_BLOCK
                    || kind == FarmSiteWorldView.BlockKind.DIRT
                    || kind == FarmSiteWorldView.BlockKind.STONE
                    || kind == FarmSiteWorldView.BlockKind.FARMLAND);
            if (stableNow || normalizableNow) {
                return stance.immutable();
            }
        }
        return null;
    }

    private static boolean safeStanceCell(
            BlockPos position,
            FarmSiteWorldView.CellObservation facts,
            Set<BlockPos> simulatedCleared) {
        return facts != null
                && facts.isReadable()
                && (simulatedCleared.contains(position)
                || facts.blockKind() == FarmSiteWorldView.BlockKind.AIR
                || (facts.blockKind() == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION
                && facts.collisionEmpty()))
                && !facts.hasFluid()
                && !facts.playerProtected()
                && !facts.blockEntity()
                && !facts.fallingHazard()
                && !facts.unbreakable();
    }

    private static Comparator<FarmSitePlan> planComparator(BlockPos anchor) {
        return Comparator
                .comparingInt(FarmSitePlan::rankingEdits)
                .thenComparing(Comparator.comparingInt(FarmSitePlan::grassColumns).reversed())
                .thenComparingLong(plan -> FarmPlotGeometry.horizontalDistanceSquared(
                        anchor, plan.center()))
                .thenComparingInt(plan -> plan.center().getX())
                .thenComparingInt(plan -> plan.center().getY())
                .thenComparingInt(plan -> plan.center().getZ());
    }

    private static final class CandidateCursor {
        private final BlockPos center;
        private final boolean explicit;
        private final List<BlockPos> positions;
        private final LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations =
                new LinkedHashMap<>();
        private FarmSiteWorldView.WorldObservation worldObservation;
        private int nextPosition;

        private CandidateCursor(BlockPos center, boolean explicit) {
            this.center = center.immutable();
            this.explicit = explicit;
            this.positions = FarmPlotGeometry.scanEnvelope(center);
        }

        private FarmSiteSnapshot snapshot() {
            return new FarmSiteSnapshot(center, explicit, worldObservation, observations);
        }
    }
}
