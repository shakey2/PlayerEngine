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
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.util.Debug;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * {@code withdraw_from_storage <x> <y> <z> <items...>} — precise, validation-first withdrawal
 * from the container at the given position into the bot's inventory (Part C4.5, WS4).
 *
 * <p>The items tail uses the {@link StorageItemArgs} grammar (Decision 11): entries separated by
 * {@code ","}, each {@code <item>} or {@code <item> <count>}; an omitted count means all
 * available (best-effort per entry). Parsed inside {@code call()} from the raw arg-unit tail —
 * never the legacy bracket-list parser — so every name/count error reaches the model through
 * {@code finishWithError} with the {@link StorageAccessCode} vocabulary, enumerating EVERY
 * failing entry, before the bot moves.
 *
 * <p>{@code call()} only parses args and runs the travel-cap PRECHECK; the session logic lives
 * in {@link BoundedContainerTransferTask} and the typed outcome is mapped to the finish paths
 * inside the {@code runUserTask} callback (the established command-&gt;task funnel). Every
 * failure and degradation reaches the model (formatter-rendered text), the player (exactly one
 * {@code reportAgenticProgress(msg, true)} call), and the server log (structured
 * {@code storage-tx} one-liner) — Decision 5.
 */
public class WithdrawFromStorageCommand extends Command {

    public WithdrawFromStorageCommand() throws CommandException {
        super(
                "withdraw_from_storage",
                "withdraw_from_storage <x> <y> <z> <items...>. Take items from the container at x y z"
                        + " (chest/double chest/barrel/shulker) into your inventory. items: entries separated"
                        + " by \",\", each \"<item>\" or \"<item> <count>\"; omit the count to take all"
                        + " available. Example: withdraw_from_storage 120 64 -33 iron_ingot 40, coal."
                        + " Explicit counts are atomic: if any cannot be fully satisfied, nothing moves and"
                        + " the error names the live amount.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"),
                new Arg<>(String.class, "items", "", 3, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String[] u = parser.getArgUnits();
        if (u == null || u.length < 4) {
            failBeforeTask(mod, null,
                    List.of(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.INVALID_ARGUMENT, "",
                            "expected <x> <y> <z> <items...>; usage: " + this.getHelpRepresentation())),
                    "");
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(u[0].trim());
            y = Integer.parseInt(u[1].trim());
            z = Integer.parseInt(u[2].trim());
        } catch (NumberFormatException e) {
            failBeforeTask(mod, null,
                    List.of(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.INVALID_ARGUMENT, "",
                            "x y z must be integers; usage: " + this.getHelpRepresentation())),
                    "");
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);

        String[] tail = Arrays.copyOfRange(u, 3, u.length);
        StorageItemArgs.ParseResult parsed = StorageItemArgs.parse(tail);
        if (!parsed.ok()) {
            // Parse phase: ONE error enumerating EVERY failing entry, command order; the bot
            // never moves.
            List<ScanReportFormatter.EntryFailure> entryFailures = new ArrayList<>();
            for (StorageItemArgs.ParseFailure f : parsed.failures()) {
                entryFailures.add(new ScanReportFormatter.EntryFailure(f.code(), f.token(), f.detail()));
            }
            failBeforeTask(mod, pos, entryFailures, String.join(" ", tail));
            return;
        }
        String reqSummary = summarizeRequest(parsed.queries());

