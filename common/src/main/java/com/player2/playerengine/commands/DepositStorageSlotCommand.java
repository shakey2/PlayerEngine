package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ResolvedContainer;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.container.SlotPreciseTransactionTask;
import com.player2.playerengine.util.Debug;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * {@code deposit_storage_slot <x> <y> <z> <botSlot> <count> [containerSlot]} — slot-precise
 * deposit: put exactly {@code count} items from ONE bot main inventory slot (0-35) into the
 * container at the given position, optionally into a specific container slot (deep-scan
 * indexing; double chests are one 0-53 space; omitted = auto-place into the first
 * stackable-or-empty slot) — Part C4.5, WS4.
 *
 * <p>{@code call()} only parses args and runs the travel-cap PRECHECK; the session (navigate,
 * lid animation, validate-then-transfer in one server tick, hold, guarded close, cache refresh)
 * lives in {@link SlotPreciseTransactionTask} and the typed outcome is mapped to the finish
 * paths inside the {@code runUserTask} callback. An occupied destination only merges with the
 * exact same stack; a mismatch or no-room destination is {@code slot_occupied} and nothing
 * moves. Every failure reaches the model, the player
 * ({@code reportAgenticProgress(msg, true)}), and the server log — Decision 5.
 */
public class DepositStorageSlotCommand extends Command {

