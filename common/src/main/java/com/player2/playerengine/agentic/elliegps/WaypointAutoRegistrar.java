package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.AgenticStorageTarget;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ContainerScanService;
import com.player2.playerengine.containeraccess.ContainerScanService.ScanOutcome;
import com.player2.playerengine.containeraccess.ScanMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Deterministic post-deposit auto-registration hook for EllieGPS (Part C5, WS6 / Decision 9).
 *
 * <p>Called from {@link com.player2.playerengine.agentic.AgenticPlanExecutor} in the success
 * branch of the {@code deposit_items} step callback (before the next step starts). This is
 * purely synchronous on the server thread except the best-effort async description polish
 * dispatched by {@link WaypointIngestionService} (Decision 7).
 *
 * <h3>Guard chain (must all pass before scanning)</h3>
 * <ol>
 *   <li>{@code ellieGpsEnabled} setting must be true.</li>
 *   <li>A storage target must be present in {@code context.memory().storageTarget()}.</li>
 *   <li>Origin evidence must be present in {@code context.memory().waypointOriginEvidence()}.</li>
 *   <li>Evidence must pass: Tier 1 ({@code placedByBot}) OR Tier 2+3 verdict is {@code REGISTER}
 *       (i.e. {@code evidence.permitsRegistration()} is true).</li>
 * </ol>
 *
 * <h3>On pass</h3>
 * In-place LIGHT scan via {@link ContainerScanService#scan} (no navigation — bot is already
 * at the chest it just deposited into; loot-table question was answered pre-deposit).
 * On scan success: ingest, upsert, rebuild index, persist, emit player milestone.
 * On scan failure: emit {@code PARTIAL} waypoint degradation + player line; never fails the run.
 *
 * <h3>On guard rejection</h3>
 * Emits a {@code SKIPPED} waypoint degradation with the reason token and a player skip line.
 *
 * <h3>Catch-all invariant</h3>
 * All EllieGPS bugs are swallowed here; a registrar exception must never break a deposit run.
 * (Validation test: deliberately throw inside this method → run still completes normally.)
 */
public final class WaypointAutoRegistrar {

    private WaypointAutoRegistrar() {}

