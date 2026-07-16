package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Deterministic typed farm preselection checks. */
public final class FarmPlantingSelectorSelfTest {
    private FarmPlantingSelectorSelfTest() {
    }

    public static void runAll() {
        List<FarmPlantingRequest> carrots = List.of(
                new FarmPlantingRequest("minecraft:carrot", 10));
        WaypointRecord farther = farm(new BlockPos(20, 64, 0), 20, null);
        WaypointRecord nearestFull = farm(new BlockPos(4, 64, 0), 0, null);
        FarmPlantingSelector.Selection selected = FarmPlantingSelector.automatic(
                List.of(farther, nearestFull),
                "minecraft:overworld",
                BlockPos.ZERO,
                carrots);
        require(selected.successful()
                        && nearestFull.id.equals(selected.candidates().get(0).id)
                        && farther.id.equals(selected.candidates().get(1).id),
                "cached capacity must not suppress nearest-first live reconciliation");

        FarmPlantingSelector.Selection full = FarmPlantingSelector.automatic(
                List.of(nearestFull), "minecraft:overworld", BlockPos.ZERO, carrots);
        require(full.successful() && full.candidates().size() == 1,
                "a cached-full farm must still be live-scanned before rejection");

        require(FarmPlantingSelector.exactMetadataFailure(
                        nearestFull, carrots, FarmPlantingPolicy.preserve())
                        == PlantFarmReason.NONE,
                "exact cached capacity must also defer to the live scan");

        WaypointRecord wheatOnly = farm(
                new BlockPos(2, 64, 0), 20, "minecraft:wheat_seeds");
        FarmPlantingSelector.Selection policy = FarmPlantingSelector.automatic(
                List.of(wheatOnly), "minecraft:overworld", BlockPos.ZERO, carrots);
        require(policy.failure() == PlantFarmReason.NO_POLICY_COMPATIBLE_FARM,
                "planting restriction conflict needs a distinct outcome");
    }

    private static WaypointRecord farm(BlockPos center, int open, String restriction) {
        WaypointRecord record = new WaypointRecord();
        record.id = WaypointRecord.idFor("minecraft:overworld", center);
        record.type = WaypointTypes.FARM;
        record.dimension = "minecraft:overworld";
        record.pos = new int[]{center.getX(), center.getY(), center.getZ()};
        record.data = new FarmWaypointData(4, 80, open, List.of(), restriction);
        return record;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
