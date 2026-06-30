package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointIngestionService;
import com.player2.playerengine.agentic.elliegps.WaypointOriginClassifier;
import com.player2.playerengine.agentic.elliegps.WaypointOriginEvidence;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * {@code create_waypoint <x> <y> <z>} — register (or refresh) an inventory waypoint at the
 * container at/canonicalized from {@code (x,y,z)} (Part C5, WS5).
 *
 * <p>Guard chain (executed in order; any failure is dual-audience):
 * <ol>
 *   <li>EllieGPS enabled check.</li>
 *   <li>One-time quarantine note (Decision 14; first EllieGPS command after a quarantine).</li>
 *   <li>Travel-cap pre-check (before navigation starts).</li>
 *   <li>Origin filter: Tier 2 (worldgen loot) and UNKNOWN (Tier 2 unevaluable) — both refuse
 *       explicitly, NEVER overridable. Tier 3 (structure-piece) is overridden by explicit
 *       create intent per Decision 4, with the override noted in the success feedback.</li>
 *   <li>{@link ScanContainerTask} LIGHT scan.</li>
 *   <li>Ingestion + upsert (the store reindexes internally).</li>
 * </ol>
 *
 * <p>Existing record at the same canonical id is refreshed ({@code createdGameTime} preserved).
 * Every failure/degradation is dual-audience: one player chat line +
 * {@code finishWithError}/{@code finishWithNote} for the model.
 */
public class CreateWaypointCommand extends Command {

