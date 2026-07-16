package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable bounded result shared by direct and agentic planting surfaces. */
public record PlantFarmOutcome(
        boolean terminal,
        boolean successful,
        PlantFarmReason reason,
        PlantFarmPhase phase,
        String dimension,
        BlockPos center,
        List<FarmPlantingRequest> requested,
        List<FarmPlantingRequest> planted,
        int openSlotsAfter,
        WaypointMutationResult waypointResult
) {
    public PlantFarmOutcome {
        reason = Objects.requireNonNull(reason, "reason");
        phase = Objects.requireNonNull(phase, "phase");
        if (dimension != null && dimension.isBlank()) {
            throw new IllegalArgumentException("dimension cannot be blank");
        }
        center = center == null ? null : center.immutable();
        requested = requested == null ? List.of() : List.copyOf(requested);
        planted = planted == null ? List.of() : List.copyOf(planted);
        if (openSlotsAfter < -1 || openSlotsAfter > FarmPlotPolicy.SOIL_CELL_COUNT) {
            throw new IllegalArgumentException("openSlotsAfter must be unknown or within one farm");
        }
        int requestedTotal = sum(requested);
        int plantedTotal = sum(planted);
        if (requestedTotal > FarmPlotPolicy.SOIL_CELL_COUNT || plantedTotal > requestedTotal) {
            throw new IllegalArgumentException("planting totals violate one-farm bounds");
        }
        Map<String, Integer> requestedByItem = totalsByItem(requested);
        Map<String, Integer> plantedByItem = totalsByItem(planted);
        for (Map.Entry<String, Integer> entry : plantedByItem.entrySet()) {
            Integer requestedCount = requestedByItem.get(entry.getKey());
            if (requestedCount == null || entry.getValue() > requestedCount) {
                throw new IllegalArgumentException(
                        "planted crop counts must be a per-item subset of the request");
            }
        }
        if (successful && (!terminal || reason != PlantFarmReason.NONE
                || phase != PlantFarmPhase.DONE || plantedTotal != requestedTotal
                || !plantedByItem.equals(requestedByItem)
                || center == null || dimension == null || waypointResult == null)) {
            throw new IllegalArgumentException("successful planting needs a complete committed outcome");
        }
        if (terminal && !successful
                && (reason == PlantFarmReason.NONE || phase == PlantFarmPhase.DONE)) {
            throw new IllegalArgumentException("failed terminal planting needs an operational reason");
        }
        if (!terminal && (successful || reason != PlantFarmReason.NONE)) {
            throw new IllegalArgumentException("non-terminal planting cannot be successful or failed");
        }
    }

    public int requestedTotal() {
        return sum(requested);
    }

    public int plantedTotal() {
        return sum(planted);
    }

    private static int sum(List<FarmPlantingRequest> values) {
        int total = 0;
        for (FarmPlantingRequest value : values) {
            total = Math.addExact(total, value.count());
        }
        return total;
    }

    private static Map<String, Integer> totalsByItem(List<FarmPlantingRequest> values) {
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        for (FarmPlantingRequest value : values) {
            totals.merge(value.plantingItemId(), value.count(), Math::addExact);
        }
        return Map.copyOf(totals);
    }
}
