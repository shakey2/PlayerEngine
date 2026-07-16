package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Pure deterministic checks for exact scanner open-capacity semantics. */
public final class FarmWaypointCapacitySelfTest {
    private FarmWaypointCapacitySelfTest() {
    }

    public static void runAll() {
        require(FarmCropScanner.isOpenCell(
                        Blocks.FARMLAND.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                "farmland with air above must be open");
        require(!FarmCropScanner.isOpenCell(
                        Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState()),
                "air above damaged non-farmland soil must not be open");
        require(!FarmCropScanner.isOpenCell(
                        Blocks.FARMLAND.defaultBlockState(), Blocks.WHEAT.defaultBlockState()),
                "recognized crop occupant must not be open");
        require(!FarmCropScanner.isOpenCell(
                        Blocks.FARMLAND.defaultBlockState(), Blocks.MELON_STEM.defaultBlockState()),
                "unsupported stem occupant must not be open");
        require(!FarmCropScanner.isOpenCell(
                        Blocks.FARMLAND.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()),
                "solid obstruction must not be open");

        ArrayList<BlockPos> supplied = new ArrayList<>(List.of(new BlockPos(1, 65, 1)));
        FarmCropScanner.ScanResult result = new FarmCropScanner.ScanResult(
                new FarmObservationResult(
                        FarmObservationStatus.HANDLER_UNAVAILABLE, null, "test"),
                List.of(),
                supplied);
        supplied.clear();
        require(result.openCount() == 1 && result.openCells().size() == 1,
                "scan result did not defensively retain open cells");
        boolean immutable = false;
        try {
            result.openCells().add(new BlockPos(2, 65, 2));
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        require(immutable, "scan result exposed a mutable open-cell list");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
