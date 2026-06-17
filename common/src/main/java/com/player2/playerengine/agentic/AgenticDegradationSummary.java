package com.player2.playerengine.agentic;

import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure deterministic function — no I/O, no model call, no Player2 API.
 *
 * <p>The DegradationLevel signals on {@link AgenticRunState} are the ONLY clean-vs-degraded signal
 * (the *Progress strings are rewritten unconditionally and MUST NOT be used for that decision). On a
 * CLEAN step the *Progress strings ARE read, but solely to extract FACTUAL success counts/targets
 * (deposited N into chest at x y z, gathered N) so the model receives the real outcome instead of a
 * generic "finished running" it would otherwise confabulate (DESIGN.md §3). Returns "" only when
 * every signal is CLEAN and no success facts were recorded.
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
        } else {
            // CLEAN gather still needs a FACTUAL note so the model does not confabulate the outcome.
            // gatherProgress is rewritten on every run including success; read it ONLY for the count.
            String clause = gatherSuccessClause(s.getGatherProgress());
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
        }

        // --- Deposit ---
        if (s.getDepositDegradation() != DegradationLevel.CLEAN) {
            clauses.add(depositClause(s.getDepositDegradationReason()));
        } else {
            // CLEAN deposit (the most important outcome to report truthfully): a successful deposit
            // previously left the model with only a generic "finished running", so it would invent
            // "Iron is stored!". Surface the deposited count + the resolved chest coordinates.
            String clause = depositSuccessClause(s.getDepositProgress(), s.getStorageTargetSummary());
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
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
    // CLEAN-success fact clauses — extract a count (and chest target) from the *Progress strings so
    // the model receives the real outcome on a clean run. Empty string => no factual note to add.
    // -----------------------------------------------------------------------------------------

    private static String depositSuccessClause(String progress, String storageTarget) {
        int n = parseToken(progress, "deposited=");
        if (n <= 0) {
            // 0 / unparseable: a genuine no-op is already surfaced by the SKIPPED branch
            // (nothing_to_deposit); nothing factual to add here.
            return "";
        }
        String clause = "deposited " + n + " item(s)";
        if (storageTarget != null && !storageTarget.isBlank()) {
            clause += " into " + storageTarget.trim();
        }
        return clause;
    }

    private static String gatherSuccessClause(String progress) {
        int n = parseToken(progress, "gathered=");
        return n > 0 ? "gathered " + n + " item(s)" : "";
    }

    /**
     * Extracts the integer immediately following {@code token} (e.g. "deposited=") in a *Progress
     * string. Returns -1 when the token is absent or the value is not a parseable non-negative int.
     */
    private static int parseToken(String progress, String token) {
        if (progress == null || token == null) {
            return -1;
        }
        int i = progress.indexOf(token);
        if (i < 0) {
            return -1;
        }
        int start = i + token.length();
        int end = start;
        while (end < progress.length() && Character.isDigit(progress.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(progress.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
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
            // A deposit no-op almost always means the requested resource was never in the bot's
            // inventory and agentic could not obtain it: the only agentic resource step is
            // gather_loose_items (loose DROPS already on the floor) — there is NO mine/get step. So a
            // bare "nothing was deposited" leaves the model looping on agentic variants (deposit/store/
            // gather+deposit) that all no-op for the same reason. Make the no-op TRUTHFUL and ACTIONABLE
            // (DESIGN.md §3): tell the model the resource was never obtained and to use 'get' FIRST,
            // mirroring the pure-mine redirect (AgenticPlannerService.mineGoalRedirectMessage). This
            // clause reaches the MODEL via finishWithNote ("finished running, but: …"). The PLAYER gets
            // the tailored line independently from DepositItemsTask.describeDepositOutcome -> report(…,
            // true); both channels fire, so the model can self-correct in one turn instead of looping.
            return "deposit no-op: nothing was deposited — the requested item was not in inventory and"
                    + " agentic cannot mine or gather raw resources (it only picks up loose drops and"
                    + " stores them). Use 'get <item> <count>' to obtain it first, then deposit.";
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
