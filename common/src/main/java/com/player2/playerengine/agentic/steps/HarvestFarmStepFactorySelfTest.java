package com.player2.playerengine.agentic.steps;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** Server-free argument parsing checks for the harvest_farm agentic factory. */
public final class HarvestFarmStepFactorySelfTest {
    private HarvestFarmStepFactorySelfTest() {
    }

    public static void runAll() {
        require(HarvestFarmStepFactory.parseExplicitCenter(Map.of()).isEmpty(),
                "nearest harvest has no explicit center");
        require(HarvestFarmStepFactory.parseExplicitCenter(Map.of("x", "1", "y", "64")).isEmpty(),
                "partial harvest coordinates rejected");
        require(HarvestFarmStepFactory.parseExplicitCenter(
                        Map.of("x", "1", "y", "64", "z", "-2"))
                        .orElseThrow().equals(new BlockPos(1, 64, -2)),
                "exact harvest center parsed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
