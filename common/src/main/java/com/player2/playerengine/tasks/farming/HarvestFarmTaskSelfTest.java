package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic contract fixtures for the finite harvest state machine. */
public final class HarvestFarmTaskSelfTest {
    private HarvestFarmTaskSelfTest() {
    }

    public static void runAll() {
        outcomeContract();
        terminalMappings();
        deadlinePrecedence();
        pickupEnvelope();
        pickupIdentityAndCountBounds();
        actionGateRace();
        protectedMatureCropPolicy();
    }

    private static void outcomeContract() {
        HarvestFarmOutcome initial = new HarvestFarmTask(
                BlockPos.ZERO, "minecraft:overworld").outcome();
        check(!initial.terminal() && initial.phase() == FarmTaskPhase.HARVEST_RESOLVE,
                "pre-start outcome is safe and explicitly unresolved");
        WaypointMutationResult clean = new WaypointMutationResult(
                WaypointMutationStatus.NO_CHANGE, null, null);
        HarvestFarmOutcome success = new HarvestFarmOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                "minecraft:overworld", BlockPos.ZERO,
                4, 2, 2, 0, 999, true, clean);
        check(success.successful() && success.pickupItemUnitsLeft() == 999,
                "bounded degraded success fixture");

        HarvestFarmOutcome denied = new HarvestFarmOutcome(
                true, false, FarmTaskReason.PARTIAL_DENIED,
                FarmTaskPhase.HARVEST_BREAK,
                "minecraft:overworld", BlockPos.ZERO,
                4, 2, 1, 1, 0, false, clean);
        check(denied.phase() == FarmTaskPhase.HARVEST_BREAK,
                "failed harvest preserves operational phase");

