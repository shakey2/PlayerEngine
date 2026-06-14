package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.EllieGPSWaypointIndex;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code locate_waypoints <query terms...>} — query the EllieGPS index (current dimension only)
 * and return up to 3 hits as formatted lines (Part C5, WS5).
 *
 * <p>This command is the retrieval surface without which EllieGPS would be write-only
 * (Decision 1 "Deliberate scope addition"). No navigation, no scan — instant index query.
 *
 * <p>Output: up to 3 {@code (x,y,z) kind: description [stale]} lines, or an honest
 * "no waypoints match" via {@code finishWithNote} when empty.
 */
public class LocateWaypointsCommand extends Command {

    /** Maximum number of locate hits to return. */
    private static final int MAX_HITS = 3;

    public LocateWaypointsCommand() throws CommandException {
        super(
                "locate_waypoints",
                "locate_waypoints <query terms...>. Queries the EllieGPS waypoint index for storage"
                        + " containers matching the given terms (e.g. 'iron ingot', 'food', 'tools')."
                        + " Returns up to 3 matching waypoints in the current dimension with their position,"
                        + " container kind, and description. Stale waypoints are shown with a [stale] marker."
                        + " Takes no coordinates — pure keyword retrieval.",
                new Arg<>(String.class, "query", "", 0, false));
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

        // Guard 2: query terms — join all arg units into a single query string
        String[] u = parser.getArgUnits();
        String query = (u.length > 0) ? String.join(" ", u).trim() : "";
        if (query.isEmpty()) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT,
                    "usage: locate_waypoints <query terms...>");
            return;
        }

        // Guard 3: store + index available
        EllieGPSStore store = EllieGPSStore.get();
        if (store == null) {
            failEarly(mod, StorageAccessCode.CONTAINER_UNREACHABLE,
                    "EllieGPS store not available (no world loaded)");
            return;
        }
        ServerLevel level = mod.getWorld();
        if (level == null) {
            failEarly(mod, StorageAccessCode.CONTAINER_UNREACHABLE, "no world available");
            return;
        }
        String dimensionId = level.dimension().location().toString();

        EllieGPSWaypointIndex index = EllieGPSWaypointIndex.getCurrent();
        if (index == null) {
            // Index not yet built — return honest empty result, not an error
            String msg = WaypointReportFormatter.noWaypointsMatch();
            mod.reportAgenticProgress(msg, true);
            this.finishWithNote(msg);
            return;
        }

        // Query the index for candidates (retrieval hit toolId == waypoint id)
        List<RetrievalHit> hits = index.query(query, MAX_HITS * 3);

        // Filter to current dimension; collect top-MAX_HITS hits
        List<WaypointRecord> results = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            if (results.size() >= MAX_HITS) break;
            // Look up the record by id in the store
            WaypointRecord record = findById(store, hit.toolId());
            if (record == null) continue;
            if (!dimensionId.equals(record.dimension)) continue;
            results.add(record);
        }

        if (results.isEmpty()) {
            String msg = WaypointReportFormatter.noWaypointsMatch();
            mod.reportAgenticProgress(msg, true);
            this.finishWithNote(msg);
            return;
        }

        // Format hit lines
        List<String> lines = new ArrayList<>();
        for (WaypointRecord record : results) {
            BlockPos canon = record.canonicalBlockPos();
            String posStr = (canon != null) ? ContainerResolver.formatPos(canon) : "(??,??,??)";
            String kind = "inventory";
            InventoryWaypointData invData = record.inventoryData();
            if (invData != null && invData.containerKind != null && !invData.containerKind.isEmpty()) {
                kind = invData.containerKind;
            }
            String description = (record.description != null) ? record.description : "";
            lines.add(WaypointReportFormatter.locateHitLine(posStr, kind, description, record.stale));
        }

        String payload = String.join("\n", lines);
        AiConversationFeedback.enqueueInfo(mod, payload);
        // Locate results are model-only (like scan_storage success); no milestone chat spam.
        // But we do send a brief player note per the dual-audience rule (informational, not
        // milestone): one condensed line so the player knows the command ran.
        mod.reportAgenticProgress("EllieGPS: found " + results.size()
                + " waypoint(s) matching \"" + query + "\"", true);
        Debug.logMessage("waypoint-locate ok query=\"" + query + "\" hits=" + results.size()
                + " dimension=" + dimensionId
                + " bot=" + mod.getEntity().getName().getString());
        this.finish();
    }

    /**
     * Finds a waypoint record by its exact id. Iterates the store's published snapshot so this
     * is safe from any thread, but for locateWaypoints we are always on the server thread.
     */
    private static WaypointRecord findById(EllieGPSStore store, String id) {
        if (id == null) return null;
        for (WaypointRecord r : store.all()) {
            if (id.equals(r.id)) return r;
        }
        return null;
    }

    /** Dual-audience failure. */
    private void failEarly(PlayerEngineController mod, StorageAccessCode code, String detail) {
        Debug.logWarning("waypoint-locate fail code=" + code.token()
                + " detail=" + detail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress("couldn't locate waypoints - " + detail, true);
        this.finishWithError(code.token() + ": " + detail);
    }
}
