package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.tasks.agentic.FarmHoeAcquisitionTask;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.automaton.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Pure setup outcome and bounded-vocabulary contract checks. */
public final class FarmSetupContractSelfTest {
    private FarmSetupContractSelfTest() {
    }

    public static void runAll() {
        setupRootFreezesCommandInputs();
        coordinateLessSetupResumesRecordedFarm();
        explicitCenterRoutesToRecordedOrForcedRepair();
        exactRepairRejectionsStaySpecific();
        operatorStopProducesTerminalCancellation();
        transientResumeLifecycleIsNonTerminalUntilAbandoned();
        resourceStageDecisionsAreExact();
        failedRepairToolSnapshotIsRechecked();
        preparedWaypointInvariantCoversEveryPostRegistrationPhase();
        mutationStatusesMapExhaustively();
        dirtPlacementPostconditionIsExact();
        farmingUseDispatchIsSynchronous();
        dirtPlacementDispatchIsSynchronous();
        tillDispatchIsSynchronous();
        repairRescansBetweenMutationKinds();
        mutationReceiptSettlementIsOwnedAndExact();
        repairBreakToolAndTimingPolicyIsBounded();
        resumeMutationBudgetsStayCumulative();
        failedPhasesStayOperational();
        boundedWaterSourceScan();
        FarmTaskOutcome initial = FarmTaskOutcome.initial();
        require(!initial.terminal() && !initial.successful(), "initial state is active");
        require(initial.reason() == FarmTaskReason.NONE, "initial reason");
        require(FarmFeedback.setupModel(initial).contains("could not start"),
                "blocked-before-start model feedback is truthful");
        require(FarmFeedback.setupPlayer(initial) != null,
                "blocked-before-start player feedback is keyed");

        FarmTaskOutcome success = new FarmTaskOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                "minecraft:overworld", new BlockPos(1, 64, 2),
                3, 2, 80, true,
                new WaypointMutationResult(WaypointMutationStatus.COMMITTED, null, null));
        require(success.center().equals(new BlockPos(1, 64, 2)), "success center");
        require(FarmFeedback.setupModel(success).length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "setup model feedback cap");
        require(FarmFeedback.setupPlayer(success) != null, "setup player feedback");

        FarmTaskOutcome failure = new FarmTaskOutcome(
                true, false, FarmTaskReason.WATER_SOURCE_NOT_FOUND,
                FarmTaskPhase.ACQUIRE_RESOURCES,
                "minecraft:overworld", new BlockPos(1, 64, 2),
                0, 0, 0, false, null);
        require("water_source_not_found".equals(failure.reason().controlledReason()),
                "controlled reason token");
        require(FarmFeedback.setupModel(failure).length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "failure model feedback cap");
        require(FarmFeedback.reasonKey(FarmTaskReason.WATER_SOURCE_NOT_FOUND)
                        .endsWith("water_source_not_found"),
                "reason translation key");

