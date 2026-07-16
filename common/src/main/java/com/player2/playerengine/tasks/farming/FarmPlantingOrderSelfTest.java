package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure deterministic retreat-order checks for mixed and tall crop planting. */
public final class FarmPlantingOrderSelfTest {
    private static final BlockPos CENTER = new BlockPos(20, 70, -10);

    private FarmPlantingOrderSelfTest() {
    }

    public static void runAll() {
        fullFarmRetreatsTowardSelectedEdge();
        inputOrderAndDuplicatesDoNotAffectOutput();
        exitSelectionAndBoundsAreDeterministic();
    }

    private static void fullFarmRetreatsTowardSelectedEdge() {
        List<BlockPos> open = FarmPlotGeometry.soilCells(CENTER).stream()
                .map(BlockPos::above)
                .toList();
        List<BlockPos> ordered = FarmPlantingOrder.retreatOrder(
                CENTER, open, Direction.NORTH);
        require(ordered.size() == FarmPlotPolicy.SOIL_CELL_COUNT,
                "retreat order contains every noncenter crop target exactly once");
        int previousDepth = Integer.MAX_VALUE;
        for (BlockPos target : ordered) {
            int depth = target.getZ() - (CENTER.getZ() - FarmPlotPolicy.HYDRATION_RADIUS);
            require(depth <= previousDepth, "planting depth never moves away from the exit");
            previousDepth = depth;
        }
        require(ordered.get(0).getZ() == CENTER.getZ() + FarmPlotPolicy.HYDRATION_RADIUS
                        && ordered.get(ordered.size() - 1).getZ()
                        == CENTER.getZ() - FarmPlotPolicy.HYDRATION_RADIUS,
                "far north-exit depth is planted first and the exit row is planted last");
    }

    private static void inputOrderAndDuplicatesDoNotAffectOutput() {
        ArrayList<BlockPos> shuffled = new ArrayList<>(FarmPlotGeometry.soilCells(CENTER).stream()
                .map(BlockPos::above)
                .toList());
        Collections.reverse(shuffled);
        shuffled.add(shuffled.get(0));
        List<BlockPos> first = FarmPlantingOrder.retreatOrder(CENTER, shuffled, Direction.EAST);
        Collections.rotate(shuffled, 17);
        List<BlockPos> second = FarmPlantingOrder.retreatOrder(CENTER, shuffled, Direction.EAST);
        require(first.equals(second) && first.size() == FarmPlotPolicy.SOIL_CELL_COUNT,
                "retreat order is independent of scan order and collapses duplicate targets");
    }

    private static void exitSelectionAndBoundsAreDeterministic() {
        require(FarmPlantingOrder.selectExitEdge(
                        CENTER, CENTER.offset(30, 1, 0)) == Direction.EAST
                        && FarmPlantingOrder.selectExitEdge(
                        CENTER, CENTER.offset(0, 1, -30)) == Direction.NORTH,
                "nearest cardinal edge follows the frozen approach anchor");
        require(FarmPlantingOrder.selectExitEdge(CENTER, CENTER.above()) == Direction.NORTH,
                "equal-distance exit selection uses stable north-first tie order");
        expectIllegal(() -> FarmPlantingOrder.retreatOrder(
                CENTER, List.of(CENTER.above()), Direction.NORTH),
                "center water column is never a crop target");
        expectIllegal(() -> FarmPlantingOrder.retreatOrder(
                CENTER, List.of(CENTER.offset(5, 1, 0)), Direction.NORTH),
                "outside target is rejected");
        expectIllegal(() -> FarmPlantingOrder.retreatOrder(
                CENTER, List.of(), Direction.UP),
                "vertical exit is rejected");
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
