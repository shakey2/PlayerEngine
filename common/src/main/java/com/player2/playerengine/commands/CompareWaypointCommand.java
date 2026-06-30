package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ContainerSnapshot;
import com.player2.playerengine.containeraccess.ItemCount;
import com.player2.playerengine.containeraccess.ScanMode;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.container.ScanContainerTask;
import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code compare_waypoint <x> <y> <z>} — scan the container and report drift against the stored
 * snapshot (Part C5, WS5).
 *
 * <p>Drift lines: {@code +<id> xN} added, {@code -<id> xN} removed, {@code <id> a->b} changed,
 * {@code empty a->b} slot delta. Never writes the store on a successful compare — drift never
 * mutates (Decision 1). One exception: a {@code container_missing} outcome stale-marks the
 * record (same as {@code audit_waypoint}, Decision 8).
 *
 * <p>Keyword-only records (no snapshot) return a {@code noSnapshotStored} message instead of
 * diffing. Output always ends with the audit hint when drift was found (steers the compare->audit
 * loop per Decision 1).
 */
public class CompareWaypointCommand extends Command {

    public CompareWaypointCommand() throws CommandException {
        super(
                "compare_waypoint",
                "compare_waypoint <x> <y> <z>. Scans the container and reports drift against the"
                        + " stored EllieGPS snapshot: items added, removed, or count-changed, and empty-slot"
                        + " delta. Does NOT update the stored record (use audit_waypoint after this to refresh"
                        + " it). If the container is missing, marks the waypoint stale.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        // Guard 1: EllieGPS enabled
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            mod.reportAgenticProgress(WaypointReportFormatter.ellieGpsDisabledPlayerComponent(), true);
            this.finishWithError(WaypointReportFormatter.ellieGpsDisabledModel());
            return;
        }

        // One-time operator note if the store was quarantined (Decision 14)
        EllieGPSStore quarantineStore = EllieGPSStore.get();
        if (quarantineStore != null && quarantineStore.consumeQuarantineNote()) {
            mod.reportAgenticProgress(WaypointReportFormatter.quarantineNoteComponent(), true);
        }

        // Guard 2: parse coordinates
        String[] u = parser.getArgUnits();
        if (u.length < 3) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT, "usage: compare_waypoint <x> <y> <z>");
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
            mod.reportAgenticProgress(Component.translatable("message.playerengine.elliegps.compare_failed", msg), true);
            this.finishWithError("waypoint_not_found: " + msg);
            return;
        }

        // Guard 4: if keyword-only (no snapshot), report immediately without scanning
        InventoryWaypointData invData = existingRecord.inventoryData();
        if (invData == null || invData.snapshot == null) {
            String msg = WaypointReportFormatter.noSnapshotStored();
            mod.reportAgenticProgress(WaypointReportFormatter.noSnapshotStoredComponent(), true);
            // Deliver to model via finishWithNote (degraded success: no snapshot to compare)
            this.finishWithNote(msg);
            return;
        }

        // Guard 5: travel-cap pre-check
        Vec3 botPos = mod.getEntity().position();
        double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = botPos.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
        if (distSq > capSq) {
            String detail = posStr + " is about " + (int) Math.sqrt(distSq) + " blocks away, beyond the "
                    + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS + "-block travel cap";
            failEarly(mod, StorageAccessCode.CONTAINER_TOO_FAR, detail);
            return;
        }

        // Capture the stored snapshot for diff before the task runs
        final InventoryWaypointData.Snapshot storedSnapshot = invData.snapshot;
        final WaypointRecord capturedRecord = existingRecord;

        // Guard 6: LIGHT scan
        ScanContainerTask task = new ScanContainerTask(queriedPos, ScanMode.LIGHT, List.of());
        mod.runUserTask(task, () -> {
            EllieGPSStore liveStore = EllieGPSStore.get();

            if (task.result().isPresent()) {
                ContainerSnapshot liveSnapshot = task.result().get();

                // Compute drift — compare only; never write the store
                List<String> driftLines = computeDrift(storedSnapshot, liveSnapshot);
                boolean hasDrift = !driftLines.isEmpty();

                StringBuilder sb = new StringBuilder();
                if (hasDrift) {
                    sb.append("drift at ").append(posStr).append(":\n");
                    for (String line : driftLines) {
                        sb.append("  ").append(line).append("\n");
                    }
                    // Trailing audit hint (steers compare->audit loop, Decision 1)
                    sb.append(WaypointReportFormatter.auditHint(posStr));
                } else {
                    sb.append("no drift at ").append(posStr).append("; stored snapshot matches live container.");
                }
                String report = sb.toString().trim();

                mod.reportAgenticProgress(report, true);
                Debug.logMessage("waypoint-compare ok id=" + capturedRecord.id
                        + " drift=" + hasDrift
                        + " bot=" + mod.getEntity().getName().getString());

                if (hasDrift) {
                    // Drift: deliver the report + audit hint via finishWithNote so the model
                    // sees it as a "succeeded but: <report>" note (Decision 1 / dual-audience).
                    this.finishWithNote(report);
                } else {
                    // No drift: info-channel reply is the clean "no drift" message
                    AiConversationFeedback.enqueueInfo(mod, report);
                    this.finish();
                }
            } else {
                ScanContainerTask.Failure failure = task.failure().orElse(
                        new ScanContainerTask.Failure(
                                StorageAccessCode.CONTAINER_UNREACHABLE,
                                "scan session ended without a result"));
                if (failure.code() == StorageAccessCode.CONTAINER_MISSING
                        && liveStore != null) {
                    // Decision 8 stale path: the one store write compare may make
                    // (the store persists and reindexes internally)
                    liveStore.markStale(capturedRecord.id, true);
                    String staleMsg = WaypointReportFormatter.waypointStaleMissing(posStr);
                    mod.reportAgenticProgress(WaypointReportFormatter.waypointStaleMissingComponent(posStr), true);
                    this.finishWithNote(staleMsg);
                } else {
                    failEarly(mod, failure.code(), failure.detail());
                }
            }
        });
    }

    /**
     * Computes item-level drift between the stored snapshot and the live scan.
     *
     * <p>Reports added ({@code +id xN}), removed ({@code -id xN}), count-changed
     * ({@code id a->b}), and empty-slot delta ({@code empty a->b}).
     */
    private static List<String> computeDrift(
            InventoryWaypointData.Snapshot stored,
            ContainerSnapshot live) {
        List<String> lines = new ArrayList<>();

        // Build index maps keyed by registryId
        Map<String, Integer> storedItems = new LinkedHashMap<>();
        if (stored.items != null) {
            for (ItemCount ic : stored.items) {
                storedItems.put(ic.registryId(), ic.count());
            }
        }
        Map<String, Integer> liveItems = new LinkedHashMap<>();
        if (live.aggregate() != null) {
            for (ItemCount ic : live.aggregate()) {
                liveItems.put(ic.registryId(), ic.count());
            }
        }

        // Removed or count-changed items (in stored but count differs or absent in live)
        for (Map.Entry<String, Integer> entry : storedItems.entrySet()) {
            String id = entry.getKey();
            int storedCount = entry.getValue();
            Integer liveCount = liveItems.get(id);
            if (liveCount == null) {
                lines.add("-" + id + " x" + storedCount);
            } else if (liveCount != storedCount) {
                lines.add(id + " " + storedCount + "->" + liveCount);
            }
        }
        // Added items (in live but not stored)
        for (Map.Entry<String, Integer> entry : liveItems.entrySet()) {
            String id = entry.getKey();
            if (!storedItems.containsKey(id)) {
                lines.add("+" + id + " x" + entry.getValue());
            }
        }

        // Empty-slot delta
        int storedEmpty = stored.emptySlots;
        int liveEmpty = live.emptySlots();
        if (storedEmpty != liveEmpty) {
            lines.add("empty " + storedEmpty + "->" + liveEmpty);
        }

        return lines;
    }

    /** Dual-audience failure. */
    private void failEarly(PlayerEngineController mod, StorageAccessCode code, String detail) {
        Debug.logWarning("waypoint-compare fail code=" + code.token()
                + " detail=" + detail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress(Component.translatable("message.playerengine.elliegps.compare_failed", detail), true);
        this.finishWithError(code.token() + ": " + detail);
    }
}
