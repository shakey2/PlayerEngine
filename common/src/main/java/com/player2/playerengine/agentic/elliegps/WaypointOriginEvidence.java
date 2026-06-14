package com.player2.playerengine.agentic.elliegps;

/**
 * Immutable capture of the placement-origin classifier result for the EllieGPS auto-hook
 * (Part C5, WS3 / Parallelization plan "contract-first" items).
 *
 * <p>Stored in {@code AgenticExecutionMemory} alongside the storage target. WS3 populates
 * this at target-confirm time in {@code ResolveStorageChestTask} — before any deposit contact
 * with the container — so the Tier 2 worldgen-loot marker is never destroyed before it is
 * evaluated. WS6 reads it in {@code WaypointAutoRegistrar.afterDeposit}.
 *
 * <p>Tier 1 (bot-placed) is captured via {@link #placedByBot}; Tiers 2–3 are captured via
 * {@link #verdict} and {@link #reasonToken}.
 *
 * @param placedByBot   true when the bot physically placed the container ({@code placedByBot}
 *                      on {@code AgenticStorageTarget} is 100 % reliable — set by
 *                      {@code ResolveStorageChestTask} after {@code WorldHelper.isBlock} confirm)
 * @param verdict       the {@code WaypointOriginClassifier} tier-2/3 verdict
 *                      ({@code REGISTER}, {@code REJECT_WORLDGEN}, {@code REJECT_STRUCTURE},
 *                      or {@code UNKNOWN}); ignored when {@code placedByBot} is true
 * @param reasonToken   short machine token describing the rejection reason (e.g.
 *                      {@code "worldgen_loot"}, {@code "structure_piece"},
 *                      {@code "origin_unverified"}) or empty string when not rejected
 * @param captureGameTime the {@code level.getGameTime()} at which this evidence was captured
 *                        (must be before any container contact)
 */
public record WaypointOriginEvidence(
        boolean placedByBot,
        Verdict verdict,
        String reasonToken,
        long captureGameTime
) {
    /**
     * The verdict returned by the Tier 2–3 classifier.
     *
     * <ul>
     *   <li>{@link #REGISTER} — no worldgen or structure signal; eligible for auto-registration.</li>
     *   <li>{@link #REJECT_WORLDGEN} — un-opened worldgen loot-table marker present (certain negative).</li>
     *   <li>{@link #REJECT_STRUCTURE} — structure-piece bounding box hit (heuristic negative).</li>
     *   <li>{@link #UNKNOWN} — the classifier could not run (block entity missing, chunk unloaded,
     *       exception); the auto-hook treats this as do-not-register (conservative default).</li>
     * </ul>
     */
    public enum Verdict {
        REGISTER,
        REJECT_WORLDGEN,
        REJECT_STRUCTURE,
        UNKNOWN
    }

    /**
     * Convenience factory: bot-placed evidence (Tier 1, always REGISTER).
     */
    public static WaypointOriginEvidence botPlaced(long captureGameTime) {
        return new WaypointOriginEvidence(true, Verdict.REGISTER, "", captureGameTime);
    }

    /**
     * Returns true when this evidence permits auto-registration.
     *
     * <p>True iff {@link #placedByBot} is true, OR the Tier 2–3 verdict is
     * {@link Verdict#REGISTER}.
     */
    public boolean permitsRegistration() {
        return placedByBot || verdict == Verdict.REGISTER;
    }
}
