package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic geometry, incremental cursor, hazard, freeze, and revalidation contract checks. */
public final class FarmGeometrySelfTest {
    private static final BlockPos CENTER = new BlockPos(10, 64, -10);

    private FarmGeometrySelfTest() {
    }

    public static void runAll() {
        geometryIsNineByNineSerpentine();
        candidateOrderAndCapsAreExact();
        flatGapStoneAndExplicitResumeAreAccepted();
        resumeReplanningPreservesMarginalSiteIdentity();
        tillSequenceIncludesPriorFarmlandStances();
        denseGroundCoverAndOneLayerLevelingAreAccepted();
        unsafeFactsAreRejectedWithoutAPlan();
        stanceProtectionAndFreezeAreRejected();
        incrementalCursorPausesAtExactRead();
        tickPartitionDoesNotChangeSelection();
        rankingUsesEditsThenGrassThenDistance();
        planningRejectsOffThreadBeforeReading();
        manifestIsImmutableAndRevalidatesExactFacts();
        watchdogBoundariesAreExact();
    }

    private static void geometryIsNineByNineSerpentine() {
        List<BlockPos> all = FarmPlotGeometry.allCells(CENTER);
        List<BlockPos> soil = FarmPlotGeometry.soilCells(CENTER);
        require(all.size() == 81, "all-cell count");
        require(soil.size() == 80, "soil-cell count");
        require(new HashSet<>(all).size() == 81, "all cells unique");
        require(new HashSet<>(soil).size() == 80, "soil cells unique");
        require(all.contains(CENTER), "center included in full footprint");
        require(!soil.contains(CENTER), "center omitted from soil footprint");
        require(soil.contains(CENTER.offset(-4, 0, -4)), "northwest corner");
        require(soil.contains(CENTER.offset(4, 0, 4)), "southeast corner");
        require(!all.contains(CENTER.offset(5, 0, 0)), "offset five excluded");
        require(all.equals(FarmPlotGeometry.allCells(CENTER)), "deterministic order");
        for (int i = 1; i < all.size(); i++) {
            BlockPos previous = all.get(i - 1);
            BlockPos current = all.get(i);
            int distance = Math.abs(previous.getX() - current.getX())
                    + Math.abs(previous.getZ() - current.getZ());
            require(distance == 1, "serpentine adjacency at " + i);
        }
        List<BlockPos> envelope = FarmPlotGeometry.scanEnvelope(CENTER);
        require(envelope.size() == 11 * 11 * 5, "complete scan envelope");
        require(new HashSet<>(envelope).size() == envelope.size(), "scan envelope unique");
    }

    private static void candidateOrderAndCapsAreExact() {
        List<BlockPos> candidates = FarmPlotGeometry.autoCandidateCenters(CENTER);
        require(candidates.size() <= FarmPlotPolicy.MAX_CANDIDATE_CENTERS, "candidate hard cap");
        require(candidates.get(0).equals(CENTER), "anchor is first candidate");
        require(candidates.stream().anyMatch(candidate ->
                        FarmPlotGeometry.horizontalDistanceSquared(CENTER, candidate)
                                == (long) FarmPlotPolicy.AUTO_SEARCH_RADIUS
                                * FarmPlotPolicy.AUTO_SEARCH_RADIUS),
                "candidate sampling reaches full auto radius");
        for (int i = 1; i < candidates.size(); i++) {
            BlockPos previous = candidates.get(i - 1);
            BlockPos current = candidates.get(i);
            long previousDistance = FarmPlotGeometry.horizontalDistanceSquared(CENTER, previous);
            long currentDistance = FarmPlotGeometry.horizontalDistanceSquared(CENTER, current);
            require(previousDistance <= currentDistance, "candidate distance order at " + i);
            require(currentDistance <= (long) FarmPlotPolicy.AUTO_SEARCH_RADIUS
                    * FarmPlotPolicy.AUTO_SEARCH_RADIUS, "candidate within radius");
        }

        FakeWorldView rejected = FakeWorldView.flat(CENTER.getY());
        rejected.world = new FarmSiteWorldView.WorldObservation(true, true, true, false);
        FarmSitePlanner planner = FarmSitePlanner.auto(rejected, CENTER);
        FarmSitePlanner.TickResult result = runToEnd(planner, FarmPlotPolicy.MAX_WORLD_READS_PER_TICK);
        require(result.status() == FarmSitePlanner.Status.NO_SUITABLE_SITE,
                "ultra-warm candidate set rejected");
        require(result.candidatesCompleted() == candidates.size(),
                "every sampled candidate completed");
        require(result.totalReads() == candidates.size(),
                "world-level rejection spends one read per candidate");
    }

