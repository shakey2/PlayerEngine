package com.player2.playerengine.tasks.farming;

import java.util.List;

/** Deterministic bounded dual-audience planting feedback checks. */
public final class PlantFarmFeedbackSelfTest {
    private PlantFarmFeedbackSelfTest() {
    }

    public static void runAll() {
        PlantFarmOutcome noFarm = new PlantFarmOutcome(
                true,
                false,
                PlantFarmReason.NO_RECORDED_FARMS,
                PlantFarmPhase.RESOLVE,
                "minecraft:overworld",
                null,
                List.of(new FarmPlantingRequest("minecraft:carrot", 1)),
                List.of(),
                -1,
                null);
        String model = PlantFarmFeedback.model(noFarm);
        require(model.contains("no EllieGPS farm is recorded"),
                "model must be told no farm exists");
        require(model.contains("ask whether"),
                "model must ask before setting up a farm");
        require(model.length() <= FarmFeedback.MAX_MODEL_LENGTH,
                "model feedback must remain bounded");
        require(PlantFarmFeedback.reasonKey(PlantFarmReason.ALL_RECORDED_FARMS_FULL)
                        .endsWith("all_recorded_farms_full"),
                "full-farm reason must have its own localization key");

        expectIllegal(() -> new PlantFarmOutcome(
                        true,
                        false,
                        PlantFarmReason.INTERACTION_DENIED,
                        PlantFarmPhase.PLANT,
                        "minecraft:overworld",
                        null,
                        List.of(
                                new FarmPlantingRequest("minecraft:carrot", 1),
                                new FarmPlantingRequest("minecraft:wheat_seeds", 1)),
                        List.of(new FarmPlantingRequest("minecraft:carrot", 2)),
                        -1,
                        null),
                "equal aggregate totals must not hide a per-crop overcount");
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