    public CreateWaypointCommand() throws CommandException {
        super(
                "create_waypoint",
                "create_waypoint <x> <y> <z>. Registers (or refreshes) an inventory waypoint for"
                        + " the storage container at the given block coordinates. Navigates to the container,"
                        + " scans it, derives category keywords and a description, then persists the record to"
                        + " EllieGPS. An existing waypoint at the same position is refreshed with the latest"
                        + " snapshot. The waypoint can then be found via locate_waypoints or used by the"
                        + " counting service for material estimates.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        // Guard 1: EllieGPS enabled (checked first, before any parsing or store access)
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            // Player path: translatable Component (.getString() bridges until reportAgenticProgress(Component) exists)
            mod.reportAgenticProgress(WaypointReportFormatter.ellieGpsDisabledPlayerComponent().getString(), true);
            // Model path: stays English for AI truthfulness
            this.finishWithError(WaypointReportFormatter.ellieGpsDisabledModel());
            return;
        }

        // One-time operator note if the store was quarantined (Decision 14): surfaced by the
        // first EllieGPS command that runs, then never again (the consume clears the flag).
        EllieGPSStore quarantineStore = EllieGPSStore.get();
        if (quarantineStore != null && quarantineStore.consumeQuarantineNote()) {
            mod.reportAgenticProgress(WaypointReportFormatter.quarantineNoteComponent().getString(), true);
        }

        // Guard 2: parse coordinates
        String[] u = parser.getArgUnits();
        if (u.length < 3) {
            failEarly(mod, StorageAccessCode.INVALID_ARGUMENT, "usage: create_waypoint <x> <y> <z>");
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

        // Guard 3: travel-cap pre-check (before navigation)
        Vec3 botPos = mod.getEntity().position();
        double capSq = ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS * ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;
        double distSq = botPos.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
        if (distSq > capSq) {
            String detail = ContainerResolver.formatPos(queriedPos) + " is about "
                    + (int) Math.sqrt(distSq) + " blocks away, beyond the "
                    + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS + "-block travel cap";
            failEarly(mod, StorageAccessCode.CONTAINER_TOO_FAR, detail);
            return;
        }

        ServerLevel level = mod.getWorld();
        if (level == null) {
            failEarly(mod, StorageAccessCode.CONTAINER_UNREACHABLE, "no world available");
            return;
        }

        // Guard 4: Origin filter — BEFORE any container scan (Decision 4 scan-order invariant).
        // Resolve for canonicalization. ContainerResolver.resolve reads block state + BE only for
        // chunk/block checks; it does NOT call getItem(), so the loot-table marker is intact.
        ContainerResolver.Resolution resolution = ContainerResolver.resolve(level, queriedPos);
        if (!resolution.ok()) {
            failEarly(mod, resolution.code(), resolution.detail());
            return;
        }
        BlockPos canonicalPos = resolution.resolved().canonicalPos();
        BlockPos secondaryPos = resolution.resolved().secondaryPos();

        // Tier 2: un-opened worldgen loot (certain negative; explicit create NEVER overrides)
        WaypointOriginEvidence.Verdict verdict = WaypointOriginClassifier.classify(
                level, canonicalPos, secondaryPos);
        if (verdict == WaypointOriginEvidence.Verdict.REJECT_WORLDGEN) {
            String canonPosStr = ContainerResolver.formatPos(canonicalPos);
            // Player path: translatable; model path: English String (separate to preserve AI truthfulness)
            mod.reportAgenticProgress(
                    WaypointReportFormatter.refusedWorldgenLootComponent(canonPosStr).getString(), true);
            this.finishWithError(WaypointReportFormatter.refusedWorldgenLoot(canonPosStr));
            return;
        }
        // UNKNOWN: the classifier could not evaluate (Tier 2 unevaluable). The explicit-create
        // policy only licenses overriding Tier 3 — an unevaluated Tier 2 falls under the
        // conservative do-not-register default (Decision 4), so refuse here as well.
        if (verdict == WaypointOriginEvidence.Verdict.UNKNOWN) {
            String canonPosStr = ContainerResolver.formatPos(canonicalPos);
            mod.reportAgenticProgress(
                    WaypointReportFormatter.refusedOriginUnverifiedComponent(canonPosStr).getString(), true);
            this.finishWithError(WaypointReportFormatter.refusedOriginUnverified(canonPosStr));
            return;
        }
        // Tier 3 (REJECT_STRUCTURE) is overridden by explicit create intent (Decision 4);
        // the override is noted in the success feedback below so both audiences see it.
        final boolean structureOverride = (verdict == WaypointOriginEvidence.Verdict.REJECT_STRUCTURE);

        // Find the existing record (for createdGameTime preservation on refresh)
        EllieGPSStore storeSnapshot = EllieGPSStore.get();
        String dimensionId = level.dimension().location().toString();
        final WaypointRecord existingRecord = (storeSnapshot != null)
                ? storeSnapshot.byPosition(dimensionId, canonicalPos)
                : null;

        // Guard 5: LIGHT scan via ScanContainerTask
        ScanContainerTask task = new ScanContainerTask(queriedPos, ScanMode.LIGHT, List.of());
        mod.runUserTask(task, () -> {
            if (task.result().isPresent()) {
                ContainerSnapshot snapshot = task.result().get();
                EllieGPSStore liveStore = EllieGPSStore.get();
                if (liveStore == null) {
                    mod.reportAgenticProgress(
                            Component.translatable("message.playerengine.elliegps.store_unavailable")
                                    .getString(),
                            true);
                    // Model path: English error code, not localized
                    this.finishWithError("elliegps_store_unavailable: no active EllieGPS store");
                    return;
                }

                // Ingest: deterministic keywords + description; optional async polish
                WaypointRecord record = WaypointIngestionService.ingest(
                        mod, snapshot, WaypointRecord.ORIGIN_EXPLICIT_CREATE, existingRecord);

                // Re-canonicalization guard: if the matched record's canonical id differs from
                // the live scan's (e.g. a double chest was split or re-paired since the record
                // was written), delete the old record first or it would survive as an orphan.
                if (existingRecord != null && !record.id.equals(existingRecord.id)) {
                    liveStore.delete(existingRecord.id);
                }

                // Upsert (the store persists and reindexes internally)
                liveStore.upsert(record);

                // Build feedback quantities
                InventoryWaypointData invData = record.inventoryData();
                boolean snapshotOmitted = (invData == null || invData.snapshot == null);
                int itemTypes = 0;
                if (!snapshotOmitted && invData.snapshot.items != null) {
                    itemTypes = invData.snapshot.items.size();
                }
                int kwCount = (record.keywords != null) ? record.keywords.size() : 0;
                String posStr = ContainerResolver.formatPos(snapshot.canonicalPos());

                // Model feedback — English String, separate from player path (AI truthfulness)
                String modelMsg = WaypointReportFormatter.waypointRegistered(
                        posStr, itemTypes, kwCount, snapshotOmitted);
                if (structureOverride) {
                    // Decision 4: the Tier 3 override must be noted in feedback (both audiences)
                    modelMsg += " " + WaypointReportFormatter.structureOverrideNote();
                }
                if (mod.getModSettings().getEllieGpsUseModelDescription()) {
                    modelMsg += " " + WaypointReportFormatter.descriptionPolishScheduledNote();
                }
                AiConversationFeedback.enqueueInfo(mod, modelMsg);

                // Player milestone — Component.translatable for i18n; two keys cover the
                // snapshotOmitted branch. .getString() bridges until reportAgenticProgress(Component) exists.
                MutableComponent playerMsg = WaypointReportFormatter.waypointRegisteredComponent(
                        posStr, itemTypes, kwCount, snapshotOmitted);
                if (structureOverride) {
                    playerMsg.append(" ").append(WaypointReportFormatter.structureOverrideNoteComponent());
                }
                mod.reportAgenticProgress(playerMsg.getString(), true);
                Debug.logMessage("waypoint-create ok id=" + record.id
                        + " snapshotOmitted=" + snapshotOmitted
                        + " kw=" + kwCount
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
                failEarly(mod, failure.code(), failure.detail());
            }
        });
    }

    /** Dual-audience failure (player chat + model finishWithError). */
    private void failEarly(PlayerEngineController mod, StorageAccessCode code, String detail) {
        Debug.logWarning("waypoint-create fail code=" + code.token()
                + " detail=" + detail
                + " bot=" + mod.getEntity().getName().getString());
        // Player path: translatable prefix; detail stays English (shared with model — not localized)
        mod.reportAgenticProgress(
                Component.translatable("message.playerengine.elliegps.create_fail", detail).getString(),
                true);
        // Model path: English token:detail for AI truthfulness
        this.finishWithError(code.token() + ": " + detail);
    }
}