    private static void flatGapStoneAndExplicitResumeAreAccepted() {
        FarmSitePlan flat = selectExplicit(FakeWorldView.flat(CENTER.getY()), CENTER);
        require(flat.waterRequired(), "new flat site requires water");
        require(flat.grassColumns() == 81, "flat grass evidence");
        require(flat.repairColumns() == 0, "flat site needs no repair columns");
        require(flat.clearActions().size() == 1, "flat site opens only center");
        require(flat.fillActions().isEmpty(), "flat site needs no dirt");
        require(flat.tillActions().size() == 80, "flat site tills exactly 80 cells");
        require(flat.requiredHoeDamage() == 80, "flat site exact hoe damage");
        require(flat.waterAction() != null, "flat site carries water stance");
        require(flat.clearActions().stream().allMatch(action -> action.stance() != null)
                        && flat.tillActions().stream().allMatch(action -> action.stance() != null),
                "every flat-site mutation carries a stance");

        BlockPos dirt = CENTER.offset(1, 0, -1);
        FakeWorldView dirtView = FakeWorldView.flat(CENTER.getY());
        dirtView.set(dirt, dirtView.cell(FarmSiteWorldView.BlockKind.DIRT));
        FarmSitePlan dirtPlan = selectExplicit(dirtView, CENTER);
        require(dirtPlan.tillActions().stream().anyMatch(action -> action.target().equals(dirt)),
                "plain dirt is directly tillable");

        BlockPos gap = CENTER.offset(2, 0, 1);
        FakeWorldView gapView = FakeWorldView.flat(CENTER.getY());
        gapView.set(gap, gapView.cell(FarmSiteWorldView.BlockKind.AIR));
        FarmSitePlan gapPlan = selectExplicit(gapView, CENTER);
        require(gapPlan.repairColumns() == 1, "one gap is one repair column");
        require(gapPlan.fillActions().stream().anyMatch(action -> action.target().equals(gap)),
                "one gap receives dirt");
        require(gapPlan.tillActions().stream().anyMatch(action -> action.target().equals(gap)
                        && action.expectedStateFingerprint().equals(
                        FarmSiteWorldView.stateFingerprint(Blocks.DIRT.defaultBlockState()))),
                "gap till gate expects phase-final dirt");

        BlockPos stone = CENTER.offset(-2, 0, 1);
        FakeWorldView stoneView = FakeWorldView.flat(CENTER.getY());
        stoneView.set(stone, stoneView.cell(FarmSiteWorldView.BlockKind.STONE));
        FarmSitePlan stonePlan = selectExplicit(stoneView, CENTER);
        require(stonePlan.repairColumns() == 1, "one stone is one repair column");
        require(stonePlan.clearActions().stream().anyMatch(action -> action.target().equals(stone)
                        && action.purpose() == FarmSitePlan.ClearPurpose.REPLACE_STONE),
                "one stone is cleared before fill");
        require(stonePlan.tillActions().stream().anyMatch(action -> action.target().equals(stone)
                        && action.expectedStateFingerprint().equals(
                        FarmSiteWorldView.stateFingerprint(Blocks.DIRT.defaultBlockState()))),
                "stone-repair till gate expects phase-final dirt");

        FakeWorldView adjacentRepairs = FakeWorldView.flat(CENTER.getY());
        BlockPos firstRepair = CENTER.offset(-2, 0, -2);
        BlockPos secondRepair = firstRepair.east();
        adjacentRepairs.set(firstRepair, adjacentRepairs.cell(
                FarmSiteWorldView.BlockKind.STONE));
        adjacentRepairs.set(secondRepair, adjacentRepairs.cell(
                FarmSiteWorldView.BlockKind.STONE));
        FarmSitePlan adjacentPlan = selectExplicit(adjacentRepairs, CENTER);
        java.util.Set<BlockPos> unstableSupports = new java.util.HashSet<>();
        adjacentPlan.clearActions().forEach(action -> unstableSupports.add(action.target()));
        adjacentPlan.fillActions().forEach(action -> unstableSupports.add(action.target()));
        require(adjacentPlan.clearActions().stream().allMatch(
                        action -> !unstableSupports.contains(action.stance().below()))
                        && adjacentPlan.fillActions().stream().allMatch(
                        action -> !unstableSupports.contains(action.stance().below())),
                "clear/fill stances never depend on pending repair supports");

        BlockPos vegetation = CENTER.offset(3, 0, 2);
        FakeWorldView vegetationView = FakeWorldView.flat(CENTER.getY());
        vegetationView.set(vegetation, vegetationView.cell(
                FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION));
        FarmSitePlan vegetationPlan = selectExplicit(vegetationView, CENTER);
        require(vegetationPlan.clearActions().stream().anyMatch(
                        action -> action.target().equals(vegetation)),
                "replaceable surface vegetation is cleared");
        require(vegetationPlan.fillActions().stream().anyMatch(
                        action -> action.target().equals(vegetation)),
                "vegetation gap is filled");

        BlockPos raised = CENTER.offset(-3, 0, 2).above();
        FakeWorldView raisedView = FakeWorldView.flat(CENTER.getY());
        raisedView.set(raised, raisedView.cell(FarmSiteWorldView.BlockKind.DIRT));
        FarmSitePlan raisedPlan = selectExplicit(raisedView, CENTER);
        require(raisedPlan.clearActions().stream().anyMatch(action -> action.target().equals(raised)
                        && action.purpose() == FarmSitePlan.ClearPurpose.LEVEL_SURFACE),
                "one-block raised dirt is leveled");

        FakeWorldView exactRepairCap = FakeWorldView.flat(CENTER.getY());
        for (int i = 0; i < FarmPlotPolicy.MAX_REPAIR_COLUMNS; i++) {
            BlockPos target = FarmPlotGeometry.soilCells(CENTER).get(i);
            exactRepairCap.set(target, exactRepairCap.cell(FarmSiteWorldView.BlockKind.STONE));
        }
        FarmSitePlan capped = selectExplicit(exactRepairCap, CENTER);
        require(capped.repairColumns() == FarmPlotPolicy.MAX_REPAIR_COLUMNS,
                "exact repair cap remains acceptable");

        FakeWorldView resumeView = FakeWorldView.flat(CENTER.getY());
        for (BlockPos soil : FarmPlotGeometry.soilCells(CENTER)) {
            resumeView.set(soil, resumeView.cell(FarmSiteWorldView.BlockKind.FARMLAND));
        }
        resumeView.set(CENTER, resumeView.cell(FarmSiteWorldView.BlockKind.STANDALONE_WATER));
        FarmSitePlan resume = selectExplicit(resumeView, CENTER);
        require(!resume.waterRequired(), "valid explicit source skips water work");
        require(resume.existingFarmland() == 80, "resume recognizes all existing farmland");
        require(resume.tillActions().isEmpty(), "existing farmland consumes no hoe durability");
        require(resume.fillActions().isEmpty(), "existing farmland consumes no dirt");
        require(resume.clearActions().isEmpty(), "complete resume has no clear actions");
        require(resume.estimatedEdits() == 0, "complete resume is a zero-edit manifest");

        FakeWorldView partialResumeView = FakeWorldView.flat(CENTER.getY());
        for (BlockPos soil : FarmPlotGeometry.soilCells(CENTER)) {
            partialResumeView.set(soil,
                    partialResumeView.cell(FarmSiteWorldView.BlockKind.FARMLAND));
        }
        BlockPos remainingDirt = CENTER.offset(2, 0, 0);
        partialResumeView.set(remainingDirt,
                partialResumeView.cell(FarmSiteWorldView.BlockKind.DIRT));
        partialResumeView.set(CENTER,
                partialResumeView.cell(FarmSiteWorldView.BlockKind.STANDALONE_WATER));
        FarmSitePlan partialResume = selectExplicit(partialResumeView, CENTER);
        require(partialResume.tillActions().size() == 1
                        && partialResume.tillActions().get(0).target().equals(remainingDirt),
                "partial resume retains the one remaining till action");
        BlockPos partialResumeSupport = partialResume.tillActions().get(0).stance().below();
        require(partialResumeView.observe(partialResumeSupport).blockKind()
                        == FarmSiteWorldView.BlockKind.FARMLAND,
                "partial resume may stand on surrounding farmland without planner rejection");
    }

