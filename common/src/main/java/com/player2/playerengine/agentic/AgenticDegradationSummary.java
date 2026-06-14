package com.player2.playerengine.agentic;

import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure deterministic function — no I/O, no model call, no Player2 API.
 *
 * <p>Reads ONLY the structured degraded signals on {@link AgenticRunState} (never the *Progress
 * strings, which are rewritten unconditionally on clean runs). Returns "" when all signals are
 * CLEAN, guaranteeing the clean-success path produces no spurious note.
 */
public final class AgenticDegradationSummary {

    private AgenticDegradationSummary() {}

    /**
     * Assemble a model-register degradation summary for an otherwise-succeeded agentic run.
     *
     * @param s the run state whose degradation signals to read
     * @return "" when all signals are CLEAN (full success); a "; "-joined clause string otherwise
     */
    public static String forModel(AgenticRunState s) {
        if (s == null) {
            return "";
        }
        List<String> clauses = new ArrayList<>();

        // --- Gather ---
        if (s.getGatherDegradation() != DegradationLevel.CLEAN) {
            clauses.add(gatherClause(s.getGatherDegradationReason()));
        }

        // --- Deposit ---
        if (s.getDepositDegradation() != DegradationLevel.CLEAN) {
            clauses.add(depositClause(s.getDepositDegradationReason()));
        }

        // --- Label ---
        if (s.getLabelDegradation() != DegradationLevel.CLEAN) {
            clauses.add(labelClause(s.getLabelDegradationReason()));
        }

        // --- Waypoint (EllieGPS auto-registration) ---
        if (s.getWaypointDegradation() != DegradationLevel.CLEAN) {
            clauses.add(waypointClause(s.getWaypointDegradation(), s.getWaypointDegradationReason()));
        }

        return String.join("; ", clauses);
    }

    // -----------------------------------------------------------------------------------------
    // Per-kind clause builders — deterministic, defensive (fall through to generic form).
    // -----------------------------------------------------------------------------------------

    private static String gatherClause(String reason) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("full")) {
            return "gather partial: inventory full, some drops left on the ground";
        }
        if (r.contains("timeout")) {
            return "gather partial: timed out, some reachable drops not collected";
        }
        // Generic fallback preserves the actual reason text for the model.
        return reason.isBlank() ? "gather partial" : "gather partial: " + reason;
    }

    private static String depositClause(String reason) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("container_full")) {
            return "deposit partial: chest full, some items not stored";
        }
        if (r.contains("remaining")) {
            return "deposit partial: some items of the request not stored";
        }
        if (r.contains("nothing_to_deposit")) {
            return "deposit no-op: nothing was deposited";
        }
        // Generic fallback.
        return reason.isBlank() ? "deposit partial" : "deposit partial: " + reason;
    }

    private static String waypointClause(DegradationLevel level, String reason) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        // PARTIAL means a scan failure occurred (chest was reachable but the scan didn't succeed)
        if (level == DegradationLevel.PARTIAL) {
            return reason.isBlank()
                    ? "waypoint not registered: scan failed after deposit"
                    : "waypoint not registered: scan failed (" + reason + ")";
        }
        // SKIPPED cases — named reason tokens from WaypointAutoRegistrar
        if (r.contains("worldgen_loot")) {
            return "waypoint not registered: container is worldgen loot (origin_rejected)";
        }
        if (r.contains("structure_piece")) {
            return "waypoint not registered: container is inside a structure piece (origin_rejected)";
        }
        if (r.contains("origin_rejected") || r.contains("origin_unverified")) {
            return "waypoint not registered: origin could not be verified; use create_waypoint to register explicitly";
        }
        if (r.contains("no_storage_target")) {
            return "waypoint not registered: no storage target was recorded for this run";
        }
        if (r.contains("store_unavailable")) {
            return "waypoint not registered: EllieGPS store was unavailable";
        }
        if (r.contains("dimension_mismatch")) {
            return "waypoint not registered: dimension mismatch between target and current world";
        }
        if (r.contains("registrar_exception")) {
            return "waypoint not registered: internal error in auto-registrar (run was not affected)";
        }
        // Generic fallback preserves the reason token for the model.
        return reason.isBlank()
                ? "waypoint not registered"
                : "waypoint not registered: " + reason;
    }

    private static String labelClause(String reason) {
        if (reason == null) {
            reason = "";
        }
        // A sign craft WAS attempted and failed/stalled — tell the model the underlying cause so it
        // can answer truthfully and not retry the identical request expecting a different outcome.
        if (reason.startsWith("sign_craft_failed: ")) {
            return "chest labeling skipped: tried to craft a sign but could not ("
                    + reason.substring("sign_craft_failed: ".length())
                    + ") (chest stored, not labeled)";
        }
        if (reason.equals("sign_craft_unavailable")) {
            return "chest labeling skipped: no sign item and sign crafting is disabled"
                    + " (chest stored, not labeled)";
        }
        // Legacy/defensive: the no-sign path now crafts first (see sign_craft_* above).
        if (reason.equals("no_sign_item")) {
            return "chest labeling skipped: no sign item in inventory (chest stored, not labeled)";
        }
        if (reason.equals("no_label_anchor")) {
            return "chest labeling skipped: no valid anchor to place a sign";
        }
        // Generic fallback for label_disabled, label_timeout, label_skipped:*, interrupted, etc.
        return reason.isBlank() ? "chest labeling skipped" : "chest labeling skipped: " + reason;
    }
}
