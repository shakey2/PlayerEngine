package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmWaypointObservation;
import com.player2.playerengine.agentic.elliegps.FarmWaypointService;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointIngestionService;
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
import java.util.List;

/** Re-observes an existing typed EllieGPS waypoint and commits a checked refresh. */
public class AuditWaypointCommand extends Command {

    public AuditWaypointCommand() throws CommandException {
        super(
                "audit_waypoint",
                "audit_waypoint <x> <y> <z>. Re-observes an existing EllieGPS waypoint and"
                        + " updates its stored inventory or farm data. Missing targets are marked stale;"
                        + " use delete_waypoint to remove them.",
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
                    "usage: audit_waypoint <x> <y> <z>");
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

        // Type dispatch must precede every inventory-only guard or scan. In particular, a farm's
        // water center must never be handed to ScanContainerTask.
        if (WaypointTypes.FARM.equals(existingRecord.type)) {
            auditFarm(mod, existingRecord, posStr);
            return;
        }
        if (!WaypointTypes.INVENTORY.equals(existingRecord.type)) {
            failWaypoint(mod, "unsupported_waypoint_type",
                    "unsupported waypoint type '"
                            + WaypointReportFormatter.boundIdentifier(existingRecord.type) + "'");
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

        final WaypointRecord capturedRecord = existingRecord;
        ScanContainerTask task = new ScanContainerTask(queriedPos, ScanMode.LIGHT, List.of());
        mod.runUserTask(task, () -> finishInventoryAudit(mod, task, capturedRecord, posStr));
    }

    private void finishInventoryAudit(
            PlayerEngineController mod,
            ScanContainerTask task,
            WaypointRecord capturedRecord,
            String posStr) {
        EllieGPSStore liveStore = EllieGPSStore.get();
        if (liveStore == null) {
            failWaypoint(mod, "elliegps_store_unavailable",
                    "EllieGPS store became unavailable before the audit completed");
            return;
        }

        if (task.result().isPresent()) {
            ContainerSnapshot snapshot = task.result().get();
            String originToken = capturedRecord.origin != null && !capturedRecord.origin.isBlank()
                    ? capturedRecord.origin
                    : WaypointRecord.ORIGIN_EXPLICIT_CREATE;
            WaypointRecord refreshed = WaypointIngestionService.ingest(
                    mod, snapshot, originToken, capturedRecord);

            // This is one authoritative transaction even when the live scan changes canonical ID.
            WaypointMutationResult mutation = liveStore.replace(capturedRecord.id, refreshed);
            handleInventoryRefreshResult(mod, mutation, refreshed, snapshot);
            return;
        }

        ScanContainerTask.Failure failure = task.failure().orElse(
                new ScanContainerTask.Failure(
                        StorageAccessCode.CONTAINER_UNREACHABLE,
                        "scan session ended without a result"));
        if (failure.code() == StorageAccessCode.CONTAINER_MISSING) {
            handleStaleMutation(mod, liveStore.markStale(capturedRecord.id), posStr, false);
        } else {
            failEarly(mod, failure.code(), failure.detail());
        }
    }

    private void auditFarm(
            PlayerEngineController mod,
            WaypointRecord capturedRecord,
            String posStr) {
        FarmObservationResult result = FarmWaypointService.observe(mod, capturedRecord);
        switch (result.status()) {
            case OBSERVED -> {
                String origin = capturedRecord.origin != null && !capturedRecord.origin.isBlank()
                        ? capturedRecord.origin
                        : WaypointRecord.ORIGIN_BOT_PLACED;
                WaypointMutationResult mutation = FarmWaypointService.registerOrRefresh(
                        result.observation(), origin);
                handleFarmRefreshResult(mod, mutation, result.observation(), posStr);
            }
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

    private void handleInventoryRefreshResult(
            PlayerEngineController mod,
            WaypointMutationResult mutation,
            WaypointRecord refreshed,
            ContainerSnapshot snapshot) {
        switch (mutation.status()) {
            case COMMITTED -> finishInventoryRefresh(
                    mod, mutation.current(), refreshed, snapshot, true, false);
            case COMMITTED_INDEX_DEGRADED -> finishInventoryRefresh(
                    mod, mutation.current(), refreshed, snapshot, true, true);
            case NO_CHANGE -> finishInventoryRefresh(
                    mod, mutation.current(), refreshed, snapshot, false, false);
            case NO_CHANGE_INDEX_DEGRADED -> finishInventoryRefresh(
                    mod, mutation.current(), refreshed, snapshot, false, true);
            case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED,
                    REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT,
                    FAILED_STORE_UNAVAILABLE, FAILED_JSON_COMMIT ->
                    finishMutationFailure(mod, mutation.status());
        }
    }

    private void finishInventoryRefresh(
            PlayerEngineController mod,
            WaypointRecord authoritative,
            WaypointRecord refreshed,
            ContainerSnapshot snapshot,
            boolean changed,
            boolean indexDegraded) {
        WaypointRecord shown = authoritative != null ? authoritative : refreshed;
        InventoryWaypointData inventory = shown.inventoryData();
        boolean snapshotOmitted = inventory == null || inventory.snapshot == null;
        int itemTypes = snapshotOmitted || inventory.snapshot.items == null
                ? 0
                : inventory.snapshot.items.size();
        int keywordCount = shown.keywords == null ? 0 : shown.keywords.size();
        BlockPos shownPos = shown.canonicalBlockPos();
        String formattedPos = ContainerResolver.formatPos(
                shownPos != null ? shownPos : snapshot.canonicalPos());

        boolean polishScheduled = authoritative != null
                && WaypointIngestionService.schedulePolishAfterCommit(mod, authoritative);
        String modelMessage;
        Component playerMessage;
        if (changed) {
            modelMessage = WaypointReportFormatter.waypointRegistered(
                    formattedPos, itemTypes, keywordCount, snapshotOmitted);
            playerMessage = WaypointReportFormatter.waypointRegisteredComponent(
                    formattedPos, itemTypes, keywordCount, snapshotOmitted);
        } else {
            modelMessage = WaypointReportFormatter.waypointUnchanged(formattedPos);
            playerMessage = WaypointReportFormatter.waypointUnchangedComponent(formattedPos);
        }
        if (polishScheduled) {
            modelMessage = WaypointReportFormatter.boundModel(
                    modelMessage + " " + WaypointReportFormatter.descriptionPolishScheduledNote());
        }

        ArrayList<String> completionNotes = new ArrayList<>();
        if (snapshotOmitted) {
            completionNotes.add("snapshot omitted because the used-slot threshold was exceeded");
        }
        finishSuccessfulMutation(
                mod, playerMessage, modelMessage, changed, indexDegraded, completionNotes);

        Debug.logMessage("waypoint-audit ok id=" + shown.id
                + " status=" + (indexDegraded ? "index_degraded" : (changed ? "committed" : "no_change"))
                + " snapshotOmitted=" + snapshotOmitted
                + " bot=" + mod.getEntity().getName().getString());
    }

    private void handleFarmRefreshResult(
            PlayerEngineController mod,
            WaypointMutationResult mutation,
            FarmWaypointObservation observation,
            String posStr) {
        switch (mutation.status()) {
            case COMMITTED -> finishFarmRefresh(mod, observation, posStr, true, false);
            case COMMITTED_INDEX_DEGRADED ->
                    finishFarmRefresh(mod, observation, posStr, true, true);
            case NO_CHANGE -> finishFarmRefresh(mod, observation, posStr, false, false);
            case NO_CHANGE_INDEX_DEGRADED ->
                    finishFarmRefresh(mod, observation, posStr, false, true);
            case NOT_FOUND, NOT_FOUND_INDEX_DEGRADED,
                    REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT,
                    FAILED_STORE_UNAVAILABLE, FAILED_JSON_COMMIT ->
                    finishMutationFailure(mod, mutation.status());
        }
    }

    private void finishFarmRefresh(
            PlayerEngineController mod,
            FarmWaypointObservation observation,
            String posStr,
            boolean changed,
            boolean indexDegraded) {
        Component playerMessage;
        String modelMessage;
        if (changed) {
            playerMessage = WaypointReportFormatter.farmAuditUpdatedComponent(
                    posStr,
                    observation.farmlandCount(),
                    observation.openSlots(),
                    observation.crops().size());
            modelMessage = WaypointReportFormatter.farmAuditUpdated(
                    posStr,
                    observation.farmlandCount(),
                    observation.openSlots(),
                    observation.crops().size());
        } else {
            playerMessage = WaypointReportFormatter.waypointUnchangedComponent(posStr);
            modelMessage = WaypointReportFormatter.waypointUnchanged(posStr);
        }
        finishSuccessfulMutation(
                mod, playerMessage, modelMessage, changed, indexDegraded, new ArrayList<>());
        Debug.logMessage("waypoint-audit farm id="
                + WaypointRecord.idFor(observation.dimension(), observation.center())
                + " changed=" + changed + " indexDegraded=" + indexDegraded);
    }

    private void finishSuccessfulMutation(
            PlayerEngineController mod,
            Component playerMessage,
            String modelMessage,
            boolean changed,
            boolean indexDegraded,
            List<String> completionNotes) {
        mod.reportAgenticProgress(playerMessage, true);
        AiConversationFeedback.enqueueInfo(mod, WaypointReportFormatter.boundModel(modelMessage));
        if (indexDegraded) {
            mod.reportAgenticProgress(
                    WaypointReportFormatter.indexDegradedComponent(changed), true);
            completionNotes.add(WaypointReportFormatter.indexDegradedModel(changed));
        }
        if (completionNotes.isEmpty()) {
            this.finish();
        } else {
            this.finishWithNote(WaypointReportFormatter.boundModel(
                    String.join("; ", completionNotes)));
        }
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
        Debug.logWarning("waypoint-audit farm observation status=" + result.status()
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
        Debug.logWarning("waypoint-audit fail code=" + code
                + " detail=" + boundedDetail
                + " bot=" + mod.getEntity().getName().getString());
        mod.reportAgenticProgress(
                WaypointReportFormatter.auditFailedComponent(boundedDetail), true);
        this.finishWithError(WaypointReportFormatter.boundModel(
                WaypointReportFormatter.boundIdentifier(code) + ": " + boundedDetail));
    }
}