    private static void resumeReplanningPreservesMarginalSiteIdentity() {
        FarmSitePlan initialBoundary = selectExplicit(minimumGrassAtRepairCap(), CENTER);
        require(initialBoundary.grassColumns()
                        == FarmPlotPolicy.MIN_NEW_SITE_GRASS_COLUMNS,
                "fixture starts at the exact new-site grass boundary");

        FakeWorldView centerAir = minimumGrassAtRepairCap();
        centerAir.set(CENTER, centerAir.cell(FarmSiteWorldView.BlockKind.AIR));
        require(selectExplicitOrNull(centerAir, CENTER) == null,
                "ordinary explicit planning does not waive consumed center grass");
        FarmSitePlan airResume = selectResume(centerAir, CENTER);
        require(airResume.waterRequired() && airResume.waterAction() != null,
                "resume accepts the pinned marginal site after opening its center");

        FakeWorldView centerSource = minimumGrassAtRepairCap();
        centerSource.set(CENTER,
                centerSource.cell(FarmSiteWorldView.BlockKind.STANDALONE_WATER));
        require(selectExplicitOrNull(centerSource, CENTER) == null,
                "ordinary explicit planning does not count center water as grass evidence");
        FarmSitePlan sourceResume = selectResume(centerSource, CENTER);
        require(!sourceResume.waterRequired() && sourceResume.waterAction() == null,
                "resume accepts the pinned marginal site after placing its source");

        FakeWorldView raisedGrass = minimumGrassAtRepairCap();
        BlockPos leveledSurface = FarmPlotGeometry.soilCells(CENTER)
                .get(FarmPlotPolicy.MAX_REPAIR_COLUMNS);
        raisedGrass.set(leveledSurface,
                raisedGrass.cell(FarmSiteWorldView.BlockKind.DIRT));
        raisedGrass.set(leveledSurface.above(),
                raisedGrass.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK));
        FarmSitePlan beforeLeveling = selectExplicit(raisedGrass, CENTER);
        require(beforeLeveling.grassColumns()
                        == FarmPlotPolicy.MIN_NEW_SITE_GRASS_COLUMNS,
                "raised-grass fixture starts at the exact eligibility boundary");
        require(beforeLeveling.clearActions().stream().anyMatch(action ->
                        action.target().equals(leveledSurface.above())
                                && action.purpose()
                                == FarmSitePlan.ClearPurpose.LEVEL_SURFACE),
                "raised grass supplies the last initial eligibility column");
        raisedGrass.set(leveledSurface.above(),
                raisedGrass.cell(FarmSiteWorldView.BlockKind.AIR));
        require(selectExplicitOrNull(raisedGrass, CENTER) == null,
                "ordinary explicit planning rejects after raised grass becomes dirt");
        FarmSitePlan leveledResume = selectResume(raisedGrass, CENTER);
        require(leveledResume.tillActions().stream().anyMatch(action ->
                        action.target().equals(leveledSurface)),
                "resume reconstructs work after raised grass is leveled to dirt");

