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

        // --- Smelt (deferred cooking: smelt_items) ---
        if (s.getSmeltDegradation() != DegradationLevel.CLEAN) {
            clauses.add(smeltClause(s.getSmeltDegradationReason()));
        } else {
            // CLEAN smelt still needs a FACTUAL note so the model reports the real count rather than
            // confabulating a generic "finished running" (DESIGN.md §3). smeltProgress is rewritten on
            // every run including success; read it ONLY for the count, never as a clean-vs-degraded
            // signal (the DegradationLevel field above is the signal).
            String clause = smeltSuccessClause(s.getSmeltProgress());
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
        }

        // --- Smith (smithing-table upgrade: smith_items) ---
        if (s.getSmithDegradation() != DegradationLevel.CLEAN) {
            clauses.add(smithClause(s.getSmithDegradationReason()));
        } else {
            // CLEAN smith still needs a FACTUAL note so the model reports the real count rather than
            // confabulating a generic "finished running" (DESIGN.md §3). smithProgress is rewritten on
            // every run including success; read it ONLY for the count, never as a clean-vs-degraded
            // signal (the DegradationLevel field above is the signal).
            String clause = smithSuccessClause(s.getSmithProgress());
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
        }

        // --- Mine (generic block mining: mine_block) ---
        if (s.getMineDegradation() != DegradationLevel.CLEAN) {
            clauses.add(mineClause(s.getMineDegradationReason(), s.getMineProgress()));
        } else {
            // CLEAN mine still needs a FACTUAL note so the model reports the real count rather than
            // confabulating a generic "finished running" (DESIGN.md §3). mineProgress is rewritten on
            // every run including success; read it ONLY for the count, never as a clean-vs-degraded
            // signal (the DegradationLevel field above is the signal).
            String clause = mineSuccessClause(s.getMineProgress());
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
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
     * Clean-smelt factual clause: extract the smelted count (and output item name when present) from
     * the smeltProgress string written by the agentic smelt step, e.g. {@code "smelted=16
     * item=minecraft:iron_ingot"}. Empty string when nothing parseable was recorded.
     */
    private static String smeltSuccessClause(String progress) {
        int n = parseToken(progress, "smelted=");
        if (n <= 0) {
            return "";
        }
        String item = parseStringToken(progress, "item=");
        return (item != null && !item.isBlank())
                ? "smelted " + n + " " + item
                : "smelted " + n + " item(s)";
    }

    /**
     * Clean-smith factual clause: extract the upgraded count (and output item name when present)
     * from the smithProgress string written by the agentic smith step, e.g.
     * {@code "upgraded=1 item=minecraft:netherite_pickaxe"}. Empty string when nothing
     * parseable was recorded.
     */
    private static String smithSuccessClause(String progress) {
        int n = parseToken(progress, "upgraded=");
        if (n <= 0) {
            return "";
        }
        String item = parseStringToken(progress, "item=");
        return (item != null && !item.isBlank())
                ? "upgraded " + n + " " + item + " at the smithing table"
                : "upgraded " + n + " item(s) at the smithing table";
    }

    /**
     * Clean-mine factual clause: extract the mined count (and target block name when present) from the
     * mineProgress string written by the agentic mine_block step, e.g.
     * {@code "mined=3 noDrops=0 block=minecraft:cobblestone"}. Empty string when nothing parseable
     * was recorded.
     */
    private static String mineSuccessClause(String progress) {
        int n = parseToken(progress, "mined=");
        if (n <= 0) {
            return "";
        }
        String block = parseStringToken(progress, "block=");
        return (block != null && !block.isBlank())
                ? "mined " + n + " " + block
                : "mined " + n + " block(s)";
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

    /**
     * Extracts the whitespace-delimited string value immediately following {@code token} (e.g.
     * "item=") in a *Progress string. Returns null when the token is absent or empty.
     */
    private static String parseStringToken(String progress, String token) {
        if (progress == null || token == null) {
            return null;
        }
        int i = progress.indexOf(token);
        if (i < 0) {
            return null;
        }
        int start = i + token.length();
        int end = start;
        while (end < progress.length() && !Character.isWhitespace(progress.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        return progress.substring(start, end);
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

    /**
     * Renders a deferred-smelt degradation reason token into a model-facing clause. Tokens are the
     * machine-readable reasons recorded by the agentic smelt step (see {@code SmeltStepFactory}),
     * derived from {@code SmeltDeferredTask.OutcomeKind} / {@link
     * com.player2.playerengine.tasks.deferred.DeferredDegradation#label()}. Defensive: unknown
     * tokens fall through to a generic, reason-preserving form so the model still receives the truth.
     */
    private static String smeltClause(String reason) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        // Fuel pre-gather outcomes (checked FIRST, before the generic 'partial'/'out_of_fuel'/'no_fuel'
        // matches below, so the distinctive 'fuel_gather' stem is never swallowed by them). The agentic
        // smelt step auto-gathered fuel before cooking; two honest forms:
        if (r.contains("fuel_gather")) {
            if (r.contains("no_acquirable")) {
                // ZERO: the gather found no usable fuel anywhere.
                return "smelt failed: tried to gather fuel automatically but none was acquirable "
                        + "(no coal/chests in range, no trees, or no pickaxe)";
            }
            // PARTIAL: gathered some fuel, but only enough for part of the batch. Parse 'collected=N of M'.
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("collected=(\\d+)\\s+of\\s+(\\d+)").matcher(r);
            if (m.find()) {
                return "smelt partial: auto-gathered fuel but only enough for " + m.group(1)
                        + " of " + m.group(2);
            }
            return "smelt partial: auto-gathered fuel but only enough for part of the batch ("
                    + reason + ")";
        }
        if (r.contains("partial") || r.contains("out_of_fuel")) {
            // Partial yield: some items cooked, then the fuel ran out mid-batch.
            return "smelt partial: ran out of fuel mid-batch — collected what finished (" + reason + ")";
        }
        if (r.contains("no_fuel")) {
            return "smelt failed: no fuel was available to start the cook";
        }
        if (r.contains("no_recipe")) {
            return "smelt failed: that item has no furnace/blast/smoke recipe (nothing was cooked)";
        }
        if (r.contains("furnace_gone") || r.contains("station_gone")) {
            return "smelt failed: the furnace was removed while I was away (couldn't finish)";
        }
        if (r.contains("tampered")) {
            return "smelt partial: the furnace contents changed while I was away — collected what I could";
        }
        if (r.contains("stalled_timeout")) {
            return "smelt failed: the furnace chunk stopped ticking and the job timed out before it finished";
        }
        if (r.contains("stalled")) {
            return "smelt incomplete: the furnace chunk stopped ticking — will finish when back near the furnace";
        }
        if (r.contains("no_furnace")) {
            return "smelt failed: no furnace, blast furnace, or smoker was reachable";
        }
        if (r.contains("no_input")) {
            return "smelt skipped: I don't have that item to smelt";
        }
        // Generic fallback preserves the reason token for the model.
        return reason.isBlank() ? "smelt incomplete" : "smelt incomplete: " + reason;
    }

    /**
     * Renders a deferred-smith degradation reason token into a model-facing clause. Tokens are the
     * machine-readable reasons recorded by the agentic smith step (see {@code SmithStepFactory}),
     * derived from {@code SmithDeferredTask.OutcomeKind}. Defensive: unknown tokens fall through
     * to a generic, reason-preserving form so the model still receives the truth.
     */
    private static String smithClause(String reason) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("partial")) {
            return "smith partial: upgraded some items but could not complete the full batch ("
                    + reason + ")";
        }
        if (r.contains("tampered")) {
            return "smith partial: smithing table or inventory changed mid-run — upgraded what was possible";
        }
        if (r.contains("no_table")) {
            return "smith failed: no smithing table was reachable or placeable";
        }
        if (r.contains("no_recipe")) {
            return "smith failed: nothing in the smithing registry produces that item";
        }
        if (r.contains("missing_template")) {
            return "smith failed: could not gather the required template item (" + reason + ")";
        }
        if (r.contains("missing_base")) {
            return "smith failed: could not gather the required base item (" + reason + ")";
        }
        if (r.contains("missing_addition")) {
            return "smith failed: could not gather the required upgrade material (" + reason + ")";
        }
        if (r.contains("setup_failed")) {
            return "smith failed: could not start the upgrade (" + reason + ")";
        }
        if (r.contains("no_input")) {
            return "smith skipped: no item was specified or resolved";
        }
        // Generic fallback preserves the reason token for the model.
        return reason.isBlank() ? "smith incomplete" : "smith incomplete: " + reason;
    }

    /**
     * Renders a mine_block degradation reason token into a model-facing clause. The mine step records
     * three partial degradations, all PARTIAL level: the no-drops-on-incorrect-tool case
     * ({@code incorrect_tool_no_drops}), a genuine overall timeout ({@code timeout} — more blocks may
     * remain, retrying can continue), and range exhaustion ({@code no_more_blocks_in_range} — no more
     * matching blocks within reach, the bot must RELOCATE to mine more). The factual mined/no-drops
     * count and target block are parsed from {@code mineProgress}
     * ({@code "mined=<N> noDrops=<M> block=<id>"}). Hard failures (no tool / unknown block / no
     * target) do NOT pass through here — they surface via {@code progressForKind("mine_block")}.
     * Defensive: unknown tokens fall through to a generic, reason-preserving form.
     */
    private static String mineClause(String reason, String progress) {
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("incorrect_tool") || r.contains("no_drops")) {
            int noDrops = parseToken(progress, "noDrops=");
            String block = parseStringToken(progress, "block=");
            String what = (block != null && !block.isBlank()) ? block : "block(s)";
            if (noDrops > 0) {
                return "broke " + noDrops + " " + what
                        + " with an insufficient tool — no drops collected";
            }
            return "broke a " + what + " with an insufficient tool — no drops collected";
        }
        if (r.contains("no_more_blocks_in_range")) {
            // Range exhaustion: NOT a timeout — actionable hint is to RELOCATE, the main motivation
            // for splitting this out from the timeout case.
            int mined = parseToken(progress, "mined=");
            String block = parseStringToken(progress, "block=");
            String what = (block != null && !block.isBlank()) ? block : "block(s)";
            return mined > 0
                    ? "mine partial: mined " + mined + " " + what
                            + " then exhausted range — no more within reach, relocate to mine more"
                    : "mine partial: no more " + what + " within reach — relocate to mine more";
        }
        if (r.contains("timeout")) {
            // Genuine overall timeout: more blocks may remain, retrying can continue.
            int mined = parseToken(progress, "mined=");
            String block = parseStringToken(progress, "block=");
            String what = (block != null && !block.isBlank()) ? block : "block(s)";
            return mined > 0
                    ? "mine partial: timed out after " + mined + " " + what
                            + " — more may remain, retrying can continue"
                    : "mine partial: timed out — more may remain, retrying can continue";
        }
        // Generic fallback preserves the reason token for the model.
        return reason.isBlank() ? "mine partial" : "mine partial: " + reason;
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
