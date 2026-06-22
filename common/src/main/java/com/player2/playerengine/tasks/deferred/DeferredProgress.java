package com.player2.playerengine.tasks.deferred;

/**
 * A single-tick snapshot of a {@link DeferredProcess}'s live state, as read from the
 * Block Entity and chunk-ticking check.
 *
 * <p>All fields are read-only; implementations construct a new record per {@code poll()} call.
 * The BE is the source of truth: this record reflects what was actually observed at the station
 * this tick, NOT what the scheduler estimated.
 *
 * @param present        whether a compatible station Block Entity still exists at the process's
 *                       {@code stationPos}. {@code false} means the station was removed or
 *                       replaced — a terminal degradation (furnace-gone).
 * @param simulated      whether the chunk containing {@code stationPos} is at block-ticking
 *                       level this tick. A furnace (or any processing station) makes no progress
 *                       in a non-ticking chunk even if "loaded". Drives the stall counter.
 * @param outputCount    how many completed items are currently in the output slot (slot index 2
 *                       for the furnace family). Used as the primary completion signal together
 *                       with {@code inputDrained}.
 * @param inputCount     how many input items remain in the input slot (slot index 0 for the
 *                       furnace family). Zero with output present = completion.
 * @param fuelCount      how many fuel items remain in the fuel slot (slot index 1). Zero during
 *                       active cook = out-of-fuel degradation.
 * @param cookingProgress the furnace's {@code cookingProgress} counter (data index 2), 0 means
 *                        not cooking or just started. Range: 0..cookingTotalTime.
 * @param cookingTotalTime the furnace's {@code cookingTotalTime} (data index 3). Zero when the
 *                         furnace is idle or no recipe is loaded.
 * @param litTime         the furnace's {@code litTime} (data index 0), scaled on 1.21.1 when
 *                        litDuration > 32767; use only as a zero/nonzero check ("is still lit").
 *                        Do not use raw value for absolute timing calculations.
 * @param pollGameTime    the {@code Level.getGameTime()} value at the moment this snapshot was
 *                        taken. Used by the stall timeout logic.
 */
public record DeferredProgress(
        boolean present,
        boolean simulated,
        int outputCount,
        int inputCount,
        int fuelCount,
        int cookingProgress,
        int cookingTotalTime,
        int litTime,
        long pollGameTime
) {

    /**
     * Sentinel snapshot returned when the station's chunk is not loaded at all (i.e.
     * {@code getBlockEntity} would not be accessible). Distinct from {@code present=false}:
     * "not loaded" means we cannot even confirm whether the BE exists; "not present" means we
     * confirmed the BE is gone.
     *
     * <p>Implementations should prefer returning this over throwing when the world block is
     * inaccessible.
     */
    public static DeferredProgress notLoaded(long gameTime) {
        // present=false, simulated=false — callers treat this as "cannot determine state yet".
        return new DeferredProgress(false, false, 0, 0, 0, 0, 0, 0, gameTime);
    }

    /**
     * Returns {@code true} when the chunk is known to be ticking and the station BE is present
     * but the cooking counter has not advanced (litTime == 0 while some input remains). This
     * is the "out-of-fuel" signal when the fuel slot is also empty.
     *
     * <p>Note: this is only a heuristic trigger; the authoritative degradation classification
     * lives in {@link DeferredProcess#degradationOf(DeferredProgress)}.
     */
    public boolean isLit() {
        return litTime > 0;
    }

    /**
     * Returns {@code true} when the cooking counter is strictly between 0 and the total, i.e.
     * a recipe is actively progressing this tick. This does NOT require {@code litTime > 0}
     * on its own, since litTime can reach 0 just as a cooking cycle completes — use
     * {@link #isLit()} together with this when fuel checks are needed.
     */
    public boolean isCooking() {
        return cookingTotalTime > 0 && cookingProgress > 0 && cookingProgress < cookingTotalTime;
    }
}
