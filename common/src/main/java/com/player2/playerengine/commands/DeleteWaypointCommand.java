package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code delete_waypoint <x> <y> <z>} — remove the EllieGPS record whose stored canonical or
 * secondary position equals {@code (x,y,z)} in the bot's current dimension (Part C5, WS5).
 *
 * <p>No navigation, no scan, works with the block absent. The only removal path for EllieGPS
 * records — never auto-deletes (Decision 8). Dual-audience: one player chat line +
 * {@code finishWithError}/{@code finish} for the model.
 */
public class DeleteWaypointCommand extends Command {

    public DeleteWaypointCommand() throws CommandException {
        super(
                "delete_waypoint",
                "delete_waypoint <x> <y> <z>. Removes the EllieGPS waypoint record whose stored"
                        + " canonical or secondary position equals (x,y,z) in the bot's current dimension."
                        + " Does not navigate; works even when the container block is gone. The only explicit"
                        + " delete path — EllieGPS never auto-deletes records (use after audit_waypoint"
                        + " reports stale).",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        // Guard 1: EllieGPS enabled
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            mod.reportAgenticProgress(WaypointReportFormatter.ellieGpsDisabledPlayer(), true);
            this.finishWithError(WaypointReportFormatter.ellieGpsDisabledModel());
            return;
        }

        // One-time operator note if the store was quarantined (Decision 14)
        EllieGPSStore quarantineStore = EllieGPSStore.get();
        if (quarantineStore != null && quarantineStore.consumeQuarantineNote()) {
            mod.reportAgenticProgress(WaypointReportFormatter.quarantineNote(), true);
        }

        // Guard 2: parse coordinates
        String[] u = parser.getArgUnits();
        if (u.length < 3) {
            fail(mod, StorageAccessCode.INVALID_ARGUMENT, "usage: delete_waypoint <x> <y> <z>");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(u[0].trim());
            y = Integer.parseInt(u[1].trim());
            z = Integer.parseInt(u[2].trim());
        } catch (NumberFormatException e) {
            fail(mod, StorageAccessCode.INVALID_ARGUMENT, "x y z must be integers");
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);

        // Guard 3: store available
        EllieGPSStore store = EllieGPSStore.get();
        if (store == null) {
            fail(mod, StorageAccessCode.CONTAINER_UNREACHABLE,
                    "EllieGPS store not available (no world loaded)");
            return;
        }

        // Dimension is the bot's current dimension (no cross-dimension addressing)
        ServerLevel level = mod.getWorld();
        if (level == null) {
            fail(mod, StorageAccessCode.CONTAINER_UNREACHABLE, "no world available");
            return;
        }
        String dimensionId = level.dimension().location().toString();
        String posStr = ContainerResolver.formatPos(pos);

        // Look up by position in current dimension (matches canonical OR secondary)
        WaypointRecord record = store.byPosition(dimensionId, pos);
        if (record == null) {
            String msg = WaypointReportFormatter.noWaypointAt(posStr, dimensionId);
            mod.reportAgenticProgress(msg, true);
            this.finishWithError(msg);
            return;
        }

        // Delete from store (the store persists and reindexes internally)
        String deletedId = record.id;
        store.delete(deletedId);

        String successMsg = WaypointReportFormatter.waypointDeleted(posStr, dimensionId);
        AiConversationFeedback.enqueueInfo(mod, successMsg);
        mod.reportAgenticProgress(successMsg, true);
        Debug.logMessage("waypoint-delete ok id=" + deletedId
                + " bot=" + mod.getEntity().getName().getString());
        this.finish();
    }

    /** Dual-audience failure (player chat + model finishWithError). */
    private void fail(PlayerEngineController mod, StorageAccessCode code, String detail) {
        Debug.logWarning("waypoint-delete fail code=" + code.token()
                + " detail=" + detail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress("couldn't delete waypoint - " + detail, true);
        this.finishWithError(code.token() + ": " + detail);
    }
}
