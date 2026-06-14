package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ScanMode;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.container.ScanContainerTask;
import com.player2.playerengine.util.Debug;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * {@code scan_storage <x> <y> <z> <light|deep|targeted> [items…]} — the read-only fresh scan
 * command (Part C4.5, WS3). {@code call()} only parses args and runs the travel-cap PRECHECK;
 * the session work lives in {@link ScanContainerTask}, started via {@code runUserTask} with the
 * outcome mapped inside the callback (the {@code DepositCommand} precedent).
 *
 * <p>The item tail is declared as an optional String arg but re-parsed in {@code call()} from
 * the raw {@code parser.getArgUnits()} tail through {@link StorageItemArgs} (the
 * {@code PlaceSignCommand} tail-parse precedent; Decision 11) — so every name/count error
 * reaches the model as a {@code StorageAccessCode}-token {@code finishWithError}, never a
 * {@code CommandException}.
 *
 * <p>Dual-audience rule (Decision 5): every failure produces the model error text, ONE player
 * chat line via {@code reportAgenticProgress(msg, true)}, and a WARN {@code storage-scan} log
 * record. Successful scans stay silent in chat (payload via
 * {@link AiConversationFeedback#enqueueInfo} only) to avoid spam.
 */
public class ScanStorageCommand extends Command {

    public ScanStorageCommand() throws CommandException {
        super(
                "scan_storage",
                "scan_storage <x> <y> <z> <light|deep|targeted> [items…]. Fresh-reads the live contents of a"
                        + " storage container (chest, double chest, barrel, shulker box) at the given block"
                        + " coordinates — read-only, moves nothing. Modes: light = per-item totals + empty-slot"
                        + " count (cheapest; prefer first); deep = every occupied slot with its slot index (large"
                        + " output; only when exact slot positions are needed); targeted = live counts for"
                        + " specific items only, e.g. scan_storage 120 64 -33 targeted iron_ingot 60, oak_planks."
                        + " Items are registry ids (namespace optional), comma-separated, with an optional"
                        + " wanted count after the name.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"),
                new Arg<>(String.class, "mode"),
                new Arg<>(String.class, "items", "", 4, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String[] u = parser.getArgUnits();
        if (u.length < 4) {
            failBeforeTarget(mod, StorageAccessCode.INVALID_ARGUMENT,
                    "usage: scan_storage <x> <y> <z> <light|deep|targeted> [items]");
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
            failBeforeTarget(mod, StorageAccessCode.INVALID_ARGUMENT, "x y z must be integers");
            return;
        }
        // Coordinates are interpreted in the bot's current level/dimension (every coordinate-
        // taking command's convention; no cross-dimension addressing in C4.5).
        BlockPos pos = new BlockPos(x, y, z);

        ScanMode mode = ScanMode.fromToken(u[3]);
        if (mode == null) {
            failBeforeTarget(mod, StorageAccessCode.INVALID_ARGUMENT, "mode must be light, deep or targeted");
            return;
        }

        // Decision 11: the tail is re-parsed from the raw arg units (join-then-split lives in
        // StorageItemArgs.parse), never through the legacy bracket-list parser.
        String[] tailUnits = Arrays.copyOfRange(u, 4, u.length);
        List<StorageItemArgs.ItemQuery> targets = List.of();
        if (mode == ScanMode.TARGETED) {
            if (tailUnits.length == 0) {
                failBeforeTarget(mod, StorageAccessCode.INVALID_ARGUMENT, "targeted mode requires item names");
                return;
            }
            StorageItemArgs.ParseResult parsed = StorageItemArgs.parse(tailUnits);
            if (!parsed.ok()) {
                // One finishWithError enumerating EVERY failing entry in command order
                // (multi-entry semantics, parse phase), rendered by the frozen grammar.
                List<ScanReportFormatter.EntryFailure> entryFailures = new ArrayList<>();
                for (StorageItemArgs.ParseFailure failure : parsed.failures()) {
                    entryFailures.add(new ScanReportFormatter.EntryFailure(
                            failure.code(), failure.token(), failure.detail()));
                }
                String errorText = ScanReportFormatter.transferFailure(entryFailures);
                logScanFail(mod, "args", errorText);
                mod.reportAgenticProgress("couldn't scan storage - " + errorText, true);
                this.finishWithError(errorText);
                return;
            }
            targets = parsed.queries();
        } else if (tailUnits.length > 0) {
            failBeforeTarget(mod, StorageAccessCode.INVALID_ARGUMENT,
                    mode.token() + " mode takes no item arguments");
            return;
        }

        // PRECHECK (before the task starts): beyond the travel cap -> container_too_far, the
        // bot never moves and no navigation starts.
        Vec3 origin = mod.getEntity().position();
        double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = origin.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
        if (distSq > capSq) {
            failAtTarget(mod, pos, mode, StorageAccessCode.CONTAINER_TOO_FAR,
                    ContainerResolver.formatPos(pos) + " is about " + (int) Math.sqrt(distSq)
                            + " blocks away, beyond the " + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS
                            + "-block travel cap");
            return;
        }

        // Session work in the task; outcome mapped INSIDE the callback (DepositCommand precedent).
        ScanMode finalMode = mode;
        List<StorageItemArgs.ItemQuery> finalTargets = targets;
        ScanContainerTask task = new ScanContainerTask(pos, mode, targets);
        mod.runUserTask(task, () -> {
            if (task.result().isPresent()) {
                // Success: payload to the model only; player chat stays silent (Decision 5).
                AiConversationFeedback.enqueueInfo(
                        mod, ScanReportFormatter.scanReport(finalMode, task.result().get(), finalTargets));
                Debug.logMessage("storage-scan ok mode=" + finalMode.token()
                        + " pos=" + ContainerResolver.formatPos(pos)
                        + " kind=" + task.result().get().kind().token()
                        + " bot=" + botName(mod));
                this.finish();
            } else {
                ScanContainerTask.Failure failure = task.failure().orElse(new ScanContainerTask.Failure(
                        StorageAccessCode.CONTAINER_UNREACHABLE, "scan session ended without a result"));
                failAtTarget(mod, pos, finalMode, failure.code(), failure.detail());
            }
        });
    }

    /** Dual-audience failure before a target pos is even known (arg-shape problems). */
    private void failBeforeTarget(PlayerEngineController mod, StorageAccessCode code, String detail) {
        logScanFail(mod, "code=" + code.token(), detail);
        mod.reportAgenticProgress("couldn't scan storage - " + detail, true);
        this.finishWithError(code.token() + ": " + detail);
    }

    /** Dual-audience failure naming the target container (PRECHECK + session failures). */
    private void failAtTarget(
            PlayerEngineController mod, BlockPos pos, ScanMode mode, StorageAccessCode code, String detail) {
        logScanFail(mod, "code=" + code.token() + " pos=" + ContainerResolver.formatPos(pos)
                + " mode=" + mode.token(), detail);
        mod.reportAgenticProgress(
                "couldn't scan the container at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " - " + detail,
                true);
        this.finishWithError(code.token() + ": " + detail);
    }

    /** The structured WARN one-liner (the storage-scan log record of Decision 5). */
    private static void logScanFail(PlayerEngineController mod, String fields, String detail) {
        Debug.logWarning("storage-scan fail " + fields + " detail=" + detail + " bot=" + botName(mod));
    }

    private static String botName(PlayerEngineController mod) {
        return mod.getEntity().getName().getString();
    }
}
