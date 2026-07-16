package com.player2.playerengine.tasks.container;

import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/** Pure contract checks for acquisition-only UP_TO withdrawals. */
public final class BoundedContainerTransferTaskSelfTest {
    private BoundedContainerTransferTaskSelfTest() {
    }

    public static void runAll() {
        require(BoundedContainerTransferTask.upToWithdrawAmount(3, 8, 20) == 3,
                "container availability caps UP_TO");
        require(BoundedContainerTransferTask.upToWithdrawAmount(20, 8, 20) == 8,
                "requested maximum caps UP_TO");
        require(BoundedContainerTransferTask.upToWithdrawAmount(20, 8, 2) == 2,
                "inventory capacity caps UP_TO");

        BoundedContainerTransferTask first =
                BoundedContainerTransferTask.withdrawUpTo(
                        BlockPos.ZERO, Items.CARROT, 8);
        BoundedContainerTransferTask same =
                BoundedContainerTransferTask.withdrawUpTo(
                        BlockPos.ZERO, Items.CARROT, 8);
        BoundedContainerTransferTask exact = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW,
                BlockPos.ZERO,
                List.of(new StorageItemArgs.ItemQuery(Items.CARROT, "carrot", 8)));
        require(first.equals(same), "equal UP_TO tasks retain stable task identity");
        require(!first.equals(exact), "UP_TO never aliases command-exact task identity");
        expectThrows(() -> BoundedContainerTransferTask.withdrawUpTo(
                        BlockPos.ZERO, Items.CARROT, 0),
                "zero is not a legal UP_TO maximum");
    }

    private static void expectThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(
                "Bounded transfer self-test failed: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Bounded transfer self-test failed: " + message);
        }
    }
}
