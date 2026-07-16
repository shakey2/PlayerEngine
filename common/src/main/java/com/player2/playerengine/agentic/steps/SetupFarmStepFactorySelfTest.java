package com.player2.playerengine.agentic.steps;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** Server-free parsing contract checks for the setup_farm agentic factory. */
public final class SetupFarmStepFactorySelfTest {
    private SetupFarmStepFactorySelfTest() {
    }

    public static void runAll() {
        require(SetupFarmStepFactory.parseExplicitCenter(Map.of()).isEmpty(),
                "automatic setup has no explicit center");
        require(SetupFarmStepFactory.parseExplicitCenter(Map.of("x", "1", "y", "64")).isEmpty(),
                "partial coordinates rejected");
        require(SetupFarmStepFactory.parseExplicitCenter(
                Map.of("x", "1", "y", "nope", "z", "2")).isEmpty(),
                "non-integer coordinates rejected");
        require(SetupFarmStepFactory.parseExplicitCenter(
                        Map.of("x", "1", "y", "64", "z", "-2"))
                        .orElseThrow()
                        .equals(new BlockPos(1, 64, -2)),
                "explicit center parsed exactly");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
