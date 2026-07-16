package com.player2.playerengine.tasks.agentic;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Pure deterministic ordering checks for the generic marked-item locator. */
public final class MarkedChestItemLocatorSelfTest {
    private MarkedChestItemLocatorSelfTest() {
    }

    public static void runAll() {
        ArrayList<BlockPos> positions = new ArrayList<>(List.of(
                new BlockPos(2, 0, 0),
                new BlockPos(0, 0, 2),
                new BlockPos(-1, 0, 0),
                new BlockPos(1, 0, 0)));
        positions.sort(MarkedChestItemLocator.positionComparator(Vec3.ZERO));
        require(positions.equals(List.of(
                        new BlockPos(-1, 0, 0),
                        new BlockPos(1, 0, 0),
                        new BlockPos(0, 0, 2),
                        new BlockPos(2, 0, 0))),
                "distance ties use stable XYZ ordering");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Marked chest item locator self-test failed: " + message);
        }
    }
}
