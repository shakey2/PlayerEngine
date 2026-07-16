package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import net.minecraft.core.BlockPos;

/** Pure feedback/direct-completion matrix for bounded harvest outcomes. */
public final class FarmFeedbackSelfTest {
    private static final BlockPos CENTER = new BlockPos(12, 64, -8);

    private FarmFeedbackSelfTest() {
    }

    public static void runAll() {
        completeAndNoMature();
        raceAndDenials();
        pickupAndPersistence();
        staleCancelTimeout();
        boundedOpaqueWaypointData();
    }

    private static void completeAndNoMature() {
        HarvestFarmOutcome complete = success(4, 4, 0, 0, 0, false,
                WaypointMutationStatus.COMMITTED);
        assertCompletion(complete, FarmFeedback.HarvestCompletion.INFO,
                "phase=done", "waypoint=committed", "harvested=4");
        check("message.playerengine.farming.harvest.success".equals(
                        FarmFeedback.harvestBasePlayerKey(complete)),
                "complete player result uses success key");

        HarvestFarmOutcome noMature = success(0, 0, 0, 0, 0, false,
                WaypointMutationStatus.NO_CHANGE);
        assertCompletion(noMature, FarmFeedback.HarvestCompletion.INFO,
                "mature=0", "waypoint=no_change");
    }

    private static void raceAndDenials() {
        HarvestFarmOutcome race = success(2, 1, 1, 0, 0, false,
                WaypointMutationStatus.NO_CHANGE);
        assertCompletion(race, FarmFeedback.HarvestCompletion.NOTE,
                "raceSkipped=1");

        HarvestFarmOutcome partial = failure(
                FarmTaskReason.PARTIAL_DENIED, FarmTaskPhase.HARVEST_BREAK,
                3, 1, 1, 1, 0, false, WaypointMutationStatus.COMMITTED);
        assertCompletion(partial, FarmFeedback.HarvestCompletion.ERROR,
                "phase=harvest_break", "denied=1", "partial denied");

        HarvestFarmOutcome all = failure(
                FarmTaskReason.ALL_DENIED, FarmTaskPhase.HARVEST_BREAK,
                2, 0, 0, 2, 0, false, WaypointMutationStatus.NO_CHANGE);
        assertCompletion(all, FarmFeedback.HarvestCompletion.ERROR,
                "denied=2", "all denied");
    }

    private static void pickupAndPersistence() {
        HarvestFarmOutcome pickup = success(1, 1, 0, 0, 23, true,
                WaypointMutationStatus.NO_CHANGE);
        assertCompletion(pickup, FarmFeedback.HarvestCompletion.NOTE,
                "at least 23", "additional new drop entities");

        HarvestFarmOutcome json = failure(
                FarmTaskReason.WAYPOINT_JSON_FAILURE, FarmTaskPhase.COMMIT,
                1, 1, 0, 0, 0, false, WaypointMutationStatus.FAILED_JSON_COMMIT);
        assertCompletion(json, FarmFeedback.HarvestCompletion.ERROR,
                "phase=commit", "waypoint=failed_json_commit", "waypoint json failure");

        for (WaypointMutationStatus status : new WaypointMutationStatus[]{
                WaypointMutationStatus.COMMITTED_INDEX_DEGRADED,
                WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED}) {
            HarvestFarmOutcome degraded = success(1, 1, 0, 0, 0, false, status);
            assertCompletion(degraded, FarmFeedback.HarvestCompletion.NOTE,
                    "waypoint=" + status.name().toLowerCase(java.util.Locale.ROOT),
                    "indexing is degraded");
        }

        HarvestFarmOutcome missing = failure(
                FarmTaskReason.FARM_NOT_FOUND, FarmTaskPhase.COMMIT,
                1, 1, 0, 0, 0, false,
                WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED);
        assertCompletion(missing, FarmFeedback.HarvestCompletion.ERROR,
                "waypoint=not_found_index_degraded", "indexing is degraded");
    }

