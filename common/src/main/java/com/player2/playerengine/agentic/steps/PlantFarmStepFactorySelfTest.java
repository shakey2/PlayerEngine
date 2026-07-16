package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.tasks.farming.FarmPlantingRequestParser;

import java.util.Map;

/** Pure argument contract checks for the planting step factory. */
public final class PlantFarmStepFactorySelfTest {
    private PlantFarmStepFactorySelfTest() {
    }

    public static void runAll() {
        require(FarmPlantingRequestParser.parseArgs(Map.of(
                "requests", "minecraft:carrot=23,minecraft:wheat_seeds=10")).valid(),
                "ordered nearest-farm request should validate");
        require(FarmPlantingRequestParser.parseArgs(Map.of(
                "requests", "minecraft:carrot=23",
                "x", "10", "y", "64", "z", "20",
                "farm_policy", "single:minecraft:carrot")).valid(),
                "exact single-crop policy should validate");
        require(!FarmPlantingRequestParser.parseArgs(Map.of(
                "requests", "minecraft:carrot=23",
                "farm_policy", "mixed")).valid(),
                "automatic farm policy mutation must fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
