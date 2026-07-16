package com.player2.playerengine.commands;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Deterministic pure checks for the bounded rotating scanner window. */
public final class BlockScannerWindowSelfTest {
    private BlockScannerWindowSelfTest() {
    }

    public static void run() {
        BlockPos one = new BlockPos(1, 64, 0);
        BlockPos two = new BlockPos(2, 64, 0);
        BlockPos three = new BlockPos(3, 64, 0);
        BlockPos four = new BlockPos(4, 64, 0);
        BlockPos five = new BlockPos(5, 64, 0);
        List<Collection<BlockPos>> groups = List.of(
                List.of(three, one, two),
                List.of(five, four));

        BlockScanner.KnownLocationWindow first = BlockScanner.boundedWindow(
                0, 2, 5, groups, ignored -> false);
        require(first.locations().equals(List.of(three, one)),
                "first window retains the scanner snapshot order");
        require(first.nextCursor() == 2 && first.visited() == 2,
                "first window advances by its bounded visit count");

        BlockScanner.KnownLocationWindow second = BlockScanner.boundedWindow(
                first.nextCursor(), 3, 5, groups, ignored -> false);
        require(second.locations().equals(List.of(two, five, four)),
                "second window reaches entries beyond the first prefix");
        require(second.nextCursor() == 0 && second.visited() == 3,
                "full traversal wraps the durable cursor");

        BlockScanner.KnownLocationWindow wrapped = BlockScanner.boundedWindow(
                4, 3, 5, groups, ignored -> false);
        require(wrapped.locations().equals(List.of(four, three, one)),
                "one bounded window wraps without repeating an entry");
        require(wrapped.nextCursor() == 2 && wrapped.visited() == 3,
                "wrapped cursor identifies the next unvisited entry");

        BlockScanner.KnownLocationWindow unreachable = BlockScanner.boundedWindow(
                0, 2, 5, groups, Set.of(three)::contains);
        require(unreachable.locations().equals(List.of(one)),
                "unreachable positions are omitted");
        require(unreachable.nextCursor() == 2 && unreachable.visited() == 2,
                "unreachable positions still consume cursor and visit budget");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("BlockScanner window self-test failed: " + message);
        }
    }
}