        expectFailure(() -> new FarmTaskOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.TILL,
                null, null, 0, 0, 0, false, null));
        expectFailure(() -> new FarmTaskOutcome(
                true, false, FarmTaskReason.NONE, FarmTaskPhase.TILL,
                null, null, 0, 0, 0, false, null));
        expectFailure(() -> new FarmTaskOutcome(
                false, false, FarmTaskReason.DIRT_UNAVAILABLE, FarmTaskPhase.ACQUIRE_RESOURCES,
                null, null, 0, 0, 0, false, null));
        expectFailure(() -> new FarmTaskOutcome(
                false, false, FarmTaskReason.NONE, FarmTaskPhase.CLEAR,
                null, null, FarmPlotPolicy.MAX_CLEAR_ACTIONS + 1, 0, 0, false, null));

        require(FarmInteractionPostconditions.isStandaloneWaterSource(
                Blocks.WATER.defaultBlockState()), "standalone source");
        require(!FarmInteractionPostconditions.isStandaloneWaterSource(
                Blocks.BUBBLE_COLUMN.defaultBlockState()), "bubble column excluded");
        require(FarmInteractionPostconditions.waterPickupInventoryDelta(2, 1, 0, 1),
                "pickup bucket delta");
        require(FarmInteractionPostconditions.waterPickupInventoryDelta(1, 0, 4, 5),
                "pickup accepts regenerated source because inventory delta is authoritative");
        require(!FarmInteractionPostconditions.waterPickupInventoryDelta(2, 2, 0, 1),
                "pickup rejects unconsumed empty bucket");
        require(FarmInteractionPostconditions.waterPlacement(
                Blocks.WATER.defaultBlockState(), 0, 1, 1, 0),
                "placement bucket and source postcondition");
        require(!FarmInteractionPostconditions.waterPlacement(
                Blocks.WATER.defaultBlockState(), 0, 0, 1, 0),
                "placement rejects missing empty-bucket delta");
        BlockPos support = new BlockPos(4, 63, -2);
        require(PlaceFarmWaterTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false),
                        support),
                "water click gate accepts only the frozen support top face");
        require(!PlaceFarmWaterTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support), Direction.NORTH, support, false),
                        support),
                "water click gate rejects a side-face ray");
        require(!PlaceFarmWaterTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support.east()), Direction.UP,
                                support.east(), false),
                        support),
                "water click gate rejects a neighboring block ray");
        require(PlaceFarmDirtTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false),
                        support),
                "dirt click gate accepts only the frozen support top face");
        require(!PlaceFarmDirtTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support), Direction.NORTH, support, false),
                        support),
                "dirt click gate rejects a side-face ray without spending an attempt");
        require(!PlaceFarmDirtTask.isExpectedSupportTopHit(
                        new BlockHitResult(Vec3.atCenterOf(support.east()), Direction.UP,
                                support.east(), false),
                        support),
                "dirt click gate rejects a neighboring block ray without spending an attempt");
        PlaceFarmDirtTask gatedDirt = new PlaceFarmDirtTask(
                support.above(), support.north().above());
        require(gatedDirt.dispatchPlacementAttempt(
                        new BlockHitResult(Vec3.atCenterOf(support),
                                Direction.NORTH, support, false),
                        () -> {
                            throw new AssertionError("wrong ray captured a dirt baseline");
                        },
                        hit -> {
                            throw new AssertionError("wrong ray dispatched a placement");
                        }).isEmpty(),
                "wrong dirt ray is rejected before the receipt boundary");
        require(gatedDirt.attempts() == 0
                        && !gatedDirt.hasIssuedMutation()
                        && !gatedDirt.inventoryBaselineCaptured(),
                "wrong dirt ray spends no attempt and captures no inventory baseline");
        require(FarmSiteWorldView.classifyBlock(
                        Blocks.SHORT_GRASS.defaultBlockState(),
                        Blocks.SHORT_GRASS.defaultBlockState().getFluidState())
                        == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION,
                "short grass is clearable neutral ground cover");
        require(FarmSiteWorldView.classifyBlock(
                        Blocks.TALL_GRASS.defaultBlockState(),
                        Blocks.TALL_GRASS.defaultBlockState().getFluidState())
                        == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION,
                "tall grass is clearable neutral ground cover");
        for (BlockState flower : List.of(
                Blocks.DANDELION.defaultBlockState(),
                Blocks.POPPY.defaultBlockState(),
                Blocks.AZURE_BLUET.defaultBlockState(),
                Blocks.CORNFLOWER.defaultBlockState(),
                Blocks.SUNFLOWER.defaultBlockState(),
                Blocks.ROSE_BUSH.defaultBlockState())) {
            require(FarmSiteWorldView.classifyBlock(flower, flower.getFluidState())
                            == FarmSiteWorldView.BlockKind.REPLACEABLE_VEGETATION,
                    "flowers are clearable neutral ground cover even when not replaceable");
        }
        require(FarmSiteWorldView.classifyBlock(
                        Blocks.WHEAT.defaultBlockState(),
                        Blocks.WHEAT.defaultBlockState().getFluidState())
                        == FarmSiteWorldView.BlockKind.CROP,
                "registered crops are classified distinctly from disposable ground cover");
        require(FarmInteractionPostconditions.farmlandTransition(
                Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState()),
                "farmland transition");
        require(FarmSiteWorldView.stateFingerprint(
                        Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0))
                        .equals(FarmSiteWorldView.stateFingerprint(
                                Blocks.FARMLAND.defaultBlockState().setValue(
                                        FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE))),
                "farmland hydration changes are normalized out of resume manifests");
        require(!TillFarmBlockTask.ownsFreshTillTransition(0),
                "external pre-attempt till does not consume NPC durability");
        require(TillFarmBlockTask.ownsFreshTillTransition(1),
                "post-attempt till transition is owned by the NPC task");
        require(TillFarmBlockTask.needsOwnedTransitionSettlement(
                        1, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState()),
                "transition-before-damage is settled before transient child discard");
        require(!TillFarmBlockTask.needsOwnedTransitionSettlement(
                        0, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState())
                        && !TillFarmBlockTask.needsOwnedTransitionSettlement(
                        1, true,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState())
                        && !TillFarmBlockTask.needsOwnedTransitionSettlement(
                        1, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState()),
                "no-attempt, already-damaged, and unchanged till receipts are not double-settled");
        require(TillFarmBlockTask.shouldSettleImmediatelyAfterDispatch(
                        1, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState()),
                "synchronous dirt-to-farmland use settles while the exact hoe remains selected");
        require(!TillFarmBlockTask.shouldSettleImmediatelyAfterDispatch(
                        0, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState())
                        && !TillFarmBlockTask.shouldSettleImmediatelyAfterDispatch(
                        1, true,
                        Blocks.DIRT.defaultBlockState(), Blocks.FARMLAND.defaultBlockState())
                        && !TillFarmBlockTask.shouldSettleImmediatelyAfterDispatch(
                        1, false,
                        Blocks.DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState()),
                "same-tick settlement rejects unowned, already-charged, and unchanged receipts");
        BlockPos frozenActionStance = new BlockPos(4, 65, -2);
        require(FarmStanceNavigation.isAtFrozenStance(
                        new BetterBlockPos(frozenActionStance), frozenActionStance),
                "farm action arrival uses the same logical feet coordinate as pathing");
        require(!FarmStanceNavigation.isAtFrozenStance(
                        frozenActionStance.below(), frozenActionStance),
                "farmland-rounded raw block position is not mistaken for the logical stance");
        require(TillFarmBlockTask.isStoneOrBetter((HoeItem) Items.STONE_HOE)
                        && TillFarmBlockTask.isStoneOrBetter((HoeItem) Items.NETHERITE_HOE),
                "stone through netherite hoes satisfy setup policy");
        require(!TillFarmBlockTask.isStoneOrBetter((HoeItem) Items.WOODEN_HOE)
                        && !TillFarmBlockTask.isStoneOrBetter((HoeItem) Items.GOLDEN_HOE),
                "wood and gold hoes remain below setup policy");
        require(FarmHoePolicy.markedStorageItems().equals(List.of(
                        Items.STONE_HOE, Items.IRON_HOE,
                        Items.DIAMOND_HOE, Items.NETHERITE_HOE)),
                "EllieGPS hoe lookup has an exact deterministic stone-or-better item set");
        net.minecraft.world.item.ItemStack damagedStone =
                new net.minecraft.world.item.ItemStack(Items.STONE_HOE);
        damagedStone.setDamageValue(damagedStone.getMaxDamage() - 3);
        net.minecraft.world.item.ItemStack iron =
                new net.minecraft.world.item.ItemStack(Items.IRON_HOE);
        iron.setDamageValue(iron.getMaxDamage() - 5);
        require(FarmHoePolicy.remainingDurability(List.of(
                        damagedStone, iron,
                        new net.minecraft.world.item.ItemStack(Items.GOLDEN_HOE))) == 8,
                "hoe acquisition sums live usable durability and excludes gold");
        FarmHoeAcquisitionTask.Checkpoint checkpoint =
                new FarmHoeAcquisitionTask.Checkpoint();
        new FarmHoeAcquisitionTask(
                8, new BlockPos(1, 64, 2), "minecraft:overworld", checkpoint);
        require(checkpoint.isBound() && checkpoint.requiredDurability() == 8
                        && checkpoint.frozenCenter().equals(new BlockPos(1, 64, 2)),
                "root-owned hoe checkpoint freezes durability, center, and dimension");
        new FarmHoeAcquisitionTask(
                8, new BlockPos(1, 64, 2), "minecraft:overworld", checkpoint);
        expectFailure(() -> new FarmHoeAcquisitionTask(
                7, new BlockPos(1, 64, 2), "minecraft:overworld", checkpoint));
        require(FarmHoeAcquisitionTask.nextStoneHoeTarget(2) == 3,
                "craft fallback requests one more stone hoe than the live absolute count");
        require(FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_CANDIDATE == 2
                        && FarmPlotPolicy.REPAIR_TOOL_MARKED_CHEST_ATTEMPTS_PER_FAMILY >= 2,
                "repair tool lookup probes a damaged chest candidate twice within its family cap");
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        require(!FarmCropScanner.isFullyGrown(wheat, wheat.defaultBlockState()),
                "immature vanilla crop classified safely");
        require(FarmCropScanner.isFullyGrown(wheat, wheat.getStateForAge(wheat.getMaxAge())),
                "max-age vanilla crop classified as mature");
        require(FarmInteractionPostconditions.exactlyOneHoeDamage(3, 10, 4, false),
                "ordinary durability use");
        require(FarmInteractionPostconditions.exactlyOneHoeDamage(9, 10, 0, true),
                "breaking durability use");
        require(!FarmInteractionPostconditions.exactlyOneHoeDamage(3, 10, 5, false),
                "double damage rejected");
        require(FarmInteractionPostconditions.verifiedBreakTransition(
                Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), BlockState::isAir),
                "verified break accepts allowed transition");
        require(!FarmInteractionPostconditions.verifiedBreakTransition(
                Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), BlockState::isAir),
                "verified break rejects no state change");

        FarmTaskOutcome waterNoChange = new FarmTaskOutcome(
                true, false, FarmTaskReason.INTERACTION_DENIED, FarmTaskPhase.PLACE_WATER,
                "minecraft:overworld", new BlockPos(1, 64, 2),
                4, 0, 0, false, null);
        String waterModel = FarmFeedback.setupModel(waterNoChange);
        require(waterModel.contains("water placement produced no verified source or bucket-inventory change"),
                "model feedback reports the observed bucket no-change outcome");
        require(!waterModel.contains("protection") && !waterModel.contains("blocked"),
                "model feedback does not invent a protection denial");
        require(FarmFeedback.setupPlayerReasonKey(waterNoChange)
                        .equals("message.playerengine.farming.reason.water_placement_no_change"),
                "player feedback uses the water-specific no-change translation");

        FarmTaskOutcome pickupNoChange = new FarmTaskOutcome(
                true, false, FarmTaskReason.INTERACTION_DENIED,
                FarmTaskPhase.ACQUIRE_RESOURCES,
                "minecraft:overworld", new BlockPos(1, 64, 2),
                0, 0, 0, false, null, FarmReturnStatus.TIMED_OUT);
        String pickupModel = FarmFeedback.setupModel(pickupNoChange);
        require(pickupModel.contains("water pickup produced no verified water-bucket inventory change")
                        && pickupModel.contains("could not return to the selected surface site")
                        && pickupModel.length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "model receives bounded water-pickup and failed-return truthfulness");
        require(FarmFeedback.setupPlayerReasonKey(pickupNoChange)
                        .equals("message.playerengine.farming.reason.water_pickup_no_change")
                        && FarmFeedback.setupPlayer(pickupNoChange) != null,
                "player receives keyed water-pickup and failed-return truthfulness");
        FarmTaskOutcome returnOnlyFailure = new FarmTaskOutcome(
                true, false, FarmTaskReason.RESOURCE_RETURN_FAILED,
                FarmTaskPhase.ACQUIRE_RESOURCES,
                "minecraft:overworld", new BlockPos(1, 64, 2),
                0, 0, 0, false, null, FarmReturnStatus.TIMED_OUT);
        require(FarmFeedback.setupModel(returnOnlyFailure).contains(
                        "resource acquisition succeeded, but return to the selected surface site failed")
                        && !FarmFeedback.setupModel(returnOnlyFailure).contains(
                                "resource acquisition timed out"),
                "successful acquisition plus failed return keeps its own truthful reason");
    }

    private static void setupRootFreezesCommandInputs() {
        BlockPos.MutableBlockPos anchor = new BlockPos.MutableBlockPos(4, 70, 9);
        BlockPos.MutableBlockPos center = new BlockPos.MutableBlockPos(8, 64, 12);
        SetupFarmTask task = new SetupFarmTask(
                anchor, "minecraft:overworld", center, null);
        anchor.set(100, 100, 100);
        center.set(200, 200, 200);
        require(task.commandAnchor().equals(new BlockPos(4, 70, 9)),
                "command anchor defensively frozen");
        require(task.exactCenter().equals(new BlockPos(8, 64, 12)),
                "explicit center defensively frozen");
        require(task.outcome().phase() == FarmTaskPhase.PRECHECK
                        && !task.outcome().terminal(),
                "unstarted root exposes active precheck outcome");
        require(task.progress().center().equals(new BlockPos(8, 64, 12)),
                "typed progress exposes exact resume center");

        BlockPos underground = new BlockPos(0, 20, 0);
        require(SetupFarmTask.chooseSurfaceGrassAnchor(underground, List.of(
                        new BlockPos(6, 72, 0), new BlockPos(2, 68, 0)))
                        .equals(new BlockPos(0, 68, 0)),
                "underground auto setup lifts its frozen candidate Y to nearby surface grass");
        require(SetupFarmTask.chooseSurfaceGrassAnchor(underground, List.of(
                        new BlockPos(1, 68, 0),
                        new BlockPos(4, 72, 0), new BlockPos(5, 72, 0),
                        new BlockPos(4, 72, 1)))
                        .equals(new BlockPos(0, 72, 0)),
                "broad grass elevation outranks a nearer one-column ledge");
        require(SetupFarmTask.chooseSurfaceGrassAnchor(underground, List.of())
                        .equals(underground),
                "missing loaded surface grass preserves the bounded original anchor");
    }

    private static void boundedWaterSourceScan() {
        require(AcquireWaterBucketTask.SOURCE_SCAN_BLOCKS_PER_TICK == 52,
                "water source discovery uses a fixed per-tick position cap");
        require(AcquireWaterBucketTask.MAX_SCANNER_CANDIDATES_PER_WINDOW == 256,
                "known-water snapshot uses a fixed visit cap");
        require(AcquireWaterBucketTask.SOURCE_SCAN_BLOCKS_PER_TICK
                        * AcquireWaterBucketTask.MAX_WORLD_READS_PER_SCANNED_POSITION
                        <= FarmPlotPolicy.MAX_WORLD_READS_PER_TICK,
                "water source scan cap stays within the farm world-read budget");
        BlockPos center = new BlockPos(10, 64, -3);
        int scanTicks = (AcquireWaterBucketTask.localScanVolume()
                        + AcquireWaterBucketTask.MAX_SCANNER_CANDIDATES_PER_WINDOW
                        + AcquireWaterBucketTask.SOURCE_SCAN_BLOCKS_PER_TICK - 1)
                        / AcquireWaterBucketTask.SOURCE_SCAN_BLOCKS_PER_TICK;
        require(scanTicks * 2
                        + AcquireWaterBucketTask.wanderReservedTicksPerLeg()
                        + AcquireWaterBucketTask.returnToBoundReserveTicks()
                        + 2
                        < FarmPlotPolicy.WATER_SEARCH_TICKS_PER_LEG,
                "pre/post scans, wander, and return reserve fit one monotonic search leg");
        require(AcquireWaterBucketTask.postScanReserveTicks()
                        == scanTicks + AcquireWaterBucketTask.returnToBoundReserveTicks() + 2,
                "wander is detached early enough to reserve the final discovery scan");
        require(AcquireWaterBucketTask.latestReservedPostScanWorkTick()
                        < FarmPlotPolicy.WATER_SEARCH_TICKS_PER_LEG,
                "exact detach/return/scan boundary finishes before the hard deadline");
        require(AcquireWaterBucketTask.windowCompletion(false, false)
                        == AcquireWaterBucketTask.SearchWindowCompletion.START_WANDER,
                "initial scan starts a bounded wander");
        require(AcquireWaterBucketTask.windowCompletion(true, true)
                        == AcquireWaterBucketTask.SearchWindowCompletion.ADVANCE_LEG,
                "leg advances only after the post-wander discovery scan");
        require(AcquireWaterBucketTask.boundedWanderDistance(
                        center, center, 64.0F, FarmPlotPolicy.WATER_SEARCH_MAX_DISTANCE) == 64.0F,
                "near-anchor wander keeps its requested bounded range");
        require(AcquireWaterBucketTask.boundedWanderDistance(
                        center, center.offset(94, 0, 0), 64.0F,
                        FarmPlotPolicy.WATER_SEARCH_MAX_DISTANCE) == 0.0F,
                "near-boundary leg skips wander so its post-scan cannot start outside the bound");
        BlockPos frozen = AcquireWaterBucketTask.freezeSourceScanCenter(null, center);
        require(AcquireWaterBucketTask.freezeSourceScanCenter(
                        frozen, center.offset(4, 0, 4)).equals(center),
                "movement cannot restart an active local water scan window");
        require(AcquireWaterBucketTask.localScanPosition(center, 0).equals(
                        center.offset(-AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS,
                                0,
                                -AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS)),
                "local water scan starts on the ideal surface-height plane");
        int plane = (AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS * 2 + 1)
                * (AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS * 2 + 1);
        require(AcquireWaterBucketTask.localScanPosition(center, plane).getY()
                        == center.getY() - 1
                        && AcquireWaterBucketTask.localScanPosition(center, plane * 2).getY()
                        == center.getY() + 1,
                "local water scan expands vertically center-out instead of cave-first");
        require(AcquireWaterBucketTask.localScanPosition(
                        center, AcquireWaterBucketTask.localScanVolume() - 1).equals(
                        center.offset(AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS,
                                AcquireWaterBucketTask.LOCAL_SCAN_VERTICAL_RADIUS,
                                AcquireWaterBucketTask.LOCAL_SCAN_HORIZONTAL_RADIUS)),
                "local water scan covers the frozen upper corner exactly once");
        require(AcquireWaterBucketTask.surfaceScanCenter(center.below(20), center.getY() + 1)
                        .equals(center),
                "local scans are lifted to the live surface instead of following cave Y");
        require(AcquireWaterBucketTask.isExposedSurfaceSource(64, 65)
                        && !AcquireWaterBucketTask.isExposedSurfaceSource(55, 65),
                "only water exposed at the live terrain/fluid surface is eligible");
        require(AcquireWaterBucketTask.isAtLiveSurface(65, 65)
                        && AcquireWaterBucketTask.isAtLiveSurface(64, 65)
                        && !AcquireWaterBucketTask.isAtLiveSurface(55, 65),
                "surface-return gate accepts shore/swimming feet but rejects cave travel");
        require(AcquireWaterBucketTask.advanceInteractionTicks(7, false) == 7
                        && AcquireWaterBucketTask.advanceInteractionTicks(7, true) == 8,
                "water interaction timeout starts only at the frozen stance");
        java.util.ArrayList<BlockPos> priority = new java.util.ArrayList<>(List.of(
                center.offset(1, -12, 0), center.offset(20, 0, 0)));
        priority.sort(AcquireWaterBucketTask.sourcePriorityComparator(center));
        require(priority.get(0).equals(center.offset(20, 0, 0)),
                "surface-level water outranks a closer cave source");
        expectFailure(() -> AcquireWaterBucketTask.localScanPosition(
                center, AcquireWaterBucketTask.localScanVolume()));
    }

    private static void operatorStopProducesTerminalCancellation() {
        SetupFarmTask task = new SetupFarmTask(
                new BlockPos(0, 64, 0), "minecraft:overworld", null, null);
        task.onStop(null);
        require(task.isTerminal() && !task.isSuccessful(),
                "operator stop terminalizes an unstarted setup root");
        require(task.outcome().reason() == FarmTaskReason.CANCELLED_OPERATOR,
                "operator stop retains controlled cancellation reason");
        task.onStop(null);
        require(task.outcome().reason() == FarmTaskReason.CANCELLED_OPERATOR,
                "repeated stop preserves the first terminal reason");
    }

    private static void transientResumeLifecycleIsNonTerminalUntilAbandoned() {
        BlockPos center = new BlockPos(6, 64, -4);
        SetupFarmTask resumable = new SetupFarmTask(
                new BlockPos(0, 64, 0), "minecraft:overworld", center, null);
        require(resumable.prepareForTransientResume(
                        com.player2.playerengine.tasks.base.TaskSuspensionCause.GESTURE_OVERLAY),
                "setup root explicitly accepts a gesture checkpoint");
        require(!resumable.isTerminal() && resumable.progress().center().equals(center),
                "checkpoint preserves the exact center without recording cancellation");
        resumable.onStart();
        require(!resumable.isTerminal() && resumable.progress().center().equals(center),
                "restart remains nonterminal and pinned to the same center");

        SetupFarmTask abandoned = new SetupFarmTask(
                new BlockPos(0, 64, 0), "minecraft:overworld", center, null);
        require(abandoned.prepareForTransientResume(
                        com.player2.playerengine.tasks.base.TaskSuspensionCause.HIGHER_PRIORITY_CHAIN),
                "setup root explicitly accepts a priority checkpoint");
        abandoned.onTransientResumeAbandoned();
        require(abandoned.isTerminal()
                        && abandoned.outcome().reason() == FarmTaskReason.CANCELLED_OPERATOR,
                "discarded checkpoint becomes a controlled terminal cancellation");
    }

    private static void resourceStageDecisionsAreExact() {
        require(SetupFarmTask.initialResourceStage(true)
                        == SetupFarmTask.ResourceStage.ENSURE_BUCKET,
                "new-water plot starts with bucket acquisition");
        require(SetupFarmTask.initialResourceStage(false)
                        == SetupFarmTask.ResourceStage.ENSURE_HOE,
                "existing-source resume bypasses all bucket work");
        require(SetupFarmTask.nextSatisfiedResourceStage(
                        SetupFarmTask.ResourceStage.ACQUIRE_WATER)
                        == SetupFarmTask.ResourceStage.ENSURE_HOE,
                "water acquisition continues directly to hoe acquisition before returning");
        require(SetupFarmTask.nextSatisfiedResourceStage(
                        SetupFarmTask.ResourceStage.ENSURE_HOE)
                        == SetupFarmTask.ResourceStage.COMPLETE,
                "ready hoe completes resources without speculative dirt stockpiling");
        require(SetupFarmTask.nextSatisfiedResourceStage(
                        SetupFarmTask.ResourceStage.REACQUIRE_HOE)
                        == SetupFarmTask.ResourceStage.COMPLETE,
                "replacement hoe returns directly to bounded farm repair");
        require(SetupFarmTask.nextSatisfiedResourceStage(
                        SetupFarmTask.ResourceStage.ENSURE_REPAIR_TOOLS)
                        == SetupFarmTask.ResourceStage.COMPLETE,
                "repair-tool acquisition completes its bounded preflight stage");
        require(SetupFarmTask.nextSatisfiedResourceStage(
                        SetupFarmTask.ResourceStage.ENSURE_REPAIR_DIRT)
                        == SetupFarmTask.ResourceStage.COMPLETE,
                "repair dirt is driven only by the exact task-local repair demand");

        assertResourceStop(SetupFarmTask.ResourceStage.ENSURE_BUCKET,
                FarmTaskReason.BUCKET_UNAVAILABLE);
        assertResourceStop(SetupFarmTask.ResourceStage.ACQUIRE_WATER,
                FarmTaskReason.WATER_SOURCE_NOT_FOUND);
        assertResourceStop(SetupFarmTask.ResourceStage.ENSURE_HOE,
                FarmTaskReason.HOE_UNAVAILABLE);
        assertResourceStop(SetupFarmTask.ResourceStage.ENSURE_BUILD_DIRT,
                FarmTaskReason.DIRT_UNAVAILABLE);
        assertResourceStop(SetupFarmTask.ResourceStage.ENSURE_REPAIR_TOOLS,
                FarmTaskReason.REPAIR_TOOL_UNAVAILABLE);
        assertResourceStop(SetupFarmTask.ResourceStage.ENSURE_REPAIR_DIRT,
                FarmTaskReason.DIRT_UNAVAILABLE);
        assertResourceStop(SetupFarmTask.ResourceStage.REACQUIRE_HOE,
                FarmTaskReason.HOE_REACQUIRE_FAILED);

        require(SetupFarmTask.resourceTerminalReason(
                        SetupFarmTask.ResourceStage.ENSURE_REPAIR_DIRT,
                        false, false, FarmPlotPolicy.RESOURCE_PHASE_TICKS)
                        == FarmTaskReason.RESOURCE_TIMEOUT,
                "active resource acquisition uses timeout reason");
        require(SetupFarmTask.resourceTerminalReason(
                        SetupFarmTask.ResourceStage.ENSURE_REPAIR_DIRT,
                        true, true, FarmPlotPolicy.RESOURCE_PHASE_TICKS)
                        == FarmTaskReason.NONE,
                "satisfied resource requirement wins over timeout/child stop");
    }

    private static void preparedWaypointInvariantCoversEveryPostRegistrationPhase() {
        for (FarmTaskPhase phase : FarmTaskPhase.values()) {
            boolean expected = phase == FarmTaskPhase.REPAIR
                    || phase == FarmTaskPhase.PLACE_WATER
                    || phase == FarmTaskPhase.TILL
                    || phase == FarmTaskPhase.VERIFY
                    || phase == FarmTaskPhase.COMMIT;
            require(SetupFarmTask.requiresPreparedWaypoint(phase) == expected,
                    "prepared waypoint invariant mapping for " + phase);
        }
    }

    private static void assertResourceStop(
            SetupFarmTask.ResourceStage stage,
            FarmTaskReason expected) {
        require(SetupFarmTask.resourceTerminalReason(stage, true, false, 1) == expected,
                stage + " stopped-child reason");
    }

    private static void mutationStatusesMapExhaustively() {
        for (WaypointMutationStatus status : WaypointMutationStatus.values()) {
            FarmTaskReason mapped = SetupFarmTask.failureForMutation(status);
            switch (status) {
                case COMMITTED, COMMITTED_INDEX_DEGRADED,
                        NO_CHANGE, NO_CHANGE_INDEX_DEGRADED ->
                        require(mapped == FarmTaskReason.NONE, status + " setup success mapping");
                case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT ->
                        require(mapped == FarmTaskReason.TYPE_CONFLICT, status + " conflict mapping");
                case FAILED_STORE_UNAVAILABLE ->
                        require(mapped == FarmTaskReason.STORE_UNAVAILABLE,
                                "store-unavailable mapping");
                case FAILED_JSON_COMMIT ->
                        require(mapped == FarmTaskReason.WAYPOINT_JSON_FAILURE,
                                "JSON failure mapping");
                case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED ->
                        require(mapped == FarmTaskReason.INTERNAL_CONTRACT_FAILURE,
                                status + " impossible setup mapping");
            }
        }
    }

    private static void dirtPlacementPostconditionIsExact() {
        require(PlaceFarmDirtTask.placementCompleted(
                        Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 2),
                "dirt placement exact state and inventory delta");
        require(!PlaceFarmDirtTask.placementCompleted(
                        Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 3),
                "dirt placement rejects missing material delta");
        require(!PlaceFarmDirtTask.placementCompleted(
                        Blocks.AIR.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(), 3, 2),
                "dirt placement rejects wrong target block");
        require(!PlaceFarmDirtTask.placementCompleted(
                        Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 2),
                "dirt placement requires frozen air target");
    }

    private static void farmingUseDispatchIsSynchronous() {
        BlockPos target = new BlockPos(9, 71, -4);
        ItemStack[] selectedHand = {new ItemStack(Items.WATER_BUCKET)};
        int[] beforeDispatch = {0};
        int[] blockUses = {0};
        int[] itemUses = {0};

        var dispatched = FarmInteractionDispatcher.dispatchExactFace(
                new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false),
                target,
                Direction.UP,
                () -> {
                    require(selectedHand[0].is(Items.WATER_BUCKET),
                            "receipt baseline runs while the requested farming item is selected");
                    beforeDispatch[0]++;
                },
                hit -> FarmInteractionDispatcher.blockThenItemUse(
                        hit,
                        exactHit -> {
                            require(selectedHand[0].is(Items.WATER_BUCKET),
                                    "block use runs before later inventory maintenance");
                            blockUses[0]++;
                            return InteractionResult.PASS;
                        },
                        () -> !selectedHand[0].isEmpty(),
                        () -> {
                            require(selectedHand[0].is(Items.WATER_BUCKET),
                                    "bucket item fallback remains in the synchronous boundary");
                            itemUses[0]++;
                            return InteractionResult.CONSUME;
                        }));

        // Simulate InventoryBehavior restoring a pickaxe only after the task's inline use returns.
        selectedHand[0] = new ItemStack(Items.STONE_PICKAXE);
        require(dispatched.isPresent() && dispatched.get() == InteractionResult.CONSUME,
                "synchronous dispatcher preserves the consuming item-fallback result");
        require(beforeDispatch[0] == 1 && blockUses[0] == 1 && itemUses[0] == 1,
                "one exact dispatch owns one baseline, block use, and required item fallback");

        int[] suppressedFallbacks = {0};
        InteractionResult blockConsumed = FarmInteractionDispatcher.blockThenItemUse(
                new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false),
                hit -> InteractionResult.CONSUME,
                () -> true,
                () -> {
                    suppressedFallbacks[0]++;
                    return InteractionResult.PASS;
                });
        require(blockConsumed == InteractionResult.CONSUME && suppressedFallbacks[0] == 0,
                "consuming block use suppresses the item fallback exactly like BlockPlaceHelper");

        require(FarmInteractionDispatcher.dispatchExactFace(
                        new BlockHitResult(Vec3.atCenterOf(target), Direction.NORTH, target, false),
                        target,
                        Direction.UP,
                        () -> {
                            throw new AssertionError("wrong ray captured a farming receipt baseline");
                        },
                        hit -> {
                            throw new AssertionError("wrong ray dispatched a farming interaction");
                        }).isEmpty(),
                "wrong face is rejected before any farming receipt or interaction");
        require(FarmInteractionDispatcher.dispatchExactBlock(
                        new BlockHitResult(Vec3.atCenterOf(target), Direction.NORTH, target, false),
                        target,
                        () -> {
                        },
                        hit -> InteractionResult.CONSUME).isPresent(),
                "water pickup accepts any face only when the live ray hits the frozen source");
    }

    private static void dirtPlacementDispatchIsSynchronous() {
        BlockPos support = new BlockPos(9, 70, -1);
        PlaceFarmDirtTask task = new PlaceFarmDirtTask(
                support.above(), support.north().above());
        ItemStack[] selectedHand = {new ItemStack(Items.DIRT, 6)};
        int[] dispatches = {0};

        var result = task.dispatchPlacementAttempt(
                new BlockHitResult(Vec3.atCenterOf(support),
                        Direction.UP, support, false),
                () -> {
                    require(selectedHand[0].is(Items.DIRT),
                            "dirt baseline is captured while dirt remains selected");
                    return selectedHand[0].getCount();
                },
                hit -> {
                    require(selectedHand[0].is(Items.DIRT),
                            "placement dispatch happens before later inventory behavior");
                    require(hit.getBlockPos().equals(support)
                                    && hit.getDirection() == Direction.UP,
                            "synchronous placement keeps the exact support-top hit");
                    dispatches[0]++;
                    return InteractionResult.CONSUME;
                });

        // Simulate InventoryBehavior restoring a pickaxe later in the same controller tick.
        selectedHand[0] = new ItemStack(Items.STONE_PICKAXE);
        require(result.isPresent() && result.get() == InteractionResult.CONSUME,
                "synchronous placement preserves the interaction result");
        require(dispatches[0] == 1
                        && task.attempts() == 1
                        && task.hasIssuedMutation()
                        && task.inventoryBaselineCaptured(),
                "one exact synchronous dispatch owns one receipt attempt");

        PlaceFarmDirtTask deniedTask = new PlaceFarmDirtTask(
                support.above(), support.north().above());
        var denied = deniedTask.dispatchPlacementAttempt(
                new BlockHitResult(Vec3.atCenterOf(support),
                        Direction.UP, support, false),
                () -> 6,
                hit -> InteractionResult.FAIL);
        require(denied.isPresent() && denied.get() == InteractionResult.FAIL
                        && deniedTask.attempts() == 1
                        && deniedTask.hasIssuedMutation(),
                "a dispatched non-consuming interaction still owns exactly one retry attempt");
    }

    private static void tillDispatchIsSynchronous() {
        BlockPos target = new BlockPos(9, 71, -4);
        TillFarmBlockTask task = new TillFarmBlockTask(target, target.north().above());
        ItemStack[] selectedHand = {new ItemStack(Items.STONE_HOE)};
        int[] dispatches = {0};

        require(task.dispatchTillAttempt(
                        new BlockHitResult(Vec3.atCenterOf(target),
                                Direction.NORTH, target, false),
                        () -> {
                            throw new AssertionError("wrong till ray captured damage");
                        },
                        () -> {
                            throw new AssertionError("wrong till ray captured maximum damage");
                        },
                        hit -> {
                            throw new AssertionError("wrong till ray dispatched hoe use");
                        }).isEmpty(),
                "wrong till ray is rejected before the receipt boundary");
        require(task.attempts() == 0 && !task.hasIssuedMutation(),
                "wrong till ray spends no mutation attempt");

        var result = task.dispatchTillAttempt(
                new BlockHitResult(Vec3.atCenterOf(target),
                        Direction.UP, target, false),
                () -> {
                    require(selectedHand[0].is(Items.STONE_HOE),
                            "till damage baseline is captured while the hoe remains selected");
                    return selectedHand[0].getDamageValue();
                },
                () -> selectedHand[0].getMaxDamage(),
                hit -> {
                    require(selectedHand[0].is(Items.STONE_HOE),
                            "hoe use dispatch happens before later inventory behavior");
                    require(hit.getBlockPos().equals(target)
                                    && hit.getDirection() == Direction.UP,
                            "synchronous till keeps the exact target-top hit");
                    dispatches[0]++;
                    return InteractionResult.CONSUME;
                });
        selectedHand[0] = new ItemStack(Items.STONE_PICKAXE);
        require(result.isPresent() && result.get() == InteractionResult.CONSUME,
                "synchronous till preserves the interaction result");
        require(dispatches[0] == 1 && task.attempts() == 1 && task.hasIssuedMutation(),
                "one exact synchronous hoe use owns one receipt attempt");

        TillFarmBlockTask deniedTask = new TillFarmBlockTask(
                target, target.north().above());
        var denied = deniedTask.dispatchTillAttempt(
                new BlockHitResult(Vec3.atCenterOf(target),
                        Direction.UP, target, false),
                () -> 0,
                () -> new ItemStack(Items.STONE_HOE).getMaxDamage(),
                hit -> InteractionResult.FAIL);
        require(denied.isPresent() && denied.get() == InteractionResult.FAIL
                        && deniedTask.attempts() == 1
                        && deniedTask.hasIssuedMutation(),
                "one denied synchronous hoe use owns exactly one retry attempt");
    }

    private static void repairRescansBetweenMutationKinds() {
        SetupFarmTask.RepairPassTransition completed =
                SetupFarmTask.repairPassTransition(2, 2, 4);
        require(completed.action() == SetupFarmTask.RepairPassAction.RESCAN_BEFORE_FILL
                        && completed.nextBreakIndex() == 0
                        && completed.nextFillIndex() == 0,
                "completed two-block clear pass discards its fill cursor for a live rescan");
        SetupFarmTask.RepairPassTransition unfinished =
                SetupFarmTask.repairPassTransition(2, 1, 4);
        require(unfinished.action() == SetupFarmTask.RepairPassAction.CONTINUE_BREAKS
                        && unfinished.nextBreakIndex() == 1
                        && unfinished.nextFillIndex() == 4,
                "unfinished clear pass preserves both cursors and continues breaking");
        SetupFarmTask.RepairPassTransition fillOnly =
                SetupFarmTask.repairPassTransition(0, 0, 1);
        require(fillOnly.action() == SetupFarmTask.RepairPassAction.CONTINUE_FILLS
                        && fillOnly.nextBreakIndex() == 0
                        && fillOnly.nextFillIndex() == 1,
                "pre-existing air gap preserves its live fill cursor without a rescan loop");
        expectFailure(() -> SetupFarmTask.repairPassTransition(1, 2, 0));
    }

    private static void failedRepairToolSnapshotIsRechecked() {
        require(!SetupFarmTask.repairToolFailureSnapshotIsStale(
                        "frozen", "frozen", false),
                "an unchanged still-missing repair tool preserves the acquisition failure");
        require(SetupFarmTask.repairToolFailureSnapshotIsStale(
                        "frozen", "changed", false),
                "a changed live repair manifest resumes through a fresh repair scan");
        require(SetupFarmTask.repairToolFailureSnapshotIsStale(
                        "frozen", "frozen", true),
                "a tool obtained during return makes the frozen acquisition failure stale");
    }

    private static void mutationReceiptSettlementIsOwnedAndExact() {
        require(VerifiedBreakBlockTask.observedTransition(
                        Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                        BlockState::isAir, 1)
                        == VerifiedBreakBlockTask.ObservedTransition.SUCCESS,
                "issued exact break receipt is successful");
        require(VerifiedBreakBlockTask.observedTransition(
                        Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                        BlockState::isAir, 0)
                        == VerifiedBreakBlockTask.ObservedTransition.PRE_ATTEMPT_DRIFT,
                "break transition without an issued attempt is not owned");
        require(VerifiedBreakBlockTask.observedTransition(
                        Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                        BlockState::isAir, 1)
                        == VerifiedBreakBlockTask.ObservedTransition.INVALID_DRIFT,
                "issued break with the wrong resulting block is invalid");

        require(PlaceFarmDirtTask.receiptDisposition(
                        0, false,
                        Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 2)
                        == PlaceFarmDirtTask.ReceiptDisposition.PENDING,
                "unissued dirt transition is left for live-state reconstruction");
        require(PlaceFarmDirtTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 2)
                        == PlaceFarmDirtTask.ReceiptDisposition.SUCCESS,
                "issued exact dirt world and inventory receipt is successful");
        require(PlaceFarmDirtTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), 3, 3)
                        == PlaceFarmDirtTask.ReceiptDisposition.PENDING,
                "unchanged issued dirt attempt remains pending");
        require(PlaceFarmDirtTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 3, 3)
                        == PlaceFarmDirtTask.ReceiptDisposition.INVALID,
                "partial dirt receipt is invalid");

        require(PlaceFarmWaterTask.receiptDisposition(
                        0, false,
                        Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                        0, 1, 1, 0)
                        == PlaceFarmWaterTask.ReceiptDisposition.PENDING,
                "unissued water transition is left for live-state reconstruction");
        require(PlaceFarmWaterTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                        0, 1, 1, 0)
                        == PlaceFarmWaterTask.ReceiptDisposition.SUCCESS,
                "issued exact water world and inventory receipt is successful");
        require(PlaceFarmWaterTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                        0, 0, 1, 1)
                        == PlaceFarmWaterTask.ReceiptDisposition.PENDING,
                "unchanged issued water attempt remains pending");
        require(PlaceFarmWaterTask.receiptDisposition(
                        1, true,
                        Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                        0, 0, 1, 0)
                        == PlaceFarmWaterTask.ReceiptDisposition.INVALID,
                "partial water receipt is invalid");
    }

    private static void repairBreakToolAndTimingPolicyIsBounded() {
        require(VerifiedBreakBlockTask.safeNoToolRepairHand(ItemStack.EMPTY),
                "empty hand is safe for tool-free repair clearing");
        require(VerifiedBreakBlockTask.safeNoToolRepairHand(new ItemStack(Items.DIRT)),
                "non-damageable material is safe for tool-free repair clearing");
        require(!VerifiedBreakBlockTask.safeNoToolRepairHand(new ItemStack(Items.STONE_HOE)),
                "reserved damageable hoe is never selected for tool-free clearing");
        require(VerifiedBreakBlockTask.safeNoToolRepairHand(
                        new ItemStack(Items.STONE_AXE)),
                "a non-hoe damageable fallback keeps a tool-free clear from failing");
        int obsidianLike = VerifiedBreakBlockTask.continuousClickBudget(8.0F / 50.0F / 30.0F);
        require(obsidianLike > 188
                        && obsidianLike < FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS,
                "slow valid tools receive one continuous bounded click window");
        require(VerifiedBreakBlockTask.continuousClickBudget(0.0F)
                        == FarmPlotPolicy.MUTATION_NO_PROGRESS_TICKS,
                "non-progressing blocks remain capped by the fixed mutation ceiling");
        require(VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        true, true, VerifiedBreakBlockTask.ObservedTransition.SUCCESS),
                "an owned protected repair break retires its exact protection marker");
        require(!VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        false, true, VerifiedBreakBlockTask.ObservedTransition.SUCCESS)
                        && !VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        true, false, VerifiedBreakBlockTask.ObservedTransition.SUCCESS)
                        && !VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        true, true, VerifiedBreakBlockTask.ObservedTransition.UNCHANGED)
                        && !VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        true, true, VerifiedBreakBlockTask.ObservedTransition.PRE_ATTEMPT_DRIFT)
                        && !VerifiedBreakBlockTask.shouldRetireTargetProtection(
                        true, true, VerifiedBreakBlockTask.ObservedTransition.INVALID_DRIFT),
                "ordinary breaks, absent markers, and unowned transitions never prune protection");
        require(FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.STONE.defaultBlockState(), true, false, true, 1.5F, true)
                        && FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.COBBLESTONE.defaultBlockState(), true, false, true, 2.0F, true),
                "stone supports remain valid for setup clearing and perimeter harvesting");
        require(!FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.SAND.defaultBlockState(), true, false, true, 0.5F, true)
                        && !FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.CHEST.defaultBlockState(), true, false, true, 2.5F, false),
                "falling and block-entity supports remain unsafe traversal anchors");
        require(FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.DIRT_PATH.defaultBlockState(), false, false, true, 0.65F, false)
                        && !FarmStanceNavigation.supportGeometryAllowed(
                        Blocks.DIRT_PATH.defaultBlockState(), false, false, true, 0.65F, true),
                "dirt-path perimeter remains harvestable without weakening setup planner support");
        VerifiedBreakBlockTask harvestBreak = VerifiedBreakBlockTask.forMatureCrop(
                BlockPos.ZERO, BlockPos.ZERO.above());
        VerifiedBreakBlockTask setupBreak = new VerifiedBreakBlockTask(
                BlockPos.ZERO, BlockPos.ZERO.above());
        require(!harvestBreak.requiresUnprotectedStanceSupport()
                        && !harvestBreak.requiresPlannerSafeStanceSupport()
                        && setupBreak.requiresUnprotectedStanceSupport()
                        && setupBreak.requiresPlannerSafeStanceSupport(),
                "harvest can stand on a protected perimeter while setup mutation support stays strict");
        require(TillFarmBlockTask.tillProtectionDenied(
                        false, true, false, false, false, false),
                "a stale protected headroom marker would immediately deny the next till");
        require(!TillFarmBlockTask.tillProtectionDenied(
                        false, false, false, false, false, false),
                "a clean till footprint passes the exact protection gate");
        require(!TillFarmBlockTask.tillProtectionDenied(
                        false, false, false, true, true, false),
                "every till child shares the protected passable-crop stance exception");
        require(!FarmStanceNavigation.stanceProtectionDenied(false, true, true, false),
                "standing in a protected collision-empty crop does not mutate that crop");
        require(FarmStanceNavigation.stanceProtectionDenied(true, false, false, false)
                        && FarmStanceNavigation.stanceProtectionDenied(
                        false, true, false, false)
                        && FarmStanceNavigation.stanceProtectionDenied(
                        false, false, true, true),
                "protected support, non-crop feet, and head cells still deny a stance");
        BlockState wheatAgeZero = Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, 0);
        BlockState wheatAgeOne = wheatAgeZero.setValue(CropBlock.AGE, 1);
        require(PlaceFarmDirtTask.stanceStateStillValid(
                        wheatAgeZero, wheatAgeOne, true, true),
                "a same-crop age tick cannot invalidate dirt-placement navigation");
        require(!PlaceFarmDirtTask.stanceStateStillValid(
                        wheatAgeZero, Blocks.CARROTS.defaultBlockState(), true, true)
                        && !PlaceFarmDirtTask.stanceStateStillValid(
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.GRASS_BLOCK.defaultBlockState(),
                        false,
                        false),
                "crop replacement and ordinary stance drift remain exact failures");
    }

    private static void coordinateLessSetupResumesRecordedFarm() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        String dimension = "minecraft:overworld";
        BlockPos unloadedCenter = new BlockPos(2, 64, 0);
        BlockPos loadedCenter = new BlockPos(6, 64, 0);

        require(SetupFarmTask.shouldResolveRecordedResume(null, null),
                "only a fresh coordinate-less setup searches durable unfinished farms");
        require(!SetupFarmTask.shouldResolveRecordedResume(loadedCenter, null)
                        && !SetupFarmTask.shouldResolveRecordedResume(null, loadedCenter),
                "explicit and transient resumes never get redirected by stale search");

        WaypointRecord unloaded = farmWaypoint(
                dimension, unloadedCenter, true, FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord loaded = farmWaypoint(
                dimension, loadedCenter, true, FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord fresh = farmWaypoint(
                dimension, new BlockPos(1, 64, 0), false, FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord wrongDimension = farmWaypoint(
                "minecraft:the_nether", new BlockPos(1, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord wrongRadius = farmWaypoint(
                dimension, new BlockPos(3, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS - 1);
        WaypointRecord outOfRange = farmWaypoint(
                dimension, new BlockPos(FarmPlotPolicy.AUTO_SEARCH_RADIUS + 1, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord wrongType = farmWaypoint(
                dimension, new BlockPos(4, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        wrongType.type = WaypointTypes.INVENTORY;
        WaypointRecord missingData = farmWaypoint(
                dimension, new BlockPos(5, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        missingData.data = null;
        WaypointRecord futureEnvelope = farmWaypoint(
                dimension, new BlockPos(7, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        futureEnvelope.schemaVersion = WaypointRecord.WAYPOINT_SCHEMA_VERSION + 1;
        WaypointRecord malformedPosition = farmWaypoint(
                dimension, new BlockPos(8, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        malformedPosition.pos = new int[]{8, 64, 0, 99};
        WaypointRecord nonCanonicalId = farmWaypoint(
                dimension, new BlockPos(9, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        nonCanonicalId.id = "legacy-noncanonical-id";

        List<WaypointRecord> mixed = List.of(
                fresh,
                wrongDimension,
                wrongRadius,
                outOfRange,
                wrongType,
                missingData,
                futureEnvelope,
                malformedPosition,
                nonCanonicalId,
                unloaded,
                loaded);
        SetupFarmTask.RecordedResumeResolution selected =
                SetupFarmTask.chooseRecordedResume(
                        mixed,
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> !center.equals(unloadedCenter));
        require(selected.status() == SetupFarmTask.RecordedResumeStatus.SELECTED
                        && loaded.id.equals(selected.id())
                        && loadedCenter.equals(selected.center()),
                "the nearest fully loaded compatible stale farm is selected");

        SetupFarmTask.RecordedResumeResolution selectedFromPermutation =
                SetupFarmTask.chooseRecordedResume(
                        java.util.Arrays.asList(
                                loaded, unloaded, null, nonCanonicalId, malformedPosition,
                                futureEnvelope, missingData, wrongType, outOfRange,
                                wrongRadius, wrongDimension, fresh),
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> !center.equals(unloadedCenter));
        require(selected.id().equals(selectedFromPermutation.id()),
                "recorded farm selection is independent of store iteration order");

        SetupFarmTask.RecordedResumeResolution allUnloaded =
                SetupFarmTask.chooseRecordedResume(
                        List.of(unloaded),
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> false);
        require(allUnloaded.status() == SetupFarmTask.RecordedResumeStatus.UNLOADED,
                "an eligible unloaded farm blocks duplicate automatic creation");
        require(SetupFarmTask.chooseRecordedResume(
                        List.of(fresh, wrongDimension, wrongRadius, outOfRange, wrongType,
                                missingData, futureEnvelope, malformedPosition, nonCanonicalId),
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> true).status() == SetupFarmTask.RecordedResumeStatus.NONE,
                "automatic site selection remains the fallback when no compatible stale farm exists");

        WaypointRecord cavern = farmWaypoint(
                dimension, new BlockPos(0, -50, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord nearbySurface = farmWaypoint(
                dimension, new BlockPos(8, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        require(SetupFarmTask.chooseRecordedResume(
                        List.of(cavern, nearbySurface),
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> true).id().equals(nearbySurface.id),
                "three-dimensional ranking prevents a same-column cave farm outranking the surface");

        WaypointRecord east = farmWaypoint(
                dimension, new BlockPos(4, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord west = farmWaypoint(
                dimension, new BlockPos(-4, 64, 0), true,
                FarmPlotPolicy.HYDRATION_RADIUS);
        require(SetupFarmTask.chooseRecordedResume(
                        List.of(east, west),
                        dimension,
                        anchor,
                        FarmPlotPolicy.AUTO_SEARCH_RADIUS,
                        center -> true).id().equals(west.id),
                "equal-distance recorded farms use stable canonical-id ordering");

        require(SetupFarmTask.recordedResumeSearchFailure(WaypointSearchStatus.INDEXED)
                        == FarmTaskReason.NONE
                        && SetupFarmTask.recordedResumeSearchFailure(
                        WaypointSearchStatus.AUTHORITATIVE_SPATIAL) == FarmTaskReason.NONE
                        && SetupFarmTask.recordedResumeSearchFailure(
                        WaypointSearchStatus.FULL_STORE_FALLBACK) == FarmTaskReason.NONE,
                "every successful waypoint search provenance can feed recorded resume");
        require(SetupFarmTask.recordedResumeSearchFailure(
                        WaypointSearchStatus.FAILED_STORE_UNAVAILABLE)
                        == FarmTaskReason.STORE_UNAVAILABLE
                        && SetupFarmTask.recordedResumeSearchFailure(
                        WaypointSearchStatus.FAILED_SCAN_LIMIT)
                        == FarmTaskReason.SEARCH_SCAN_LIMIT
                        && SetupFarmTask.recordedResumeSearchFailure(
                        WaypointSearchStatus.FAILED_SEARCH_ERROR)
                        == FarmTaskReason.INTERNAL_CONTRACT_FAILURE,
                "recorded resume maps every waypoint search failure to bounded task feedback");

        WaypointRecord sameIdentity = farmWaypoint(
                dimension, loadedCenter, true, FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord differentIdentity = farmWaypoint(
                dimension, loadedCenter.east(), true, FarmPlotPolicy.HYDRATION_RADIUS);
        require(SetupFarmTask.sameWaypointIdentity(loaded, sameIdentity),
                "recorded resume reconciles the exact ID with the position occupant");
        require(!SetupFarmTask.sameWaypointIdentity(loaded, differentIdentity)
                        && !SetupFarmTask.sameWaypointIdentity(loaded, null),
                "a different or missing position occupant blocks pre-mutation resume");

        FarmSiteWorldView.WorldObservation safeWorld =
                new FarmSiteWorldView.WorldObservation(false, true, true, false);
        require(SetupFarmTask.recordedResumeSafetyFailure(safeWorld, false)
                        == FarmTaskReason.NONE,
                "a safe recorded farm may enter repair");
        require(SetupFarmTask.recordedResumeSafetyFailure(
                        new FarmSiteWorldView.WorldObservation(false, false, true, false), false)
                        == FarmTaskReason.STORE_UNAVAILABLE
                        && SetupFarmTask.recordedResumeSafetyFailure(
                        new FarmSiteWorldView.WorldObservation(false, true, false, false), false)
                        == FarmTaskReason.STORE_UNAVAILABLE,
                "recorded repair requires both authoritative safety stores");
        require(SetupFarmTask.recordedResumeSafetyFailure(
                        new FarmSiteWorldView.WorldObservation(false, true, true, true), false)
                        == FarmTaskReason.TYPE_CONFLICT,
                "a conflicting position occupant is rejected before repair");
        require(SetupFarmTask.recordedResumeSafetyFailure(
                        new FarmSiteWorldView.WorldObservation(true, true, true, false), false)
                        == FarmTaskReason.WATER_EVAPORATES
                        && SetupFarmTask.waterEnvironmentFailure(
                        new FarmSiteWorldView.WorldObservation(true, true, true, false))
                        == FarmTaskReason.WATER_EVAPORATES,
                "every setup mode reports the ultra-warm water gate directly");
        require(SetupFarmTask.recordedResumeSafetyFailure(safeWorld, true)
                        == FarmTaskReason.FREEZE_RISK,
                "recorded farms re-run the live hypothetical-water freeze gate");

        require(SetupFarmTask.shouldRetireOrphanedFarmProtection(true, true),
                "an air cell with an old placement marker is safe bounded repair metadata");
        require(!SetupFarmTask.shouldRetireOrphanedFarmProtection(true, false)
                        && !SetupFarmTask.shouldRetireOrphanedFarmProtection(false, true),
                "live blocks and unmarked air never trigger orphan-marker cleanup");
    }

    private static void explicitCenterRoutesToRecordedOrForcedRepair() {
        String dimension = "minecraft:overworld";
        BlockPos center = new BlockPos(11, 71, -2);

        require(SetupFarmTask.resolveExactCenter(null, dimension, center, true).status()
                        == SetupFarmTask.ExactCenterStatus.NEW_FORCED,
                "an unoccupied loaded exact center enters forced bounded repair");
        require(SetupFarmTask.resolveExactCenter(null, dimension, center, false).status()
                        == SetupFarmTask.ExactCenterStatus.UNLOADED,
                "forced repair requires the complete exact-center envelope loaded");

        WaypointRecord hiddenCanonicalId = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        hiddenCanonicalId.pos = new int[]{center.getX() + 1, center.getY(), center.getZ()};
        require(SetupFarmTask.resolveExactCenter(
                        null, hiddenCanonicalId, dimension, center, true).status()
                        == SetupFarmTask.ExactCenterStatus.UNSUPPORTED_DATA,
                "a same-ID farm hidden from position lookup is never overwritten as a forced site");
        require(SetupFarmTask.resolveExactCenter(
                        hiddenCanonicalId, null, dimension, center, true).status()
                        == SetupFarmTask.ExactCenterStatus.UNSUPPORTED_DATA,
                "a position-only farm without canonical ID authority is never overwritten");

        WaypointRecord stale = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        SetupFarmTask.ExactCenterResolution staleResolution =
                SetupFarmTask.resolveExactCenter(stale, dimension, center, true);
        require(staleResolution.status() == SetupFarmTask.ExactCenterStatus.RECORDED
                        && staleResolution.recorded() != null
                        && stale.id.equals(staleResolution.recorded().id())
                        && center.equals(staleResolution.recorded().center()),
                "an exact stale farm binds its canonical durable identity");

        WaypointRecord current = farmWaypoint(
                dimension, center, false, FarmPlotPolicy.HYDRATION_RADIUS);
        require(SetupFarmTask.resolveExactCenter(current, dimension, center, true).status()
                        == SetupFarmTask.ExactCenterStatus.RECORDED,
                "an exact current farm also enters repair and is re-prepared before mutation");
        require(SetupFarmTask.resolveExactCenter(current, dimension, center, false).status()
                        == SetupFarmTask.ExactCenterStatus.UNLOADED,
                "a recorded exact farm never repairs through an incomplete envelope");

        WaypointRecord wrongType = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        wrongType.type = WaypointTypes.INVENTORY;
        require(SetupFarmTask.resolveExactCenter(wrongType, dimension, center, false).status()
                        == SetupFarmTask.ExactCenterStatus.TYPE_CONFLICT,
                "an exact non-farm occupant is a typed conflict even while unloaded");
        require(SetupFarmTask.resolveExactCenter(
                        null, wrongType, dimension, center, true).status()
                        == SetupFarmTask.ExactCenterStatus.TYPE_CONFLICT,
                "a non-farm canonical-ID occupant conflicts even when position lookup misses it");

        WaypointRecord wrongRadius = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS - 1);
        WaypointRecord futureSchema = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        futureSchema.schemaVersion = WaypointRecord.WAYPOINT_SCHEMA_VERSION + 1;
        WaypointRecord nonCanonical = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        nonCanonical.id = "legacy-farm-id";
        WaypointRecord missingData = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        missingData.data = null;
        WaypointRecord wrongDimension = farmWaypoint(
                "minecraft:the_nether", center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        WaypointRecord malformedPosition = farmWaypoint(
                dimension, center, true, FarmPlotPolicy.HYDRATION_RADIUS);
        malformedPosition.pos = new int[]{center.getX(), center.getY()};
        for (WaypointRecord unsupported : List.of(
                wrongRadius, futureSchema, nonCanonical, missingData,
                wrongDimension, malformedPosition)) {
            require(SetupFarmTask.resolveExactCenter(
                            unsupported, dimension, center, true).status()
                            == SetupFarmTask.ExactCenterStatus.UNSUPPORTED_DATA,
                    "malformed or unsupported exact farms are never overwritten");
        }
    }

    private static void exactRepairRejectionsStaySpecific() {
        require(SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.UNREADABLE)
                        == FarmTaskReason.CHUNK_UNLOADED,
                "unreadable exact repair envelope maps to chunk-unloaded");
        require(SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.UNSAFE_SUPPORT)
                        == FarmTaskReason.EXACT_SITE_UNSAFE_SUPPORT
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.UNSAFE_OBSTRUCTION)
                        == FarmTaskReason.EXACT_SITE_UNSAFE_OBSTRUCTION
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.NON_CENTER_FLUID)
                        == FarmTaskReason.EXACT_SITE_FLUID_CONFLICT
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.CROP_CONFLICT)
                        == FarmTaskReason.EXACT_SITE_CROP_CONFLICT
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.REPAIR_LIMIT_EXCEEDED)
                        == FarmTaskReason.EXACT_SITE_REPAIR_LIMIT_EXCEEDED,
                "exact repair hard gates retain bounded distinct reasons");
        require(SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.NO_RETURN_STANCE)
                        == FarmTaskReason.EXACT_SITE_NO_SAFE_STANCE
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.NO_ACTION_STANCE)
                        == FarmTaskReason.EXACT_SITE_NO_SAFE_STANCE,
                "both exact stance failures share the bounded no-safe-stance reason");
        require(SetupFarmTask.exactRepairFailureReason(null)
                        == FarmTaskReason.INTERNAL_CONTRACT_FAILURE
                        && SetupFarmTask.exactRepairFailureReason(
                        FarmPlotRepairPlan.UnsafeReason.NONE)
                        == FarmTaskReason.INTERNAL_CONTRACT_FAILURE,
                "impossible exact repair reasons never masquerade as site suitability");

        FarmTaskOutcome exactFluid = new FarmTaskOutcome(
                true, false, FarmTaskReason.EXACT_SITE_FLUID_CONFLICT,
                FarmTaskPhase.PRECHECK, "minecraft:overworld",
                new BlockPos(11, 71, -2), 0, 0, 0, false, null);
        String exactModel = FarmFeedback.setupModel(exactFluid);
        require(exactModel.contains("requested fixed 9x9 footprint")
                        && exactModel.contains("fluid outside the permitted center water cell")
                        && exactModel.contains("no blocks were changed")
                        && exactModel.contains("retrying this exact center unchanged will fail again")
                        && exactModel.contains("coordinate-less setup_farm")
                        && exactModel.length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "exact rejection feedback is specific, bounded, and prevents unchanged retries");
        require(FarmFeedback.setupPlayerReasonKey(exactFluid).equals(
                        "message.playerengine.farming.reason.exact_site_fluid_conflict")
                        && FarmFeedback.exactSetupPlayerResultKey(exactFluid).equals(
                        "message.playerengine.farming.setup.exact_rejected_at")
                        && FarmFeedback.setupPlayer(exactFluid) != null,
                "exact rejection has dedicated keyed player reason and actionable result text");

        FarmTaskOutcome exactFreeze = new FarmTaskOutcome(
                true, false, SetupFarmTask.exactWorldSafetyFailure(FarmTaskReason.FREEZE_RISK),
                FarmTaskPhase.PRECHECK, "minecraft:overworld",
                new BlockPos(11, 71, -2), 0, 0, 0, false, null);
        require(exactFreeze.reason() == FarmTaskReason.EXACT_SITE_FREEZE_RISK
                        && FarmFeedback.isExactSiteReason(exactFreeze.reason())
                        && FarmFeedback.setupModel(exactFreeze).contains(
                        "center water would freeze at this exact site")
                        && FarmFeedback.exactSetupPlayerResultKey(exactFreeze).equals(
                        "message.playerengine.farming.setup.exact_rejected_at"),
                "new forced cold sites receive exact no-mutation and retry guidance");

        FarmTaskOutcome ultraWarm = new FarmTaskOutcome(
                true, false, FarmTaskReason.WATER_EVAPORATES,
                FarmTaskPhase.PRECHECK, "minecraft:the_nether",
                null, 0, 0, 0, false, null);
        require(FarmFeedback.setupModel(ultraWarm).contains(
                        "water cannot remain placed in this ultra-warm dimension")
                        && FarmFeedback.setupPlayerReasonKey(ultraWarm).equals(
                        "message.playerengine.farming.reason.water_evaporates")
                        && FarmFeedback.exactSetupPlayerResultKey(ultraWarm) == null,
                "ultra-warm rejection is truthful without pretending a different local center helps");

        FarmTaskOutcome automatic = new FarmTaskOutcome(
                true, false, FarmTaskReason.NO_SUITABLE_SITE,
                FarmTaskPhase.SELECT_SITE, "minecraft:overworld",
                null, 0, 0, 0, false, null);
        String automaticModel = FarmFeedback.setupModel(automatic);
        require(automaticModel.contains(
                        "automatic nearby selection found no safe, sufficiently flat grass site")
                        && !automaticModel.contains("retrying this exact center")
                        && FarmFeedback.setupPlayerReasonKey(automatic).equals(
                        "message.playerengine.farming.reason.no_suitable_site"),
                "coordinate-less site search keeps its distinct grass-suitability feedback");
    }

    private static WaypointRecord farmWaypoint(
            String dimension,
            BlockPos center,
            boolean stale,
            int radius) {
        WaypointRecord record = new WaypointRecord();
        record.id = WaypointRecord.idFor(dimension, center);
        record.type = WaypointTypes.FARM;
        record.dimension = dimension;
        record.pos = new int[]{center.getX(), center.getY(), center.getZ()};
        record.stale = stale;
        record.data = new FarmWaypointData(radius, 0, List.of());
        return record;
    }

    private static void resumeMutationBudgetsStayCumulative() {
        require(SetupFarmTask.withinCumulativeMutationBudget(
                        20, 60, FarmPlotPolicy.MAX_TILL_ACTIONS),
                "partial till plus a regenerated continuation stays within the bounded repair total");
        require(!SetupFarmTask.withinCumulativeMutationBudget(
                        FarmPlotPolicy.MAX_TILL_ACTIONS, 1, FarmPlotPolicy.MAX_TILL_ACTIONS),
                "resume cannot exceed the whole-operation retill bound");
        require(SetupFarmTask.withinCumulativeMutationBudget(
                        FarmPlotPolicy.MAX_FILL_ACTIONS, 0,
                        FarmPlotPolicy.MAX_FILL_ACTIONS),
                "completed repair budget survives a no-op resume");
        require(!SetupFarmTask.withinCumulativeMutationBudget(
                        FarmPlotPolicy.MAX_FILL_ACTIONS, 1,
                        FarmPlotPolicy.MAX_FILL_ACTIONS),
                "resume cannot renew the finite repair budget");
    }

    private static void failedPhasesStayOperational() {
        assertFailedPhase(FarmTaskPhase.CLEAR, FarmTaskReason.MANIFEST_DRIFT, null);
        assertFailedPhase(FarmTaskPhase.REPAIR, FarmTaskReason.MANIFEST_DRIFT, null);
        assertFailedPhase(FarmTaskPhase.TILL, FarmTaskReason.INTERACTION_DENIED, null);
        assertFailedPhase(
                FarmTaskPhase.COMMIT,
                FarmTaskReason.WAYPOINT_JSON_FAILURE,
                new WaypointMutationResult(WaypointMutationStatus.FAILED_JSON_COMMIT, null, null));
    }

    private static void assertFailedPhase(
            FarmTaskPhase phase,
            FarmTaskReason reason,
            WaypointMutationResult waypointResult) {
        FarmTaskOutcome outcome = new FarmTaskOutcome(
                true, false, reason, phase,
                "minecraft:overworld", new BlockPos(2, 64, 3),
                1, 1, 1, false, waypointResult);
        require(outcome.phase() == phase,
                "failed outcome preserves operational phase " + phase);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected contract rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
