package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Audience-separated bounded feedback for one finite planting operation. */
public final class PlantFarmFeedback {
    public enum Completion {
        INFO,
        NOTE,
        ERROR
    }

    private PlantFarmFeedback() {
    }

    public static Component player(PlantFarmOutcome outcome) {
        if (outcome == null) {
            return Component.translatable("message.playerengine.farming.plant.cancelled");
        }
        if (!outcome.terminal()) {
            return Component.translatable("message.playerengine.farming.plant.not_started");
        }
        MutableComponent result;
        if (outcome.successful()) {
            result = Component.translatable(
                    "message.playerengine.farming.plant.success",
                    FarmFeedback.formatCenter(outcome.center()),
                    outcome.plantedTotal(),
                    outcome.openSlotsAfter());
        } else if (outcome.center() == null) {
            result = Component.translatable(
                    "message.playerengine.farming.plant.failed",
                    Component.translatable(phaseKey(outcome.phase())),
                    Component.translatable(reasonKey(outcome.reason())),
                    outcome.plantedTotal(),
                    outcome.requestedTotal());
        } else {
            result = Component.translatable(
                    "message.playerengine.farming.plant.failed_at",
                    FarmFeedback.formatCenter(outcome.center()),
                    Component.translatable(phaseKey(outcome.phase())),
                    Component.translatable(reasonKey(outcome.reason())),
                    outcome.plantedTotal(),
                    outcome.requestedTotal());
        }
        if (isIndexDegraded(outcome)) {
            result.append(" ").append(Component.translatable(
                    "message.playerengine.farming.plant.index_degraded"));
        }
        return result;
    }

    public static String model(PlantFarmOutcome outcome) {
        if (outcome == null) {
            return "farm planting was cancelled before a terminal outcome was recorded";
        }
        if (!outcome.terminal()) {
            return "farm planting could not start because the NPC was not available in-game";
        }
        String center = outcome.center() == null
                ? "unresolved farm"
                : FarmFeedback.formatCenter(outcome.center());
        String counts = outcome.plantedTotal() + "/" + outcome.requestedTotal() + " planted";
        String result;
        if (outcome.successful()) {
            result = "farm planting succeeded at " + center + " (" + counts
                    + ", open slots=" + outcome.openSlotsAfter() + ")";
        } else if (outcome.reason() == PlantFarmReason.NO_RECORDED_FARMS) {
            result = "farm planting could not start because no EllieGPS farm is recorded; tell the player"
                    + " there is no recorded farm and ask whether they want the NPC to set one up";
        } else if (outcome.reason() == PlantFarmReason.ALL_RECORDED_FARMS_FULL) {
            result = "farm planting could not start because every recorded farm is full";
        } else {
            result = "farm planting failed at " + center + " during "
                    + humanize(outcome.phase().name()) + ": "
                    + humanize(outcome.reason().name()) + " (" + counts + ")";
        }
        if (isIndexDegraded(outcome)) {
            result += "; the waypoint JSON was saved but EllieGPS indexing is degraded";
        }
        return FarmFeedback.bound(result, FarmFeedback.MAX_MODEL_LENGTH);
    }

    public static Completion completion(PlantFarmOutcome outcome) {
        if (outcome == null || !outcome.terminal() || !outcome.successful()) {
            return Completion.ERROR;
        }
        return isIndexDegraded(outcome) ? Completion.NOTE : Completion.INFO;
    }

    public static boolean isIndexDegraded(PlantFarmOutcome outcome) {
        if (outcome == null || outcome.waypointResult() == null) {
            return false;
        }
        WaypointMutationStatus status = outcome.waypointResult().status();
        return status == WaypointMutationStatus.COMMITTED_INDEX_DEGRADED
                || status == WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED
                || status == WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED;
    }

    public static String phaseKey(PlantFarmPhase phase) {
        PlantFarmPhase safe = phase == null ? PlantFarmPhase.FAILED : phase;
        return "message.playerengine.farming.plant.phase."
                + safe.name().toLowerCase(Locale.ROOT);
    }

    public static String reasonKey(PlantFarmReason reason) {
        PlantFarmReason safe = reason == null
                ? PlantFarmReason.INTERNAL_CONTRACT_FAILURE
                : reason;
        return "message.playerengine.farming.plant.reason."
                + safe.name().toLowerCase(Locale.ROOT);
    }

    private static String humanize(String token) {
        return token.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