        expectThrows(() -> new HarvestFarmOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                "minecraft:overworld", BlockPos.ZERO,
                2, 2, 1, 0, 0, false, clean),
                "terminal counts cannot exceed the initially mature set");
        expectThrows(() -> new HarvestFarmOutcome(
                true, false, FarmTaskReason.NONE, FarmTaskPhase.HARVEST_BREAK,
                "minecraft:overworld", BlockPos.ZERO,
                0, 0, 0, 0, 0, false, null),
                "failed terminal outcome requires controlled reason");
        expectThrows(() -> new HarvestFarmOutcome(
                true, false, FarmTaskReason.INTERNAL_CONTRACT_FAILURE,
                FarmTaskPhase.HARVEST_SCAN,
                "minecraft:overworld", null,
                0, 0, 0, 0, 0, false, null),
                "resolved operational phase requires a center");
    }

    private static void terminalMappings() {
        check(HarvestFarmTask.failureForSearch(
                        WaypointSearchStatus.FAILED_SCAN_LIMIT, true)
                        == FarmTaskReason.SEARCH_SCAN_LIMIT,
                "scan-limit search fails before mutation");
        check(HarvestFarmTask.failureForSearch(
                        WaypointSearchStatus.AUTHORITATIVE_SPATIAL, true)
                        == FarmTaskReason.FARM_NOT_FOUND,
                "empty nearest search is not found");
        check(HarvestFarmTask.failureForMutation(
                        WaypointMutationStatus.COMMITTED_INDEX_DEGRADED)
                        == FarmTaskReason.NONE,
                "index-degraded commit remains harvest-success eligible");
        check(HarvestFarmTask.failureForMutation(
                        WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED)
                        == FarmTaskReason.NONE,
                "index-degraded no-op remains harvest-success eligible");
        check(HarvestFarmTask.failureForMutation(
                        WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED)
                        == FarmTaskReason.FARM_NOT_FOUND,
                "not-found keeps failure precedence over index degradation");
        check(HarvestFarmTask.failureForMutation(
                        WaypointMutationStatus.REJECTED_TARGET_CONFLICT)
                        == FarmTaskReason.TYPE_CONFLICT,
                "target conflict maps to bounded type conflict");
        check(HarvestFarmTask.failureForMutation(
                        WaypointMutationStatus.FAILED_JSON_COMMIT)
                        == FarmTaskReason.WAYPOINT_JSON_FAILURE,
                "JSON commit failure remains explicit");
    }

    private static void deadlinePrecedence() {
        check(HarvestFarmTask.deadlineDecision(
                        HarvestFarmTask.WHOLE_TIMEOUT_TICKS - 1,
                        HarvestFarmTask.PICKUP_TIMEOUT_TICKS - 1,
                        FarmTaskPhase.HARVEST_PICKUP)
                        == HarvestFarmTask.DeadlineDecision.RUNNING,
                "both watchdogs still running");
        check(HarvestFarmTask.deadlineDecision(
                        HarvestFarmTask.WHOLE_TIMEOUT_TICKS - 1,
                        HarvestFarmTask.PICKUP_TIMEOUT_TICKS,
                        FarmTaskPhase.HARVEST_PICKUP)
                        == HarvestFarmTask.DeadlineDecision.PICKUP_BUDGET_EXPIRED,
                "pickup expiry is best-effort while whole budget remains");
        check(HarvestFarmTask.deadlineDecision(
                        HarvestFarmTask.WHOLE_TIMEOUT_TICKS,
                        HarvestFarmTask.PICKUP_TIMEOUT_TICKS,
                        FarmTaskPhase.HARVEST_PICKUP)
                        == HarvestFarmTask.DeadlineDecision.WHOLE_TIMEOUT,
                "whole timeout wins on the shared expiry tick");
    }

    private static void pickupEnvelope() {
        BlockPos center = new BlockPos(10, 64, -5);
        Vec3 origin = Vec3.atCenterOf(center);
        check(HarvestFarmTask.isInsidePickupEnvelope(
                        center, origin.add(HarvestFarmTask.PICKUP_HORIZONTAL_RADIUS, 0, 0)),
                "horizontal boundary is included");
        check(!HarvestFarmTask.isInsidePickupEnvelope(
                        center, origin.add(HarvestFarmTask.PICKUP_HORIZONTAL_RADIUS + 0.01, 0, 0)),
                "horizontal radius is fixed");
        check(HarvestFarmTask.isInsidePickupEnvelope(
                        center, origin.add(0, HarvestFarmTask.PICKUP_VERTICAL_RADIUS, 0)),
                "vertical boundary is included");
        check(!HarvestFarmTask.isInsidePickupEnvelope(
                        center, origin.add(0, HarvestFarmTask.PICKUP_VERTICAL_RADIUS + 0.01, 0)),
                "vertical radius is fixed");
        check(HarvestFarmTask.MAX_PICKUP_ENTITIES == 64,
                "pickup target cap remains frozen");
    }

    private static void actionGateRace() {
        net.minecraft.world.level.block.state.BlockState mature = Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7);
        net.minecraft.world.level.block.state.BlockState immature = Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 6);
        check(HarvestFarmTask.isActionable(mature),
                "mature crop remains actionable at the final gate");
        check(!HarvestFarmTask.isActionable(immature),
                "mature-to-immature race is skipped at the final gate");
        check(!HarvestFarmTask.isActionable(Blocks.AIR.defaultBlockState()),
                "mature-to-air race is skipped at the final gate");
        check(VerifiedBreakBlockTask.observedTransition(
                        mature, Blocks.AIR.defaultBlockState(), BlockState::isAir, 0)
                        == VerifiedBreakBlockTask.ObservedTransition.PRE_ATTEMPT_DRIFT,
                "another actor's pre-input break is race-skipped, not harvested");
        check(VerifiedBreakBlockTask.observedTransition(
                        mature, immature, BlockState::isAir, 0)
                        == VerifiedBreakBlockTask.ObservedTransition.PRE_ATTEMPT_DRIFT,
                "move-window maturity drift is race-skipped");
        check(VerifiedBreakBlockTask.observedTransition(
                        mature, Blocks.AIR.defaultBlockState(), BlockState::isAir, 1)
                        == VerifiedBreakBlockTask.ObservedTransition.SUCCESS,
                "air transition after NPC break input is accepted");
        check(VerifiedBreakBlockTask.observedTransition(
                        mature, Blocks.STONE.defaultBlockState(), BlockState::isAir, 1)
                        == VerifiedBreakBlockTask.ObservedTransition.INVALID_DRIFT,
                "post-input non-break drift is not claimed as harvest success");
    }

    private static void protectedMatureCropPolicy() {
        check(!VerifiedBreakBlockTask.targetProtectionDenied(true, true,
                        HarvestFarmTask.isActionable(Blocks.WHEAT.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7))),
                "protected mature crop target is harvestable");
        check(VerifiedBreakBlockTask.targetProtectionDenied(true, true,
                        HarvestFarmTask.isActionable(Blocks.WHEAT.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 6))),
                "protected immature crop target remains denied");
        check(VerifiedBreakBlockTask.targetProtectionDenied(true, true,
                        HarvestFarmTask.isActionable(Blocks.STONE.defaultBlockState())),
                "protected non-crop target remains denied");
        check(!VerifiedBreakBlockTask.targetProtectionDenied(false, false, true),
                "ordinary unprotected break policy is unchanged");
    }

    private static void pickupIdentityAndCountBounds() {
        UUID preExisting = new UUID(0L, 1L);
        Set<UUID> before = new HashSet<>(Set.of(preExisting));
        ArrayList<UUID> observed = new ArrayList<>();
        observed.add(preExisting);
        for (int i = 0; i < HarvestFarmTask.MAX_PICKUP_ENTITIES + 1; i++) {
            observed.add(new UUID(1L, i));
        }
        LinkedHashSet<UUID> tracked = new LinkedHashSet<>();
        check(HarvestFarmTask.trackEligibleDropIds(observed, before, tracked),
                "65th new drop reports pickup entity overflow");
        check(tracked.size() == HarvestFarmTask.MAX_PICKUP_ENTITIES,
                "pickup UUID tracking is capped at 64");
        check(!tracked.contains(preExisting),
                "pre-existing drop UUID is excluded");
        check(HarvestFarmTask.saturatingItemUnits(List.of(400, 598)) == 998,
                "pickup units count item-stack units");
        check(HarvestFarmTask.saturatingItemUnits(List.of(400, 599, 64)) == 999,
                "pickup unit count saturates at 999");
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("HarvestFarmTask self-test failed: " + message);
        }
    }
}
