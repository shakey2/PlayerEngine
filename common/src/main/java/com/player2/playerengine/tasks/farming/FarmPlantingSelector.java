package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure typed preselection over waypoint metadata; the root still live-scans every candidate. */
public final class FarmPlantingSelector {
    public record Selection(List<WaypointRecord> candidates, PlantFarmReason failure) {
        public Selection {
            candidates = candidates == null
                    ? List.of()
                    : candidates.stream().map(WaypointRecord::copy).toList();
            failure = Objects.requireNonNull(failure, "failure");
            if (candidates.isEmpty() == (failure == PlantFarmReason.NONE)) {
                throw new IllegalArgumentException(
                        "selection needs candidates or one terminal failure, but not both");
            }
        }

        public boolean successful() {
            return failure == PlantFarmReason.NONE;
        }
    }

    private FarmPlantingSelector() {
    }

    public static Selection automatic(
            List<WaypointRecord> records,
            String dimension,
            BlockPos anchor,
            List<FarmPlantingRequest> requests) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(requests, "requests");

        ArrayList<WaypointRecord> currentFarms = new ArrayList<>();
        for (WaypointRecord record : records) {
            if (record != null
                    && WaypointTypes.FARM.equals(record.type)
                    && dimension.equals(record.dimension)
                    && !record.stale
                    && record.canonicalBlockPos() != null) {
                currentFarms.add(record.copy());
            }
        }
        currentFarms.sort(Comparator
                .comparingLong((WaypointRecord record) -> FarmPlotGeometry.horizontalDistanceSquared(
                        anchor, record.canonicalBlockPos()))
                .thenComparing(record -> Objects.toString(record.id, "")));
        if (currentFarms.isEmpty()) {
            return failed(PlantFarmReason.NO_RECORDED_FARMS);
        }

        int supported = 0;
        ArrayList<WaypointRecord> viable = new ArrayList<>();
        for (WaypointRecord record : currentFarms) {
            FarmWaypointData data = record.farmData();
            if (data == null || !data.isSupportedVersion()
                    || data.radius() != FarmPlotPolicy.HYDRATION_RADIUS) {
                continue;
            }
            supported++;
            boolean acceptsEvery = true;
            for (FarmPlantingRequest request : requests) {
                if (!data.acceptsPlantingItem(request.plantingItemId())) {
                    acceptsEvery = false;
                    break;
                }
            }
            if (!acceptsEvery) {
                continue;
            }
            // Capacity metadata is a cached observation, not an action-time authorization. Keep
            // every policy-compatible candidate in nearest order so the root can live-scan it;
            // players or other tasks may have opened or occupied cells since the last refresh.
            viable.add(record);
        }
        if (!viable.isEmpty()) {
            return new Selection(viable, PlantFarmReason.NONE);
        }
        if (supported == 0) {
            return failed(PlantFarmReason.UNSUPPORTED_FARM_DATA);
        }
        return failed(PlantFarmReason.NO_POLICY_COMPATIBLE_FARM);
    }

    static PlantFarmReason exactMetadataFailure(
            WaypointRecord record,
            List<FarmPlantingRequest> requests,
            FarmPlantingPolicy policy) {
        if (record == null) {
            return PlantFarmReason.EXACT_FARM_NOT_FOUND;
        }
        if (!WaypointTypes.FARM.equals(record.type)) {
            return PlantFarmReason.TYPE_CONFLICT;
        }
        if (record.stale) {
            return PlantFarmReason.STALE_FARM;
        }
        FarmWaypointData data = record.farmData();
        if (data == null || !data.isSupportedVersion()
                || data.radius() != FarmPlotPolicy.HYDRATION_RADIUS) {
            return PlantFarmReason.UNSUPPORTED_FARM_DATA;
        }
        if (policy.kind() == FarmPlantingPolicy.Kind.PRESERVE) {
            for (FarmPlantingRequest request : requests) {
                if (!data.acceptsPlantingItem(request.plantingItemId())) {
                    return PlantFarmReason.EXACT_FARM_POLICY_CONFLICT;
                }
            }
        }
        // Exact capacity is reconciled from the world by PlantFarmTask. A cached zero/shortfall may
        // already be obsolete after a player harvested or removed plants.
        return PlantFarmReason.NONE;
    }

    private static Selection failed(PlantFarmReason reason) {
        return new Selection(List.of(), reason);
    }
}