    private static void staleCancelTimeout() {
        HarvestFarmOutcome stale = failure(
                FarmTaskReason.STALE_CENTER, FarmTaskPhase.HARVEST_RESOLVE,
                0, 0, 0, 0, 0, false, null);
        assertCompletion(stale, FarmFeedback.HarvestCompletion.ERROR,
                "phase=harvest_resolve", "waypoint=not_attempted", "stale center");

        HarvestFarmOutcome cancelled = failure(
                FarmTaskReason.CANCELLED_OPERATOR, FarmTaskPhase.HARVEST_PICKUP,
                1, 1, 0, 0, 2, false, null);
        assertCompletion(cancelled, FarmFeedback.HarvestCompletion.ERROR,
                "phase=harvest_pickup", "cancelled operator");

        HarvestFarmOutcome timeout = failure(
                FarmTaskReason.HARVEST_TIMEOUT, FarmTaskPhase.HARVEST_PICKUP,
                1, 1, 0, 0, 3, false, null);
        assertCompletion(timeout, FarmFeedback.HarvestCompletion.ERROR,
                "phase=harvest_pickup", "harvest timeout");
    }

    private static void boundedOpaqueWaypointData() {
        String sentinel = "private-extra-" + "x".repeat(1_000);
        WaypointRecord record = new WaypointRecord();
        record.id = sentinel;
        record.description = sentinel;
        HarvestFarmOutcome outcome = new HarvestFarmOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                "minecraft:overworld", new BlockPos(Integer.MAX_VALUE, 64, Integer.MIN_VALUE),
                80, 80, 0, 0, 999, true,
                new WaypointMutationResult(WaypointMutationStatus.COMMITTED, record, record));
        String model = FarmFeedback.harvestModel(outcome);
        check(model.length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "harvest model feedback is bounded");
        check(!model.contains("private-extra"),
                "waypoint IDs, descriptions, and extras never enter model feedback");
        check(FarmFeedback.harvestPlayer(outcome) != null,
                "bounded harvest player feedback remains keyed");
    }

    private static HarvestFarmOutcome success(
            int mature,
            int harvested,
            int skipped,
            int denied,
            int pickupLeft,
            boolean overflow,
            WaypointMutationStatus waypointStatus) {
        return new HarvestFarmOutcome(
                true, true, FarmTaskReason.NONE, FarmTaskPhase.DONE,
                "minecraft:overworld", CENTER,
                mature, harvested, skipped, denied, pickupLeft, overflow,
                mutation(waypointStatus));
    }

    private static HarvestFarmOutcome failure(
            FarmTaskReason reason,
            FarmTaskPhase phase,
            int mature,
            int harvested,
            int skipped,
            int denied,
            int pickupLeft,
            boolean overflow,
            WaypointMutationStatus waypointStatus) {
        return new HarvestFarmOutcome(
                true, false, reason, phase,
                "minecraft:overworld", CENTER,
                mature, harvested, skipped, denied, pickupLeft, overflow,
                waypointStatus == null ? null : mutation(waypointStatus));
    }

    private static WaypointMutationResult mutation(WaypointMutationStatus status) {
        return new WaypointMutationResult(status, null, null);
    }

    private static void assertCompletion(
            HarvestFarmOutcome outcome,
            FarmFeedback.HarvestCompletion expected,
            String... fragments) {
        check(FarmFeedback.harvestCompletion(outcome) == expected,
                "direct completion classification for " + outcome.reason());
        String model = FarmFeedback.harvestModel(outcome);
        check(model.length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "model feedback cap for " + outcome.reason());
        for (String fragment : fragments) {
            check(model.contains(fragment),
                    "model feedback includes '" + fragment + "'");
        }
        check(FarmFeedback.harvestPlayer(outcome) != null,
                "player feedback exists for " + outcome.reason());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("FarmFeedback self-test failed: " + message);
        }
    }
}
