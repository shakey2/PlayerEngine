package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointIngestionService;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ContainerSnapshot;
import com.player2.playerengine.containeraccess.ScanMode;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.container.ScanContainerTask;
import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * {@code audit_waypoint <x> <y> <z>} — re-scan an existing EllieGPS waypoint and overwrite its
 * snapshot, keywords, and description (Part C5, WS5).
 *
 * <p>Guard chain:
 * <ol>
 *   <li>EllieGPS enabled check.</li>
 *   <li>Coordinate parse + record existence check (error if no record, suggesting create_waypoint).</li>
 *   <li>Travel-cap pre-check.</li>
 *   <li>LIGHT scan via {@link ScanContainerTask}.</li>
 *   <li>On success: re-ingest + overwrite + un-stale. On {@code container_missing}: stale-mark
 *       (Decision 8, {@code finishWithNote}). On other failure: {@code finishWithError}.</li>
 * </ol>
 *
 * <p>Dual-audience: every failure/degradation produces a player chat line and model feedback.
 */
public class AuditWaypointCommand extends Command {

    public AuditWaypointCommand() throws CommandException {
        super(
                "audit_waypoint",
                "audit_waypoint <x> <y> <z>. Re-scans an existing EllieGPS waypoint and updates"
                        + " its snapshot, keywords, and description with the latest container contents."
                        + " If the container is missing the waypoint is marked stale (use delete_waypoint"
                        + " to remove it). Use after compare_waypoint detects drift to refresh the record.",
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
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT, "usage: audit_waypoint <x> <y> <z>");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(u[0].trim());
            y = Integer.parseInt(u[1].trim());
            z = Integer.parseInt(u[2].trim());
        } catch (NumberFormatException e) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT, "x y z must be integers");
            return;
        }
        BlockPos queriedPos = new BlockPos(x, y, z);
        String posStr = ContainerResolver.formatPos(queriedPos);

        // Guard 3: store + record existence
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

        WaypointRecord existingRecord = store.byPosition(dimensionId, queriedPos);
        if (existingRecord == null) {
            String msg = "no waypoint at " + posStr + " in " + dimensionId
                    + "; use create_waypoint " + x + " " + y + " " + z + " to register it first";
            mod.reportAgenticProgress("couldn't audit waypoint - " + msg, true);
            this.finishWithError("waypoint_not_found: " + msg);
            return;
        }

        // Guard 4: travel-cap pre-check
        Vec3 botPos = mod.getEntity().position();
        double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = botPos.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
        if (distSq > capSq) {
            String detail = posStr + " is about " + (int) Math.sqrt(distSq) + " blocks away, beyond the "
                    + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS + "-block travel cap";
            failEarly(mod, StorageAccessCode.CONTAINER_TOO_FAR, detail);
            return;
        }

        // Guard 5: LIGHT scan
        final WaypointRecord capturedRecord = existingRecord;
        ScanContainerTask task = new ScanContainerTask(queriedPos, ScanMode.LIGHT, List.of());
        mod.runUserTask(task, () -> {
            EllieGPSStore liveStore = EllieGPSStore.get();
            if (liveStore == null) {
                mod.reportAgenticProgress(
                        "EllieGPS: store no longer available; audit not saved.", true);
                this.finishWithError("elliegps_store_unavailable: no active EllieGPS store");
                return;
            }

            if (task.result().isPresent()) {
                ContainerSnapshot snapshot = task.result().get();
                // Re-ingest: deterministic keywords/description + optional async polish.
                // Origin is preserved from the existing record if available.
                String originToken = (capturedRecord.origin != null && !capturedRecord.origin.isEmpty())
                        ? capturedRecord.origin
                        : WaypointRecord.ORIGIN_EXPLICIT_CREATE;
                WaypointRecord refreshed = WaypointIngestionService.ingest(
                        mod, snapshot, originToken, capturedRecord);
                // Ensure un-staled
                refreshed.stale = false;

                // Re-canonicalization guard: if the live scan canonicalized to a different id
                // (e.g. the double chest was split or re-paired since the record was written),
                // delete the old record first so it cannot survive as a stale orphan.
                if (!refreshed.id.equals(capturedRecord.id)) {
                    liveStore.delete(capturedRecord.id);
                }

                // Upsert (the store persists and reindexes internally)
                liveStore.upsert(refreshed);

                // Feedback
                InventoryWaypointData invData = refreshed.inventoryData();
                boolean snapshotOmitted = (invData == null || invData.snapshot == null);
                int itemTypes = 0;
                if (!snapshotOmitted && invData.snapshot.items != null) {
                    itemTypes = invData.snapshot.items.size();
                }
                int kwCount = (refreshed.keywords != null) ? refreshed.keywords.size() : 0;
                String snapshotPos = ContainerResolver.formatPos(snapshot.canonicalPos());
                String successMsg = WaypointReportFormatter.waypointRegistered(
                        snapshotPos, itemTypes, kwCount, snapshotOmitted);

                String modelMsg = successMsg;
                if (mod.getModSettings().getEllieGpsUseModelDescription()) {
                    modelMsg += " " + WaypointReportFormatter.descriptionPolishScheduledNote();
                }
                AiConversationFeedback.enqueueInfo(mod, modelMsg);
                mod.reportAgenticProgress(successMsg, true);
                Debug.logMessage("waypoint-audit ok id=" + refreshed.id
                        + " snapshotOmitted=" + snapshotOmitted
                        + " bot=" + mod.getEntity().getName().getString());

                if (snapshotOmitted) {
                    this.finishWithNote(
                            "snapshot omitted (used slots > threshold); record is keyword-only");
                } else {
                    this.finish();
                }
            } else {
                ScanContainerTask.Failure failure = task.failure().orElse(
                        new ScanContainerTask.Failure(
                                StorageAccessCode.CONTAINER_UNREACHABLE,
                                "scan session ended without a result"));
                if (failure.code() == StorageAccessCode.CONTAINER_MISSING) {
                    // Decision 8 stale path: mark the record stale, don't auto-delete
                    // (the store persists and reindexes internally)
                    liveStore.markStale(capturedRecord.id, true);
                    String staleMsg = WaypointReportFormatter.waypointStaleMissing(posStr);
                    mod.reportAgenticProgress(staleMsg, true);
                    this.finishWithNote(staleMsg);
                } else {
                    failEarly(mod, failure.code(), failure.detail());
                }
            }
        });
    }

    /** Dual-audience failure. */
    private void failEarly(PlayerEngineController mod, StorageAccessCode code, String detail) {
        Debug.logWarning("waypoint-audit fail code=" + code.token()
                + " detail=" + detail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress("couldn't audit waypoint - " + detail, true);
        this.finishWithError(code.token() + ": " + detail);
    }
}