    /**
     * Attempts to auto-register a waypoint after a successful {@code deposit_items} step.
     *
     * <p>This method is called on the server thread, returns promptly, and never throws to its
     * caller. All EllieGPS exceptions are caught, logged, and converted to degradation notes.
     *
     * @param context the agentic execution context for the current run
     * @param mod     the bot controller (must be the same instance used during the run)
     */
    public static void afterDeposit(AgenticExecutionContext context, PlayerEngineController mod) {
        try {
            afterDepositImpl(context, mod);
        } catch (Exception e) {
            // Catch-all: EllieGPS bug must never break a deposit run (Decision 14).
            PlayerEngine.LOGGER.warn(
                    "WaypointAutoRegistrar: unexpected exception (run continues): {}",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            // Best-effort degradation note — if the run state is still reachable
            try {
                context.runState().setWaypointDegraded(
                        DegradationLevel.SKIPPED, "registrar_exception");
                mod.reportAgenticProgress(
                        WaypointReportFormatter.autoSkippedLine("registrar_exception"), false);
            } catch (Exception ignored) {
                // If even the degradation note throws (e.g. run state was cleared), swallow it.
            }
        }
    }

    // -------------------------------------------------------------------------
    // Implementation (may throw — caught by afterDeposit)
    // -------------------------------------------------------------------------

    private static void afterDepositImpl(AgenticExecutionContext context, PlayerEngineController mod) {

        // --- Guard 1: master EllieGPS switch ---
        if (!mod.getModSettings().getEllieGpsEnabled()) {
            // Disabled must still be noted: the planner prompt tells the model waypoints are
            // managed automatically after deposits, so an unnoted skip lets the model claim a
            // waypoint exists (truthfulness rule; plan validation matrix #14).
            skip(context, mod, "elliegps_disabled", DegradationLevel.SKIPPED);
            return;
        }

        // --- Guard 2: storage target must be present ---
        Optional<AgenticStorageTarget> targetOpt = context.memory().storageTarget();
        if (targetOpt.isEmpty()) {
            skip(context, mod, "no_storage_target", DegradationLevel.SKIPPED);
            return;
        }
        AgenticStorageTarget target = targetOpt.get();

        // --- Guard 3: origin evidence must be present ---
        Optional<WaypointOriginEvidence> evidenceOpt = context.memory().waypointOriginEvidence();
        if (evidenceOpt.isEmpty()) {
            skip(context, mod, "origin_unverified", DegradationLevel.SKIPPED);
            return;
        }
        WaypointOriginEvidence evidence = evidenceOpt.get();

        // --- Guard 4: origin must permit registration ---
        if (!evidence.permitsRegistration()) {
            // Rejected by Tier 2 or Tier 3 (or UNKNOWN = conservative do-not-register)
            String reasonToken = evidence.reasonToken() != null && !evidence.reasonToken().isBlank()
                    ? evidence.reasonToken()
                    : "origin_rejected";
            skip(context, mod, reasonToken, DegradationLevel.SKIPPED);
            return;
        }

        // --- Origin-filter passed: attempt in-place LIGHT scan ---
        BlockPos pos = target.pos();
        ServerLevel level = mod.getWorld();
        String dimensionId = (level != null)
                ? level.dimension().location().toString()
                : null;

        // Dimension should always be available (bot is in-world), but be defensive
        if (dimensionId == null || !dimensionId.equals(target.dimension())) {
            skip(context, mod, "dimension_mismatch", DegradationLevel.SKIPPED);
            return;
        }

        // --- No-op-deposit guard: nothing changed, a record already exists -> skip entirely ---
        // afterDeposit runs in the deposit_items SUCCESS branch, but a no-op deposit
        // ("nothing_to_deposit", e.g. the requested item was never in inventory) is ALSO a success
        // (DepositItemsTask: empty selection / nothing held -> succeed). Without this guard every such
        // failed-but-"successful" attempt re-scans the chest and re-runs store.upsert (re-persist +
        // reindex + "EllieGPS: upserted waypoint …" log + a player milestone line) with byte-identical
        // contents — observed ~8x in one session, all no-ops, all identical. When the deposit moved
        // zero items (depositDegradation==SKIPPED, set only on nothing_to_deposit) AND a record already
        // exists at this position, the freshly scanned snapshot would be unchanged: skip the scan,
        // upsert, persist, reindex, log, and milestone. First-time registration and real content
        // changes are untouched (no existing record, or a non-no-op deposit -> guard does not trip).
        EllieGPSStore preStore = EllieGPSStore.get();
        boolean depositWasNoOp =
                context.runState().getDepositDegradation() == DegradationLevel.SKIPPED
                && context.runState().getDepositDegradationReason() != null
                && context.runState().getDepositDegradationReason().contains("nothing_to_deposit");
        if (depositWasNoOp && preStore != null && preStore.byPosition(dimensionId, pos) != null) {
            PlayerEngine.LOGGER.debug(
                    "WaypointAutoRegistrar: deposit no-op and waypoint already registered at {}; "
                            + "skipping redundant re-registration.",
                    ContainerResolver.formatPos(pos));
            return;
        }

        // In-place scan — no navigation, no animation. The bot is standing at the chest it
        // just deposited into; the loot-table marker question was already answered pre-deposit
        // (ResolveStorageChestTask captured the evidence before any container contact).
        ScanOutcome outcome = ContainerScanService.scan(mod, pos, ScanMode.LIGHT, null);

        if (!outcome.ok()) {
            // Scan failure → PARTIAL degradation (we know the chest existed, scan just failed)
            String code = outcome.code() != null ? outcome.code().token() : "scan_failed";
            PlayerEngine.LOGGER.warn(
                    "WaypointAutoRegistrar: in-place scan failed at {} ({}): {}; recording PARTIAL degradation.",
                    ContainerResolver.formatPos(pos), code, outcome.detail());
            context.runState().setWaypointDegraded(DegradationLevel.PARTIAL, code);
            mod.reportAgenticProgress(
                    WaypointReportFormatter.autoSkippedLine("scan failed: " + code), true);
            return;
        }

        // --- Scan succeeded: ingest, persist, reindex ---
        String originToken = evidence.placedByBot()
                ? WaypointRecord.ORIGIN_BOT_PLACED
                : WaypointRecord.ORIGIN_HEURISTIC_PASS;

        EllieGPSStore store = EllieGPSStore.get();
        if (store == null) {
            // World unloaded between deposit success and here — rare but defensive
            skip(context, mod, "store_unavailable", DegradationLevel.SKIPPED);
            return;
        }

        // Preserve createdGameTime if a record already exists at this position
        WaypointRecord existing = store.byPosition(dimensionId, pos);

        WaypointRecord record = WaypointIngestionService.ingest(
                mod, outcome.snapshot(), originToken, existing);

        // Re-canonicalization guard: if the matched record's canonical id differs from the
        // live scan's (double chest split/re-paired since it was written), delete the old
        // record first so it cannot survive as an orphan.
        if (existing != null && !record.id.equals(existing.id)) {
            store.delete(existing.id);
        }

        // Upsert (the store persists and reindexes internally)
        store.upsert(record);

        // Player milestone line (never throttled — milestone=true)
        String posStr = ContainerResolver.formatPos(pos);
        mod.reportAgenticProgress(WaypointReportFormatter.autoRegisteredMilestone(posStr), true);

        PlayerEngine.LOGGER.info(
                "WaypointAutoRegistrar: auto-registered waypoint id={} at {} origin={}.",
                record.id, posStr, originToken);
    }

    // -------------------------------------------------------------------------
    // Internal helper: degradation + player skip line
    // -------------------------------------------------------------------------

    /**
     * Records a waypoint degradation note in the run state and emits a player skip line.
     * Never throws.
     */
    private static void skip(AgenticExecutionContext context, PlayerEngineController mod,
                              String reasonToken, DegradationLevel level) {
        try {
            context.runState().setWaypointDegraded(level, reasonToken);
            mod.reportAgenticProgress(WaypointReportFormatter.autoSkippedLine(reasonToken), false);
            PlayerEngine.LOGGER.debug(
                    "WaypointAutoRegistrar: skipped auto-registration (level={} reason={}).",
                    level, reasonToken);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("WaypointAutoRegistrar: skip() threw: {}", e.getMessage());
        }
    }
}