        FakeWorldView overRepairCap = minimumGrassAtRepairCap();
        BlockPos extraRepair = FarmPlotGeometry.soilCells(CENTER)
                .get(FarmPlotPolicy.MAX_REPAIR_COLUMNS);
        overRepairCap.set(extraRepair,
                overRepairCap.cell(FarmSiteWorldView.BlockKind.STONE));
        require(selectResumeOrNull(overRepairCap, CENTER) == null,
                "resume still enforces the repair-column cap");

        FakeWorldView protectedResume = minimumGrassAtRepairCap();
        BlockPos protectedSoil = FarmPlotGeometry.soilCells(CENTER)
                .get(FarmPlotPolicy.MAX_REPAIR_COLUMNS);
        protectedResume.set(protectedSoil,
                protectedResume.withProtected(protectedResume.observe(protectedSoil)));
        protectedResume.resetReads();
        require(selectResumeOrNull(protectedResume, CENTER) == null,
                "resume still enforces current protection facts");

        FakeWorldView freezingResume = minimumGrassAtRepairCap();
        freezingResume.set(CENTER, freezingResume.withClimate(
                freezingResume.cell(FarmSiteWorldView.BlockKind.AIR), false, 0));
        FarmSitePlanner.TickResult freezeResult = runToEnd(
                FarmSitePlanner.resume(freezingResume, CENTER, CENTER), 2_048);
        require(freezeResult.status() == FarmSitePlanner.Status.FREEZE_RISK
                        && freezeResult.selectedPlan() == null,
                "resume still enforces the current freeze gate");
    }

    private static void tillSequenceIncludesPriorFarmlandStances() {
        FarmSitePlan plan = selectExplicit(FakeWorldView.flat(CENTER.getY()), CENTER);
        Set<BlockPos> completedTargets = new HashSet<>();
        boolean foundPriorFarmlandSupport = false;
        for (FarmSitePlan.TillAction action : plan.tillActions()) {
            if (completedTargets.contains(action.stance().below())) {
                foundPriorFarmlandSupport = true;
                break;
            }
            completedTargets.add(action.target());
        }
        require(foundPriorFarmlandSupport,
                "serpentine till sequence exercises a stance supported by earlier farmland");
    }

    private static void unsafeFactsAreRejectedWithoutAPlan() {
        BlockPos soil = CENTER.offset(1, 0, 1);
        assertRejected(view -> view.set(soil.below(), view.cell(FarmSiteWorldView.BlockKind.AIR)),
                "deep void/support gap");
        assertRejected(view -> view.set(soil, view.cell(FarmSiteWorldView.BlockKind.OTHER)),
                "unsupported surface");
        assertRejected(view -> view.set(soil, view.withBlockEntity(view.cell(
                        FarmSiteWorldView.BlockKind.GRASS_BLOCK))),
                "block entity");
        assertRejected(view -> view.set(soil, view.withFalling(view.cell(
                        FarmSiteWorldView.BlockKind.GRASS_BLOCK))),
                "falling hazard");
        assertRejected(view -> view.set(soil, view.withUnbreakable(view.cell(
                        FarmSiteWorldView.BlockKind.GRASS_BLOCK))),
                "unbreakable state");
        assertRejected(view -> view.set(soil.above(2), view.cell(
                        FarmSiteWorldView.BlockKind.STONE)),
                "blocked second headroom");
        assertRejected(view -> view.set(CENTER.offset(5, 0, 0), view.withFluid(
                        view.cell(FarmSiteWorldView.BlockKind.OTHER),
                        FarmSiteWorldView.FluidKind.FLOWING_WATER)),
                "adjacent flowing liquid");
        assertRejected(view -> view.set(CENTER, view.withFluid(
                        view.cell(FarmSiteWorldView.BlockKind.OTHER),
                        FarmSiteWorldView.FluidKind.OTHER_WATER_SOURCE)),
                "waterlogged/bubble-like center");
        assertRejected(view -> view.set(soil, view.withLoaded(
                        view.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK), false)),
                "unloaded cell");
        assertRejected(view -> view.set(soil, view.withBorder(
                        view.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK), false)),
                "world-border exclusion");
        assertRejected(view -> view.set(soil, view.withBuildHeight(
                        view.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK), false)),
                "build-height exclusion");
        assertRejected(view -> view.world = new FarmSiteWorldView.WorldObservation(
                        false, false, true, false),
                "missing protection store");
        assertRejected(view -> view.world = new FarmSiteWorldView.WorldObservation(
                        false, true, false, false),
                "missing waypoint store");
        assertRejected(view -> view.world = new FarmSiteWorldView.WorldObservation(
                        false, true, true, true),
                "non-farm waypoint collision");

        FakeWorldView tooManyRepairs = FakeWorldView.flat(CENTER.getY());
        for (int i = 0; i < FarmPlotPolicy.MAX_REPAIR_COLUMNS + 1; i++) {
            BlockPos target = FarmPlotGeometry.soilCells(CENTER).get(i);
            tooManyRepairs.set(target, tooManyRepairs.cell(FarmSiteWorldView.BlockKind.STONE));
        }
        require(selectExplicitOrNull(tooManyRepairs, CENTER) == null, "repair cap rejection");

        FakeWorldView weakEvidence = FakeWorldView.flat(CENTER.getY());
        for (int i = 0; i < 17; i++) {
            BlockPos target = FarmPlotGeometry.soilCells(CENTER).get(i);
            weakEvidence.set(target, weakEvidence.cell(FarmSiteWorldView.BlockKind.DIRT));
        }
        require(selectExplicitOrNull(weakEvidence, CENTER) == null, "65-column evidence threshold");
    }

    private static void denseGroundCoverAndOneLayerLevelingAreAccepted() {
        FarmSitePlan flat = selectExplicit(FakeWorldView.flat(CENTER.getY()), CENTER);

        FakeWorldView meadow = FakeWorldView.flat(CENTER.getY());
        for (BlockPos cell : FarmPlotGeometry.allCells(CENTER)) {
            meadow.set(cell.above(), meadow.cell(
                    FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION));
            meadow.set(cell.above(2), meadow.cell(
                    FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION));
        }
        FarmSitePlan meadowPlan = selectExplicit(meadow, CENTER);
        long meadowClears = meadowPlan.clearActions().stream()
                .filter(action -> action.purpose() == FarmSitePlan.ClearPurpose.VEGETATION)
                .count();
        require(meadowPlan.repairColumns() == 0,
                "dense ground cover consumes no structural repair columns");
        require(meadowClears == FarmPlotPolicy.PLOT_CELL_COUNT * 2L,
                "both blocks of dense tall ground cover remain physical clear actions");
        require(meadowPlan.clearActions().size() <= FarmPlotPolicy.MAX_CLEAR_ACTIONS,
                "dense tall ground cover fits the fixed clear manifest");
        require(meadowPlan.rankingEdits() == flat.rankingEdits(),
                "ground cover is neutral in site fitness");
        require(meadowPlan.estimatedEdits() > flat.estimatedEdits(),
                "physical ground-cover work remains visible in execution totals");

        FakeWorldView raised = FakeWorldView.flat(CENTER.getY());
        int haloRadius = FarmPlotPolicy.HYDRATION_RADIUS + 1;
        for (int dz = -haloRadius; dz <= haloRadius; dz++) {
            for (int dx = -haloRadius; dx <= haloRadius; dx++) {
                BlockPos cell = CENTER.offset(dx, 0, dz);
                raised.set(cell, raised.cell(FarmSiteWorldView.BlockKind.DIRT));
                raised.set(cell.above(), raised.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK));
            }
        }
        FarmSitePlan raisedPlan = selectExplicit(raised, CENTER);
        require(raisedPlan.repairColumns() == 0,
                "one-layer leveling is distinct from bounded gap/stone repairs");
        require(raisedPlan.clearActions().stream().filter(action ->
                        action.purpose() == FarmSitePlan.ClearPurpose.LEVEL_SURFACE).count()
                        == FarmPlotPolicy.PLOT_CELL_COUNT,
                "a complete one-block grass layer is scheduled for leveling");
        require(raisedPlan.clearActions().stream().anyMatch(action ->
                        action.purpose() == FarmSitePlan.ClearPurpose.LEVEL_SURFACE
                                && action.stance().getY() == CENTER.getY() + 2),
                "raised terrain extending through the scan halo uses an elevated clear stance");

        Set<BlockPos> simulatedCleared = new HashSet<>();
        for (FarmSitePlan.ClearAction action : raisedPlan.clearActions()) {
            if (action.purpose() == FarmSitePlan.ClearPurpose.OPEN_CENTER) {
                continue;
            }
            require(simulatedOpenStanceCell(raised, action.stance(), simulatedCleared)
                            && simulatedOpenStanceCell(
                            raised, action.stance().above(), simulatedCleared),
                    "each one-layer clear stance is executable after prior clears");
            simulatedCleared.add(action.target());
        }
    }

    private static void stanceProtectionAndFreezeAreRejected() {
        FakeWorldView protectedStances = FakeWorldView.flat(CENTER.getY());
        for (BlockPos stance : FarmPlotGeometry.stanceCandidates(CENTER, CENTER)) {
            protectedStances.set(stance, protectedStances.withProtected(protectedStances.observe(stance)));
        }
        protectedStances.resetReads();
        require(selectExplicitOrNull(protectedStances, CENTER) == null,
                "tracked required stances reject candidate");

        FakeWorldView freezing = FakeWorldView.flat(CENTER.getY());
        freezing.set(CENTER, freezing.withClimate(freezing.observe(CENTER), false, 0));
        freezing.resetReads();
        FarmSitePlanner freezePlanner = FarmSitePlanner.explicit(freezing, CENTER, CENTER);
        FarmSitePlanner.TickResult freezeResult = runToEnd(freezePlanner, 2_048);
        require(freezeResult.status() == FarmSitePlanner.Status.FREEZE_RISK
                        && freezeResult.selectedPlan() == null,
                "hypothetical source freeze rejected with typed status");
    }

    private static void incrementalCursorPausesAtExactRead() {
        FakeWorldView view = FakeWorldView.flat(CENTER.getY());
        FarmSitePlanner planner = FarmSitePlanner.auto(view, CENTER);
        FarmSitePlanner.TickResult first = planner.onTick(100);
        require(first.status() == FarmSitePlanner.Status.RUNNING, "partial scan remains running");
        require(first.readsThisTick() == 100, "requested partition honored exactly");
        require(first.candidatesCompleted() == 0, "candidate pauses mid-snapshot");
        FarmSitePlanner.TickResult second = planner.onTick(100);
        require(second.totalReads() == 200, "cursor resumes without duplicate reads");
        require(second.candidatesCompleted() == 0, "same candidate still partial");
        for (int i = 0; i < 3; i++) {
            planner.onTick(100);
        }
        require(planner.totalReads() == 500, "partitioned read total exact");
    }

    private static void tickPartitionDoesNotChangeSelection() {
        FarmSitePlanner large = FarmSitePlanner.auto(FakeWorldView.flat(CENTER.getY()), CENTER);
        FarmSitePlanner small = FarmSitePlanner.auto(FakeWorldView.flat(CENTER.getY()), CENTER);
        FarmSitePlanner.TickResult largeResult = runToEnd(large, 2_048);
        FarmSitePlanner.TickResult smallResult = runToEnd(small, 1_600);
        require(largeResult.status() == FarmSitePlanner.Status.SELECTED, "large partition selected");
        require(smallResult.status() == FarmSitePlanner.Status.SELECTED, "small partition selected");
        require(largeResult.selectedPlan().center().equals(smallResult.selectedPlan().center()),
                "partition-independent center");
        require(largeResult.selectedPlan().fingerprint().equals(
                        smallResult.selectedPlan().fingerprint()),
                "partition-independent frozen manifest");
        require(largeResult.selectedPlan().acquisitionReturnStance().equals(
                        largeResult.selectedPlan().clearActions().get(0).stance())
                        && !largeResult.selectedPlan().acquisitionReturnStance().equals(
                                largeResult.selectedPlan().center()),
                "manifest freezes an initially valid feet stance instead of the future water cell");
        require(largeResult.totalReads() == smallResult.totalReads(),
                "partition-independent completed read sequence");
        require(large.ticks() <= FarmPlotPolicy.SITE_SELECTION_TICKS
                        && small.ticks() <= FarmPlotPolicy.SITE_SELECTION_TICKS,
                "both partitions fit site watchdog");
    }

    private static void rankingUsesEditsThenGrassThenDistance() {
        FakeWorldView vegetationView = FakeWorldView.flat(CENTER.getY());
        vegetationView.set(CENTER.above(), vegetationView.cell(
                FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION));
        FarmSitePlan vegetationWinner = runToEnd(
                FarmSitePlanner.auto(vegetationView, CENTER), 2_048).selectedPlan();
        require(vegetationWinner != null && vegetationWinner.center().equals(CENTER),
                "neutral vegetation leaves distance as the site tie-break");

        FakeWorldView levelView = FakeWorldView.flat(CENTER.getY());
        levelView.set(CENTER.above(), levelView.cell(FarmSiteWorldView.BlockKind.GRASS_BLOCK));
        FarmSitePlan levelWinner = runToEnd(
                FarmSitePlanner.auto(levelView, CENTER), 2_048).selectedPlan();
        require(levelWinner != null, "structural edit ranking produced a winner");
        require(FarmPlotGeometry.horizontalDistanceSquared(CENTER, levelWinner.center()) >= 25,
                "fewer structural edits outrank a nearer one-layer repair");

        FakeWorldView grassView = FakeWorldView.flat(CENTER.getY());
        grassView.set(CENTER, grassView.cell(FarmSiteWorldView.BlockKind.DIRT));
        FarmSitePlan grassWinner = runToEnd(
                FarmSitePlanner.auto(grassView, CENTER), 2_048).selectedPlan();
        require(grassWinner != null && grassWinner.grassColumns() == 81,
                "more grass outranks nearer equal-edit candidate");
        require(FarmPlotGeometry.horizontalDistanceSquared(CENTER, grassWinner.center()) >= 25,
                "grass ranking precedes anchor distance");
    }

    private static void planningRejectsOffThreadBeforeReading() {
        FakeWorldView view = FakeWorldView.flat(CENTER.getY());
        FarmSitePlanner planner = FarmSitePlanner.explicit(view, CENTER, CENTER);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                planner.onTick();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "farm-planner-off-thread-fixture");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("off-thread fixture interrupted", interrupted);
        }
        require(failure.get() instanceof IllegalStateException, "off-thread call rejected");
        require(view.reads == 0, "off-thread rejection occurs before world access");
    }

    private static void manifestIsImmutableAndRevalidatesExactFacts() {
        FakeWorldView view = FakeWorldView.flat(CENTER.getY());
        FarmSitePlan plan = selectExplicit(view, CENTER);
        FarmSitePlanner.RevalidationResult unchanged = FarmSitePlanner.revalidate(plan, view);
        require(unchanged.valid(), "unchanged manifest revalidates");
        require(unchanged.reads() == FarmPlotGeometry.scanEnvelope(CENTER).size() + 1,
                "revalidation reads exact frozen envelope");
        require(unchanged.expectedFingerprint().equals(unchanged.observedFingerprint()),
                "unchanged fingerprint stable");

        boolean immutable = false;
        try {
            plan.tillActions().clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        require(immutable, "manifest action lists immutable");

        BlockPos frozenStance = plan.waterAction().stance();
        view.set(frozenStance, view.withProtected(view.observe(frozenStance)));
        view.resetReads();
        FarmSitePlanner.RevalidationResult drift = FarmSitePlanner.revalidate(plan, view);
        require(!drift.valid(), "stance protection drift invalidates manifest");
        require(!drift.expectedFingerprint().equals(drift.observedFingerprint()),
                "drift changes diagnostic fingerprint");
    }

    private static void watchdogBoundariesAreExact() {
        FarmTaskWatchdog watchdog = new FarmTaskWatchdog();
        for (int i = 0; i < FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS - 1; i++) {
            watchdog.tick();
        }
        require(!watchdog.mutationStalled(), "stall waits for exact boundary");
        watchdog.tick();
        require(watchdog.mutationStalled(), "stall boundary");
        watchdog.markProgress();
        require(!watchdog.mutationStalled(), "progress resets stall");
        watchdog.enterPhase(FarmTaskPhase.SELECT_SITE);
        require(watchdog.phase() == FarmTaskPhase.SELECT_SITE, "phase transition");
        require(watchdog.phaseTicks() == 0, "phase ticks reset");
        watchdog.tick();
        int wholeBeforeRestart = watchdog.wholeTicks();
        watchdog.restartFromCheckpoint();
        require(watchdog.wholeTicks() == wholeBeforeRestart,
                "checkpoint restart preserves the whole-operation deadline");
        require(watchdog.phase() == FarmTaskPhase.PRECHECK
                        && watchdog.phaseTicks() == 0
                        && watchdog.noProgressTicks() == 0,
                "checkpoint restart re-arms only phase-local deadlines");
        watchdog.reset();
        require(watchdog.phase() == FarmTaskPhase.PRECHECK
                        && watchdog.wholeTicks() == 0
                        && watchdog.phaseTicks() == 0
                        && watchdog.noProgressTicks() == 0,
                "root-task reuse resets every watchdog counter");
    }

    private static FarmSitePlan selectExplicit(FakeWorldView view, BlockPos center) {
        FarmSitePlan selected = selectExplicitOrNull(view, center);
        require(selected != null, "expected explicit site to be selected");
        return selected;
    }

    private static FarmSitePlan selectExplicitOrNull(FakeWorldView view, BlockPos center) {
        FarmSitePlanner planner = FarmSitePlanner.explicit(view, center, center);
        FarmSitePlanner.TickResult result = runToEnd(planner, 2_048);
        require(result.status() == FarmSitePlanner.Status.SELECTED
                        || result.status() == FarmSitePlanner.Status.NO_SUITABLE_SITE
                        || result.status() == FarmSitePlanner.Status.FREEZE_RISK,
                "explicit planner terminates deterministically");
        return result.selectedPlan();
    }

    private static FarmSitePlan selectResume(FakeWorldView view, BlockPos center) {
        FarmSitePlan selected = selectResumeOrNull(view, center);
        require(selected != null, "expected resume site to be selected");
        return selected;
    }

    private static FarmSitePlan selectResumeOrNull(FakeWorldView view, BlockPos center) {
        FarmSitePlanner planner = FarmSitePlanner.resume(view, center, center);
        FarmSitePlanner.TickResult result = runToEnd(planner, 2_048);
        require(result.status() == FarmSitePlanner.Status.SELECTED
                        || result.status() == FarmSitePlanner.Status.NO_SUITABLE_SITE
                        || result.status() == FarmSitePlanner.Status.FREEZE_RISK,
                "resume planner terminates deterministically");
        return result.selectedPlan();
    }

    private static FakeWorldView minimumGrassAtRepairCap() {
        FakeWorldView view = FakeWorldView.flat(CENTER.getY());
        for (int i = 0; i < FarmPlotPolicy.MAX_REPAIR_COLUMNS; i++) {
            BlockPos target = FarmPlotGeometry.soilCells(CENTER).get(i);
            view.set(target, view.cell(FarmSiteWorldView.BlockKind.STONE));
        }
        return view;
    }

    private static FarmSitePlanner.TickResult runToEnd(FarmSitePlanner planner, int budget) {
        FarmSitePlanner.TickResult result = null;
        for (int tick = 0; tick < FarmPlotPolicy.SITE_SELECTION_TICKS
                && !planner.isFinished(); tick++) {
            result = planner.onTick(budget);
            require(result.readsThisTick() <= Math.min(
                            budget, FarmPlotPolicy.MAX_WORLD_READS_PER_TICK),
                    "per-tick read cap");
        }
        require(result != null && planner.isFinished(), "planner terminates within site watchdog");
        return result;
    }

    private static void assertRejected(ViewMutation mutation, String label) {
        FakeWorldView view = FakeWorldView.flat(CENTER.getY());
        mutation.apply(view);
        view.resetReads();
        require(selectExplicitOrNull(view, CENTER) == null, label + " rejected");
    }

    private static boolean simulatedOpenStanceCell(
            FakeWorldView view,
            BlockPos position,
            Set<BlockPos> simulatedCleared) {
        if (simulatedCleared.contains(position)) {
            return true;
        }
        FarmSiteWorldView.CellObservation observation = view.observe(position);
        return observation.blockKind() == FarmSiteWorldView.BlockKind.AIR
                || (observation.blockKind()
                == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION
                && observation.collisionEmpty());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ViewMutation {
        void apply(FakeWorldView view);
    }

    private static final class FakeWorldView implements FarmSiteWorldView {
        private final Thread serverThread = Thread.currentThread();
        private final int surfaceY;
        private final Map<BlockPos, CellObservation> overrides = new HashMap<>();
        private WorldObservation world = new WorldObservation(false, true, true, false);
        private int reads;

        private FakeWorldView(int surfaceY) {
            this.surfaceY = surfaceY;
        }

        private static FakeWorldView flat(int surfaceY) {
            return new FakeWorldView(surfaceY);
        }

        @Override
        public void assertServerThread() {
            if (Thread.currentThread() != serverThread) {
                throw new IllegalStateException("fake world read off server thread");
            }
        }

        @Override
        public CellObservation observe(BlockPos position) {
            assertServerThread();
            reads++;
            CellObservation overridden = overrides.get(position);
            if (overridden != null) {
                return overridden;
            }
            if (position.getY() == surfaceY - 1) {
                return cell(BlockKind.STONE);
            }
            if (position.getY() == surfaceY) {
                return cell(BlockKind.GRASS_BLOCK);
            }
            return cell(BlockKind.AIR);
        }

        @Override
        public WorldObservation observeWorld(BlockPos center) {
            assertServerThread();
            reads++;
            return world;
        }

        private void set(BlockPos position, CellObservation observation) {
            overrides.put(position.immutable(), observation);
        }

        private void resetReads() {
            reads = 0;
        }

        private CellObservation cell(BlockKind kind) {
            FluidKind fluid = kind == BlockKind.STANDALONE_WATER
                    ? FluidKind.STANDALONE_WATER_SOURCE
                    : FluidKind.NONE;
            boolean replaceable = kind == BlockKind.AIR
                    || kind == BlockKind.REPLACEABLE_VEGETATION;
            boolean support = kind == BlockKind.GRASS_BLOCK
                    || kind == BlockKind.DIRT
                    || kind == BlockKind.STONE
                    || kind == BlockKind.FARMLAND;
            return new CellObservation(
                    true,
                    true,
                    true,
                    kind,
                    fluid,
                    replaceable,
                    kind == BlockKind.AIR || kind == BlockKind.REPLACEABLE_VEGETATION,
                    kind == BlockKind.CROP,
                    false,
                    false,
                    false,
                    support,
                    kind != BlockKind.STANDALONE_WATER,
                    false,
                    true,
                    15,
                    "fixture:" + kind.name().toLowerCase());
        }

        private CellObservation withProtected(CellObservation source) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(),
                    source.unbreakable(), true, source.warmEnoughToRain(), source.blockLight());
        }

        private CellObservation withBlockEntity(CellObservation source) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), true, source.fallingHazard(), source.unbreakable(),
                    source.playerProtected(), source.warmEnoughToRain(), source.blockLight());
        }

        private CellObservation withFalling(CellObservation source) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), true, source.unbreakable(),
                    source.playerProtected(), source.warmEnoughToRain(), source.blockLight());
        }

        private CellObservation withUnbreakable(CellObservation source) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(), true,
                    source.playerProtected(), source.warmEnoughToRain(), source.blockLight());
        }

        private CellObservation withFluid(CellObservation source, FluidKind fluid) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    fluid, source.blockEntity(), source.fallingHazard(), source.unbreakable(),
                    source.playerProtected(), source.warmEnoughToRain(), source.blockLight());
        }

        private CellObservation withLoaded(CellObservation source, boolean loaded) {
            return copy(source, loaded, source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(),
                    source.unbreakable(), source.playerProtected(), source.warmEnoughToRain(),
                    source.blockLight());
        }

        private CellObservation withBorder(CellObservation source, boolean withinBorder) {
            return copy(source, source.loaded(), source.inBuildHeight(), withinBorder,
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(),
                    source.unbreakable(), source.playerProtected(), source.warmEnoughToRain(),
                    source.blockLight());
        }

        private CellObservation withBuildHeight(CellObservation source, boolean inBuildHeight) {
            return copy(source, source.loaded(), inBuildHeight, source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(),
                    source.unbreakable(), source.playerProtected(), source.warmEnoughToRain(),
                    source.blockLight());
        }

        private CellObservation withClimate(
                CellObservation source,
                boolean warmEnoughToRain,
                int blockLight) {
            return copy(source, source.loaded(), source.inBuildHeight(), source.withinWorldBorder(),
                    source.fluidKind(), source.blockEntity(), source.fallingHazard(),
                    source.unbreakable(), source.playerProtected(), warmEnoughToRain, blockLight);
        }

        private CellObservation copy(
                CellObservation source,
                boolean loaded,
                boolean inBuildHeight,
                boolean withinBorder,
                FluidKind fluid,
                boolean blockEntity,
                boolean falling,
                boolean unbreakable,
                boolean protectedByPlayer,
                boolean warmEnoughToRain,
                int blockLight) {
            return new CellObservation(
                    loaded,
                    inBuildHeight,
                    withinBorder,
                    source.blockKind(),
                    fluid,
                    source.replaceable(),
                    source.collisionEmpty(),
                    source.recognizedCrop(),
                    blockEntity,
                    falling,
                    unbreakable,
                    source.solidTopSupport(),
                    source.nonWaterloggable(),
                    protectedByPlayer,
                    warmEnoughToRain,
                    blockLight,
                    source.stateFingerprint());
        }
    }
}