    public DepositStorageSlotCommand() throws CommandException {
        super(
                "deposit_storage_slot",
                "deposit_storage_slot <x> <y> <z> <botSlot> <count> [containerSlot]. Put exactly count"
                        + " items from your inventory slot botSlot (0-35) into the container at x y z."
                        + " containerSlot (slot indices match deep scan_storage output; a double chest is"
                        + " one 0-53 space) targets a specific container slot; omit it to auto-place into"
                        + " the first stackable-or-empty slot."
                        + " Example: deposit_storage_slot 120 64 -33 4 32 9.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"),
                new Arg<>(Integer.class, "botSlot"),
                new Arg<>(Integer.class, "count"),
                new Arg<>(Integer.class, "containerSlot", SlotPreciseTransactionTask.SLOT_AUTO, 5, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String[] u = parser.getArgUnits();
        if (u == null || u.length < 5) {
            failNow(mod, null, StorageAccessCode.INVALID_ARGUMENT,
                    "expected <x> <y> <z> <botSlot> <count> [containerSlot]; usage: "
                            + this.getHelpRepresentation(), "?");
            return;
        }
        int x;
        int y;
        int z;
        int botSlot;
        int count;
        int containerSlot = SlotPreciseTransactionTask.SLOT_AUTO;
        try {
            x = Integer.parseInt(u[0].trim());
            y = Integer.parseInt(u[1].trim());
            z = Integer.parseInt(u[2].trim());
            botSlot = Integer.parseInt(u[3].trim());
            count = Integer.parseInt(u[4].trim());
            if (u.length > 5) {
                containerSlot = Integer.parseInt(u[5].trim());
            }
        } catch (NumberFormatException e) {
            failNow(mod, null, StorageAccessCode.INVALID_ARGUMENT,
                    "all arguments must be integers; usage: " + this.getHelpRepresentation(), "?");
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        String reqSummary = "botSlot:" + botSlot + ",count:" + count
                + (containerSlot != SlotPreciseTransactionTask.SLOT_AUTO ? ",slot:" + containerSlot : "");

        // Argument gates fire before any movement.
        if (count < 1 || count > StorageItemArgs.MAX_COUNT) {
            failNow(mod, pos, StorageAccessCode.INVALID_COUNT,
                    "count must be 1.." + StorageItemArgs.MAX_COUNT + ", got \"" + u[4].trim() + "\"",
                    reqSummary);
            return;
        }
        if (botSlot < 0 || botSlot > SlotPreciseTransactionTask.MAX_BOT_SLOT) {
            failNow(mod, pos, StorageAccessCode.INVALID_SLOT,
                    "bot inventory has slots 0-" + SlotPreciseTransactionTask.MAX_BOT_SLOT
                            + " (got " + botSlot + ")", reqSummary);
            return;
        }
        if (containerSlot != SlotPreciseTransactionTask.SLOT_AUTO && containerSlot < 0) {
            failNow(mod, pos, StorageAccessCode.INVALID_SLOT,
                    "containerSlot must be >= 0 (got " + containerSlot + ")", reqSummary);
            return;
        }

        // PRECHECK: travel cap — beyond it no navigation ever starts.
        double cap = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = mod.getEntity().position()
                .distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > cap * cap) {
            failNow(mod, pos, StorageAccessCode.CONTAINER_TOO_FAR,
                    "target " + ContainerResolver.formatPos(pos) + " is " + (int) Math.sqrt(distSq)
                            + " blocks away, beyond the " + (int) cap + "-block storage travel cap",
                    reqSummary);
            return;
        }

        SlotPreciseTransactionTask task = new SlotPreciseTransactionTask(
                ScanReportFormatter.TransferDirection.DEPOSIT, pos, containerSlot, botSlot, count);
        mod.runUserTask(task, () -> handleOutcome(mod, pos, reqSummary, task));
    }

    /** Maps the task's typed outcome to the finish paths (runs inside the task callback). */
    private void handleOutcome(
            PlayerEngineController mod, BlockPos pos, String reqSummary, SlotPreciseTransactionTask task) {
        if (task.outcome() == SlotPreciseTransactionTask.Outcome.COMPLETED) {
            ResolvedContainer rc = task.resolvedContainer();
            String receipt = ScanReportFormatter.slotReceipt(
                    ScanReportFormatter.TransferDirection.DEPOSIT, rc, task.containerSlotUsed(),
                    task.displayId(), task.movedCount(), task.botSlotUsed());
            mod.reportAgenticProgress(
                    Component.translatable("message.playerengine.storage.deposit_slot_ok",
                            String.valueOf(task.movedCount()), task.displayId().replace('_', ' '),
                            String.valueOf(task.containerSlotUsed()), humanKind(rc), humanPos(pos)),
                    true);
            Debug.logMessage("storage-tx ok dir=deposit_slot pos=" + ContainerResolver.formatPos(pos)
                    + " kind=" + kindToken(rc) + " slot=" + task.containerSlotUsed()
                    + " moved=" + task.displayId() + ":" + task.movedCount() + " bot=" + botName(mod));
            AiConversationFeedback.enqueueInfo(mod, receipt);
            this.finish();
        } else {
            reportFailure(mod, pos, task.resolvedContainer(), task.failures(), reqSummary);
        }
    }

    /** Pre-task failure exit (argument gates and PRECHECK). */
    private void failNow(PlayerEngineController mod, BlockPos pos, StorageAccessCode code,
                         String detail, String reqSummary) {
        reportFailure(mod, pos, null,
                List.of(new ScanReportFormatter.EntryFailure(code, "", detail)), reqSummary);
    }

    private void reportFailure(
            PlayerEngineController mod, BlockPos pos, ResolvedContainer rc,
            List<ScanReportFormatter.EntryFailure> failures, String reqSummary) {
        if (failures.isEmpty()) {
            // Defensive: an interrupted session can end without a recorded failure entry.
            failures = List.of(new ScanReportFormatter.EntryFailure(
                    StorageAccessCode.CONTAINER_UNREACHABLE, "",
                    "transaction ended without a result (session interrupted)"));
        }
        String text = ScanReportFormatter.transferFailure(failures);
        String where = pos == null ? "storage" : "the " + humanKind(rc) + " at " + humanPos(pos);
        mod.reportAgenticProgress(
                Component.translatable("message.playerengine.storage.deposit_fail",
                        where, failures.get(0).detail()),
                true);
        Debug.logWarning("storage-tx fail code=" + failures.get(0).code().token()
                + " pos=" + (pos == null ? "?" : ContainerResolver.formatPos(pos))
                + " kind=" + kindToken(rc)
                + " req=" + reqSummary
                + " detail=\"" + text + "\" bot=" + botName(mod));
        this.finishWithError(text);
    }

    // ------------------------------------------------------------------ rendering helpers

    private static String humanKind(ResolvedContainer rc) {
        return rc == null ? "container" : rc.kind().token().replace('_', ' ');
    }

    private static String kindToken(ResolvedContainer rc) {
        return rc == null ? "?" : rc.kind().token();
    }

    private static String humanPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String botName(PlayerEngineController mod) {
        return mod.getEntity().getName().getString();
    }
}