        // PRECHECK: travel cap — beyond it no navigation ever starts.
        double cap = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = mod.getEntity().position()
                .distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > cap * cap) {
            failBeforeTask(mod, pos,
                    List.of(new ScanReportFormatter.EntryFailure(
                            StorageAccessCode.CONTAINER_TOO_FAR, "",
                            "target " + ContainerResolver.formatPos(pos) + " is " + (int) Math.sqrt(distSq)
                                    + " blocks away, beyond the " + (int) cap + "-block storage travel cap")),
                    reqSummary);
            return;
        }

        BoundedContainerTransferTask task = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW, pos, parsed.queries());
        mod.runUserTask(task, () -> handleOutcome(mod, pos, reqSummary, task));
    }

    /** Maps the task's typed outcome to the finish paths (runs inside the task callback). */
    private void handleOutcome(
            PlayerEngineController mod, BlockPos pos, String reqSummary, BoundedContainerTransferTask task) {
        BoundedContainerTransferTask.Outcome outcome = task.outcome();
        ResolvedContainer rc = task.resolvedContainer();
        if (outcome == BoundedContainerTransferTask.Outcome.MOVED_ALL) {
            String receipt = ScanReportFormatter.transferReceipt(
                    ScanReportFormatter.TransferDirection.WITHDRAW, rc, task.entryOutcomes());
            mod.reportAgenticProgress(
                    Component.translatable("message.playerengine.storage.withdraw_ok",
                            humanItems(task.entryOutcomes()), humanKind(rc), humanPos(pos)),
                    true);
            Debug.logMessage("storage-tx ok dir=withdraw pos=" + ContainerResolver.formatPos(pos)
                    + " kind=" + kindToken(rc) + " moved=" + movedSummary(task.entryOutcomes())
                    + " bot=" + botName(mod));
            AiConversationFeedback.enqueueInfo(mod, receipt);
            this.finish();
        } else if (outcome == BoundedContainerTransferTask.Outcome.MOVED_PARTIAL) {
            String receipt = ScanReportFormatter.transferReceipt(
                    ScanReportFormatter.TransferDirection.WITHDRAW, rc, task.entryOutcomes());
            mod.reportAgenticProgress(
                    Component.translatable("message.playerengine.storage.withdraw_partial",
                            humanItems(task.entryOutcomes()), humanKind(rc), humanPos(pos),
                            firstShortReason(task.entryOutcomes())),
                    true);
            Debug.logMessage("storage-tx partial dir=withdraw pos=" + ContainerResolver.formatPos(pos)
                    + " kind=" + kindToken(rc) + " code=" + task.code().token()
                    + " moved=" + movedSummary(task.entryOutcomes()) + " bot=" + botName(mod));
            // Degraded success: the receipt rides the note so the model sees per-entry truth.
            this.finishWithNote(receipt);
        } else {
            // NOTHING and FAILED both end via the error path with per-entry live numbers.
            reportFailure(mod, pos, rc, task.failures(), reqSummary);
        }
    }

    /** Failure exit shared by the pre-task gates and the task callback (Decision 5: model error
     * text + one player line + structured WARN log). */
    private void failBeforeTask(
            PlayerEngineController mod, BlockPos pos,
            List<ScanReportFormatter.EntryFailure> failures, String reqSummary) {
        reportFailure(mod, pos, null, failures, reqSummary);
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
                Component.translatable("message.playerengine.storage.withdraw_fail",
                        where, failures.get(0).detail()),
                true);
        Debug.logWarning("storage-tx fail code=" + failures.get(0).code().token()
                + " pos=" + (pos == null ? "?" : ContainerResolver.formatPos(pos))
                + " kind=" + kindToken(rc)
                + " req=" + (reqSummary.isEmpty() ? "?" : reqSummary)
                + " detail=\"" + text + "\" bot=" + botName(mod));
        this.finishWithError(text);
    }

    // ------------------------------------------------------------------ rendering helpers

    private static String summarizeRequest(List<StorageItemArgs.ItemQuery> queries) {
        StringBuilder sb = new StringBuilder();
        for (StorageItemArgs.ItemQuery q : queries) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(q.displayId()).append(":").append(q.isAll() ? "all" : q.countOrAll());
        }
        return sb.toString();
    }

    private static String movedSummary(List<ScanReportFormatter.EntryOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (ScanReportFormatter.EntryOutcome eo : outcomes) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(eo.displayId()).append(":").append(eo.moved());
        }
        return sb.toString();
    }

    /** Human item list for the player chat line, e.g. {@code "40 iron ingot, 12 coal"}. */
    private static String humanItems(List<ScanReportFormatter.EntryOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (ScanReportFormatter.EntryOutcome eo : outcomes) {
            if (eo.moved() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(eo.moved()).append(" ").append(eo.displayId().replace('_', ' '));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String firstShortReason(List<ScanReportFormatter.EntryOutcome> outcomes) {
        for (ScanReportFormatter.EntryOutcome eo : outcomes) {
            if (!eo.shortReason().isEmpty()) {
                return eo.shortReason();
            }
        }
        return "partial";
    }

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
