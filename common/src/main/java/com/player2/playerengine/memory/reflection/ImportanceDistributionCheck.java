package com.player2.playerengine.memory.reflection;

import com.player2.playerengine.PlayerEngine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advisory, run-once-per-store calibration check for the importance scorer (Phase D, W6). After the
 * first {@link #SAMPLE_SIZE} scored importances, if more than {@link #HIGH_RATIO_THRESHOLD} of them
 * land in the high band (7–10) it logs a single bounded WARN naming the store and the ratio, then
 * marks itself done (a persisted {@code importanceCheckDone} flag prevents re-running).
 *
 * <p><b>This is DISK/CONSOLE ONLY and NEVER model-facing.</b> The WARN text is a short, fully
 * templated phrase ({@code "%s"} format args = store label + ratio numbers) — no log content, stack
 * trace, {@code Throwable}, or unbounded external string. It uses {@link PlayerEngine#LOGGER}
 * (log4j), the sanctioned disk/console sink, and must never feed any model-facing surface
 * (DESIGN.md §3 egress hard rule). The check gates nothing — the importance scorer keeps running
 * regardless; this only flags a mis-calibrated rubric to the operator/dev.
 *
 * <p>Level-0 {@code Debug} logs are silent in this engine, so this uses {@code LOGGER.warn} per the
 * memory note about diagnostics visibility.
 *
 * <h3>Threading</h3>
 * Counters are atomic so it is safe to call from the off-tick memory executor where importance is
 * scored. The persisted {@code importanceCheckDone} flag is owned by the caller (it lives in the
 * store's forward-compat root extras / a future field); this class exposes the in-memory gate and a
 * {@link #markDone()} the caller persists. v1 keeps the flag in-memory per store instance via the
 * {@link AtomicBoolean}; the caller may additionally persist it to survive restarts.
 */
public final class ImportanceDistributionCheck {

    /** Number of scored importances to observe before evaluating the distribution. */
    public static final int SAMPLE_SIZE = 30;

    /** Ratio in the high band (7–10) above which the rubric is flagged as clustering. */
    public static final double HIGH_RATIO_THRESHOLD = 0.60;

    /** Inclusive lower bound of the "high" importance band. */
    public static final int HIGH_BAND_MIN = 7;

    private final String storeLabel;
    private final AtomicInteger sampleCount = new AtomicInteger(0);
    private final AtomicInteger highCount = new AtomicInteger(0);
    private final AtomicBoolean done = new AtomicBoolean(false);

    /**
     * @param storeLabel a bounded, non-PII store identifier (e.g. {@code scope.toString()} /
     *                   {@code companionId}) — the caller must pass a short curated label, never
     *                   unbounded content.
     * @param alreadyDone the persisted {@code importanceCheckDone} flag (true → this check is inert)
     */
    public ImportanceDistributionCheck(String storeLabel, boolean alreadyDone) {
        this.storeLabel = storeLabel == null ? "?" : storeLabel;
        if (alreadyDone) {
            this.done.set(true);
        }
    }

    /** True once the check has fired (or was constructed already-done). The caller persists this. */
    public boolean isDone() {
        return done.get();
    }

    /** Marks the check done so it never runs again (caller persists {@code importanceCheckDone}). */
    public void markDone() {
        done.set(true);
    }

    /**
     * Records one scored importance (1–10). When the sample fills and the high-band ratio exceeds the
     * threshold, logs the single bounded WARN and marks done.
     *
     * @param importance the clamped stored importance (1–10); out-of-range values are still counted
     *                   in the sample but only 7–10 count toward the high band
     * @return {@code true} exactly on the call that fired the warning (for the caller to persist the
     *         flag), {@code false} otherwise
     */
    public boolean record(int importance) {
        if (done.get()) {
            return false;
        }
        if (importance >= HIGH_BAND_MIN && importance <= 10) {
            highCount.incrementAndGet();
        }
        int n = sampleCount.incrementAndGet();
        if (n < SAMPLE_SIZE) {
            return false;
        }
        // Reached the sample size — evaluate once, then latch done.
        if (!done.compareAndSet(false, true)) {
            return false; // another thread already evaluated
        }
        int high = highCount.get();
        double ratio = (double) high / (double) n;
        if (ratio > HIGH_RATIO_THRESHOLD) {
            // Bounded, fully templated — store label + integer/ratio only. Never model-facing.
            PlayerEngine.LOGGER.warn(
                    "Memory importance distribution skewed for {}: {} of {} scores in 7-10 ({}%). "
                            + "Rubric may be clustering high; consider tuning ImportanceScorer prompt.",
                    storeLabel, high, n, Math.round(ratio * 100.0));
            return true;
        }
        return false;
    }
}
