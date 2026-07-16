package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmCropCount;
import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.FarmWaypointObservation;
import com.player2.playerengine.agentic.elliegps.FarmWaypointService;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointMutationResult;
import com.player2.playerengine.agentic.elliegps.WaypointMutationStatus;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointReportFormatter;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Compares a typed EllieGPS waypoint with a live observation without refreshing its contents. */
public class CompareWaypointCommand extends Command {

    private static final int MAX_DISPLAYED_CHANGES = 12;
    // Resource-location namespaces and paths cannot contain '|', so this is collision-free.
    private static final char CROP_KEY_SEPARATOR = '|';

    public CompareWaypointCommand() throws CommandException {
        super(
                "compare_waypoint",
                "compare_waypoint <x> <y> <z>. Observes the stored inventory or farm waypoint and"
                        + " reports deterministic drift without refreshing it. Missing targets are marked"
                        + " stale; use audit_waypoint to accept observed changes.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            mod.reportAgenticProgress(WaypointReportFormatter.ellieGpsDisabledPlayerComponent(), true);
            this.finishWithError(WaypointReportFormatter.ellieGpsDisabledModel());
            return;
        }

        EllieGPSStore quarantineStore = EllieGPSStore.get();
        if (quarantineStore != null && quarantineStore.consumeQuarantineNote()) {
            mod.reportAgenticProgress(WaypointReportFormatter.quarantineNoteComponent(), true);
        }

        String[] units = parser.getArgUnits();
        if (units.length < 3) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT,
                    "usage: compare_waypoint <x> <y> <z>");
            return;
        }

        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(units[0].trim());
            y = Integer.parseInt(units[1].trim());
            z = Integer.parseInt(units[2].trim());
        } catch (NumberFormatException e) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT, "x y z must be integers");
            return;
        }

        BlockPos queriedPos = new BlockPos(x, y, z);
        String posStr = ContainerResolver.formatPos(queriedPos);
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
            String detail = "no waypoint at " + posStr + " in " + dimensionId
                    + "; use create_waypoint " + x + " " + y + " " + z
                    + " to register it first";
            failWaypoint(mod, "waypoint_not_found", detail);
            return;
        }

        // Dispatch before the inventory snapshot guard and before creating a container task. This
        // prevents a farm's center water block from ever being treated as a container.
        if (WaypointTypes.FARM.equals(existingRecord.type)) {
            compareFarm(mod, existingRecord, posStr);
            return;
        }
        if (!WaypointTypes.INVENTORY.equals(existingRecord.type)) {
            failWaypoint(mod, "unsupported_waypoint_type",
                    "unsupported waypoint type '"
                            + WaypointReportFormatter.boundIdentifier(existingRecord.type) + "'");
            return;
        }

        InventoryWaypointData inventory = existingRecord.inventoryData();
        if (inventory == null || inventory.snapshot == null) {
            String modelMessage = WaypointReportFormatter.noSnapshotStored();
            mod.reportAgenticProgress(
                    WaypointReportFormatter.noSnapshotStoredComponent(), true);
            this.finishWithNote(modelMessage);
            return;
        }

        Vec3 botPos = mod.getEntity().position();
        double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS
                * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = botPos.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
        if (distSq > capSq) {
            String detail = posStr + " is about " + (int) Math.sqrt(distSq)
                    + " blocks away, beyond the "
                    + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS + "-block travel cap";
            failEarly(mod, StorageAccessCode.CONTAINER_TOO_FAR, detail);
            return;
        }

        final InventoryWaypointData.Snapshot storedSnapshot = inventory.snapshot;
        final WaypointRecord capturedRecord = existingRecord;
        ScanContainerTask task = new ScanContainerTask(queriedPos, ScanMode.LIGHT, List.of());
        mod.runUserTask(task, () -> finishInventoryCompare(
                mod, task, capturedRecord, storedSnapshot, posStr));
    }

    private void finishInventoryCompare(
            PlayerEngineController mod,
            ScanContainerTask task,
            WaypointRecord capturedRecord,
            InventoryWaypointData.Snapshot storedSnapshot,
            String posStr) {
        if (task.result().isPresent()) {
            ContainerSnapshot liveSnapshot = task.result().get();
            List<String> changes = computeInventoryDrift(storedSnapshot, liveSnapshot);
            if (changes.isEmpty()) {
                String modelMessage = WaypointReportFormatter.boundModel(
                        "no drift at " + WaypointReportFormatter.boundIdentifier(posStr)
                                + "; stored snapshot matches the live container.");
                mod.reportAgenticProgress(
                        WaypointReportFormatter.inventoryCompareNoDriftComponent(posStr), true);
                AiConversationFeedback.enqueueInfo(mod, modelMessage);
                this.finish();
            } else {
                String summary = summarize(changes, MAX_DISPLAYED_CHANGES);
                String modelMessage = WaypointReportFormatter.boundModel(
                        "inventory drift at " + WaypointReportFormatter.boundIdentifier(posStr)
                                + ": " + summary + "; "
                                + WaypointReportFormatter.auditHint(posStr));
                mod.reportAgenticProgress(
                        WaypointReportFormatter.inventoryCompareDriftComponent(posStr, summary), true);
                this.finishWithNote(modelMessage);
            }
            Debug.logMessage("waypoint-compare inventory id=" + capturedRecord.id
                    + " drift=" + !changes.isEmpty()
                    + " bot=" + mod.getEntity().getName().getString());
            return;
        }

        ScanContainerTask.Failure failure = task.failure().orElse(
                new ScanContainerTask.Failure(
                        StorageAccessCode.CONTAINER_UNREACHABLE,
                        "scan session ended without a result"));
        if (failure.code() == StorageAccessCode.CONTAINER_MISSING) {
            EllieGPSStore liveStore = EllieGPSStore.get();
            if (liveStore == null) {
                failWaypoint(mod, "elliegps_store_unavailable",
                        "EllieGPS store became unavailable before the waypoint could be marked stale");
                return;
            }
            handleStaleMutation(mod, liveStore.markStale(capturedRecord.id), posStr, false);
        } else {
            failEarly(mod, failure.code(), failure.detail());
        }
    }

    private void compareFarm(
            PlayerEngineController mod,
            WaypointRecord capturedRecord,
            String posStr) {
        FarmObservationResult result = FarmWaypointService.observe(mod, capturedRecord);
        switch (result.status()) {
            case OBSERVED -> compareFarmObservation(
                    mod, capturedRecord, result.observation(), posStr);
            case STALE_CENTER -> {
                EllieGPSStore liveStore = EllieGPSStore.get();
                if (liveStore == null) {
                    failWaypoint(mod, "elliegps_store_unavailable",
                            "EllieGPS store became unavailable before the farm could be marked stale");
                    return;
                }
                handleStaleMutation(mod, liveStore.markStale(capturedRecord.id), posStr, true);
            }
            case HANDLER_UNAVAILABLE, UNLOADED, UNSUPPORTED_DATA ->
                    failFarmObservation(mod, result);
        }
    }

    private void compareFarmObservation(
            PlayerEngineController mod,
            WaypointRecord capturedRecord,
            FarmWaypointObservation live,
            String posStr) {
        FarmWaypointData stored = capturedRecord.farmData();
        if (stored == null || !stored.isSupportedVersion()) {
            failWaypoint(mod, "unsupported_farm_data",
                    "farm waypoint data version is unsupported");
            return;
        }

        List<String> changes = computeFarmDrift(stored, live);
        if (changes.isEmpty()) {
            String modelMessage = WaypointReportFormatter.farmCompareNoDrift(posStr);
            mod.reportAgenticProgress(
                    WaypointReportFormatter.farmCompareNoDriftComponent(posStr), true);
            AiConversationFeedback.enqueueInfo(mod, modelMessage);
            this.finish();
        } else {
            String summary = String.join(", ", changes);
            String modelMessage = WaypointReportFormatter.farmCompareDrift(posStr, summary);
            mod.reportAgenticProgress(
                    WaypointReportFormatter.farmCompareDriftComponent(posStr, summary), true);
            this.finishWithNote(modelMessage);
        }
        Debug.logMessage("waypoint-compare farm id=" + capturedRecord.id
                + " drift=" + !changes.isEmpty()
                + " bot=" + mod.getEntity().getName().getString());
    }

    private static List<String> computeFarmDrift(
            FarmWaypointData stored,
            FarmWaypointObservation live) {
        ArrayList<String> changes = new ArrayList<>();
        if (stored.radius() != live.radius()) {
            changes.add("radius " + stored.radius() + "->" + live.radius());
        }
        if (stored.farmlandCount() != live.farmlandCount()) {
            changes.add("farmland " + stored.farmlandCount()
                    + "->" + live.farmlandCount());
        }
        if (stored.openSlots().isEmpty()) {
            changes.add("open slots unknown->" + live.openSlots());
        } else if (stored.openSlots().getAsInt() != live.openSlots()) {
            changes.add("open slots " + stored.openSlots().getAsInt()
                    + "->" + live.openSlots());
        }

        Map<String, Integer> storedCrops = cropCounts(stored.crops());
        Map<String, Integer> liveCrops = cropCounts(live.crops());
        TreeSet<String> cropKeys = new TreeSet<>();
        cropKeys.addAll(storedCrops.keySet());
        cropKeys.addAll(liveCrops.keySet());

        int cropChanges = 0;
        int displayed = 0;
        for (String key : cropKeys) {
            int before = storedCrops.getOrDefault(key, 0);
            int after = liveCrops.getOrDefault(key, 0);
            if (before == after) {
                continue;
            }
            cropChanges++;
            if (displayed >= MAX_DISPLAYED_CHANGES) {
                continue;
            }
            String label = cropLabel(key);
            if (before == 0) {
                changes.add("+" + label + " x" + after);
            } else if (after == 0) {
                changes.add("-" + label + " x" + before);
            } else {
                changes.add(label + " " + before + "->" + after);
            }
            displayed++;
        }
        if (cropChanges > displayed) {
            changes.add("+" + (cropChanges - displayed) + " more crop changes");
        }
        return changes;
    }

    private static Map<String, Integer> cropCounts(List<FarmCropCount> crops) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (FarmCropCount crop : crops) {
            String key = crop.blockId() + CROP_KEY_SEPARATOR
                    + (crop.plantingItemId() == null ? "" : crop.plantingItemId());
            counts.merge(key, crop.count(), Math::addExact);
        }
        return counts;
    }

    private static String cropLabel(String key) {
        int separator = key.indexOf(CROP_KEY_SEPARATOR);
        if (separator < 0 || separator == key.length() - 1) {
            return WaypointReportFormatter.boundIdentifier(
                    separator < 0 ? key : key.substring(0, separator));
        }
        return WaypointReportFormatter.boundIdentifier(
                key.substring(0, separator) + " (plant " + key.substring(separator + 1) + ")");
    }

    private static List<String> computeInventoryDrift(
            InventoryWaypointData.Snapshot stored,
            ContainerSnapshot live) {
        TreeMap<String, Integer> storedItems = new TreeMap<>();
        if (stored.items != null) {
            for (ItemCount item : stored.items) {
                storedItems.merge(item.registryId(), item.count(), Math::addExact);
            }
        }
        TreeMap<String, Integer> liveItems = new TreeMap<>();
        if (live.aggregate() != null) {
            for (ItemCount item : live.aggregate()) {
                liveItems.merge(item.registryId(), item.count(), Math::addExact);
            }
        }

        ArrayList<String> changes = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(storedItems.keySet());
        ids.addAll(liveItems.keySet());
        for (String id : ids) {
            int before = storedItems.getOrDefault(id, 0);
            int after = liveItems.getOrDefault(id, 0);
            if (before == after) {
                continue;
            }
            String shownId = WaypointReportFormatter.boundIdentifier(id);
            if (before == 0) {
                changes.add("+" + shownId + " x" + after);
            } else if (after == 0) {
                changes.add("-" + shownId + " x" + before);
            } else {
                changes.add(shownId + " " + before + "->" + after);
            }
        }
        if (stored.emptySlots != live.emptySlots()) {
            changes.add("empty " + stored.emptySlots + "->" + live.emptySlots());
        }
        return changes;
    }

    private static String summarize(List<String> changes, int limit) {
        int shown = Math.min(changes.size(), limit);
        String summary = String.join(", ", changes.subList(0, shown));
        if (changes.size() > shown) {
            summary += ", +" + (changes.size() - shown) + " more changes";
        }
        return summary;
    }

    private void handleStaleMutation(
            PlayerEngineController mod,
            WaypointMutationResult mutation,
            String posStr,
            boolean farm) {
        switch (mutation.status()) {
            case COMMITTED -> finishStaleSuccess(mod, posStr, false, true, farm);
            case COMMITTED_INDEX_DEGRADED -> finishStaleSuccess(mod, posStr, true, true, farm);
            case NO_CHANGE -> finishStaleSuccess(mod, posStr, false, false, farm);
            case NO_CHANGE_INDEX_DEGRADED -> finishStaleSuccess(mod, posStr, true, false, farm);
            case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED,
                    REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT,
                    FAILED_STORE_UNAVAILABLE, FAILED_JSON_COMMIT ->
                    finishMutationFailure(mod, mutation.status());
        }
    }

    private void finishStaleSuccess(
            PlayerEngineController mod,
            String posStr,
            boolean indexDegraded,
            boolean changed,
            boolean farm) {
        String staleMessage = farm
                ? WaypointReportFormatter.farmWaypointStaleMissing(posStr)
                : WaypointReportFormatter.waypointStaleMissing(posStr);
        mod.reportAgenticProgress(farm
                ? WaypointReportFormatter.farmWaypointStaleMissingComponent(posStr)
                : WaypointReportFormatter.waypointStaleMissingComponent(posStr), true);
        if (indexDegraded) {
            mod.reportAgenticProgress(
                    WaypointReportFormatter.indexDegradedComponent(changed), true);
            staleMessage = WaypointReportFormatter.boundModel(
                    staleMessage + " " + WaypointReportFormatter.indexDegradedModel(changed));
        }
        this.finishWithNote(staleMessage);
    }

    private void finishMutationFailure(
            PlayerEngineController mod,
            WaypointMutationStatus status) {
        switch (status) {
            case NOT_FOUND -> finishNotFoundMutation(mod, false);
            case NOT_FOUND_INDEX_DEGRADED -> finishNotFoundMutation(mod, true);
            case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT -> {
                mod.reportAgenticProgress(WaypointReportFormatter.waypointConflictComponent(), true);
                this.finishWithError(WaypointReportFormatter.waypointConflictModel(status));
            }
            case FAILED_STORE_UNAVAILABLE -> failWaypoint(
                    mod, "elliegps_store_unavailable",
                    "EllieGPS store is unavailable; the waypoint was not changed");
            case FAILED_JSON_COMMIT -> {
                mod.reportAgenticProgress(WaypointReportFormatter.jsonCommitFailedComponent(), true);
                this.finishWithError(WaypointReportFormatter.jsonCommitFailedModel());
            }
            case COMMITTED, COMMITTED_INDEX_DEGRADED,
                    NO_CHANGE, NO_CHANGE_INDEX_DEGRADED ->
                    throw new IllegalArgumentException("not a failed mutation status: " + status);
        }
    }

    private void finishNotFoundMutation(PlayerEngineController mod, boolean indexDegraded) {
        String modelMessage = WaypointReportFormatter.mutationNotFoundModel();
        mod.reportAgenticProgress(WaypointReportFormatter.mutationNotFoundComponent(), true);
        if (indexDegraded) {
            mod.reportAgenticProgress(
                    WaypointReportFormatter.indexDegradedComponent(false), true);
            modelMessage = WaypointReportFormatter.boundModel(
                    modelMessage + " " + WaypointReportFormatter.indexDegradedModel(false));
        }
        this.finishWithError(modelMessage);
    }

    private void failFarmObservation(PlayerEngineController mod, FarmObservationResult result) {
        Debug.logWarning("waypoint-compare farm observation status=" + result.status()
                + " reason=" + WaypointReportFormatter.boundReason(result.controlledReason()));
        mod.reportAgenticProgress(
                WaypointReportFormatter.farmObservationFailedComponent(result), true);
        this.finishWithError(WaypointReportFormatter.farmObservationFailed(result));
    }

    private void failEarly(
            PlayerEngineController mod,
            StorageAccessCode code,
            String detail) {
        failWaypoint(mod, code.token(), detail);
    }

    private void failWaypoint(
            PlayerEngineController mod,
            String code,
            String detail) {
        String boundedDetail = WaypointReportFormatter.boundReason(detail);
        Debug.logWarning("waypoint-compare fail code=" + code
                + " detail=" + boundedDetail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress(
                WaypointReportFormatter.compareFailedComponent(boundedDetail), true);
        this.finishWithError(WaypointReportFormatter.boundModel(
                WaypointReportFormatter.boundIdentifier(code) + ": " + boundedDetail));
    }
}
