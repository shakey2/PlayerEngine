package com.player2.playerengine.tasks.deferred;

/**
 * Classification of a {@link DeferredProcess} degradation condition, as determined by
 * {@link DeferredProcess#degradationOf(DeferredProgress)}.
 *
 * <p>Each case maps to a distinct dual-audience reporting outcome (player chat line + model
 * feedback) per DESIGN.md §3. See the degradation table in the smelt plan (WS8) for the
 * exact player lines and model-channel routing for each case.
 *
 * <p>{@code null} returned from {@link DeferredProcess#degradationOf} means no degradation
 * (healthy in-progress or completed cleanly). Only the named enum values below represent
 * actual anomalies.
 */
public enum DeferredDegradation {

    /**
     * The processing station Block Entity no longer exists at the recorded position — the
     * furnace (or other station) was removed or replaced while the bot was away.
     *
     * <p>Terminal: the job cannot be resumed. Report to both player and model as an error.
     * Model channel: {@code finishWithError("furnace removed at x y z")} (command path) /
     * {@code setSmeltDegraded(SKIPPED, "furnace_gone")} (agentic path).
     */
    STATION_GONE,

    /**
     * The fuel slot emptied mid-batch while input items remain, and the station is no longer
     * lit (no more cooking will occur without additional fuel). Depending on whether any items
     * were completed before the fuel ran out, this may be a PARTIAL yield or a total failure.
     *
     * <p>Sub-cases are distinguished by the output count in {@link DeferredProgress}:
     * <ul>
     *   <li>Output count == expected → clean (should not reach here; classified as complete).</li>
     *   <li>0 < output count < expected → partial yield (report as partial).</li>
     *   <li>Output count == 0 → nothing produced (report as no-fuel failure).</li>
     * </ul>
     *
     * <p>Model channel: {@code finishWithNote} (partial) or {@code finishWithError} (none).
     */
    OUT_OF_FUEL,

    /**
     * The output slot holds fewer items than expected, and neither an out-of-fuel nor a
     * station-gone condition explains the shortfall — the contents were likely tampered with
     * (a player or another process removed items from the furnace while the bot was away).
     *
     * <p>Non-terminal if the bot can collect what remains; terminal if nothing is collectible.
     * Model channel: {@code finishWithNote("tampered: expected M, collected N")}.
     */
    TAMPERED_OUTPUT,

    /**
     * The chunk containing the station has been non-simulated (block-ticking level) for
     * {@code deferredSmeltStallPolls} consecutive polls (≈10 seconds at 20 polls/sec) while
     * the job is incomplete, AND the bot cannot reach the furnace to keep the chunk ticking.
     *
     * <p>SOFT degradation: the bot intends to retry/resume. Routes as {@code finishWithNote}
     * (degraded-success-with-retry) on the command path; {@code setSmeltDegraded(PARTIAL, ...)}
     * on the agentic path.
     *
     * <p>Escalates to {@link #STALLED_TIMEOUT} when the real-time hard-timeout
     * ({@code deferredSmeltTimeoutSeconds}) expires.
     */
    STALLED_RETRYING,

    /**
     * The hard real-time timeout ({@code deferredSmeltTimeoutSeconds}, default 600 s) for this
     * job has elapsed while the chunk remained non-simulated and the job is still incomplete.
     *
     * <p>Terminal: give up. Routes as {@code finishWithError} (command path) /
     * {@code setSmeltDegraded(SKIPPED, "stalled_timeout")} (agentic path).
     */
    STALLED_TIMEOUT;

    /**
     * Returns a short, machine-readable label for this degradation (used in model-facing notes
     * and job records). Format: {@code snake_case}, lowercase.
     */
    public String label() {
        return name().toLowerCase();
    }
}
