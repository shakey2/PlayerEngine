package com.player2.playerengine.memory.reflection;

import com.player2.playerengine.memory.MemoryStore;

/**
 * Decides <b>when</b> a reflection should run (Phase D, W6). Pure and deterministic — no LLM, no
 * Minecraft, no loader, no I/O. <b>Embedding-free and NOT clustering</b>: the trigger is a single
 * cumulative-importance threshold crossing, not a similarity/cluster computation.
 *
 * <p>Two trigger paths:
 * <ol>
 *   <li><b>Mid-session threshold</b> ({@link #shouldReflect}): fires once the companion has
 *       accumulated more than {@code reflectionImportanceThreshold} importance-points worth of new
 *       memories since the last reflection ({@link MemoryStore#cumulativeImportanceSinceLastReflection()}).
 *       W6's {@code ImportanceScorer}/extraction path feeds the counter via
 *       {@code MemoryStore.addCumulativeImportance}; {@code setRelationshipSummary} resets it to 0.</li>
 *   <li><b>Session-end</b> ({@link #shouldReflectAtSessionEnd}): a best-effort flush at despawn /
 *       owner-disconnect / SERVER_STOPPING so a sub-threshold-but-non-trivial session still produces
 *       a summary. Its lifecycle WIRING (ordering vs. executor shutdown) is a serial integration item —
 *       this class only states the <em>predicate</em> (any unreflected importance accumulated).</li>
 * </ol>
 *
 * <p>The numeric threshold is a playtest-tunable placeholder ({@link #DEFAULT_IMPORTANCE_THRESHOLD});
 * the serial config pass supplies it via {@code reflectionImportanceThreshold} at the call site.
 */
public final class ReflectionTrigger {

    /**
     * Default cumulative-importance threshold for the mid-session trigger (plan
     * {@code reflectionImportanceThreshold} ≈ 150). Playtest-tunable placeholder.
     */
    public static final long DEFAULT_IMPORTANCE_THRESHOLD = 150L;

    /**
     * Minimum unreflected importance for the session-end flush to be worth a reflection. Avoids
     * spending an LLM budget slot on a near-empty session at every disconnect. Conservative default;
     * the session-end hook may use {@code > 0} to always flush, or this floor to be frugal.
     */
    public static final long DEFAULT_SESSION_END_MIN_IMPORTANCE = 20L;

    private ReflectionTrigger() {}

    /**
     * Mid-session trigger: true iff the store's cumulative unreflected importance exceeds
     * {@code threshold}. Off-tick reflection should be dispatched ONCE per crossing; the dispatcher
     * (or {@link ReflectionService}) must avoid re-dispatching while a reflection is in flight, and
     * the counter is reset by {@code MemoryStore.setRelationshipSummary} when the reflection completes.
     *
     * @param store     the companion store (null → false)
     * @param threshold the cumulative-importance threshold ({@code reflectionImportanceThreshold});
     *                  non-positive falls back to {@link #DEFAULT_IMPORTANCE_THRESHOLD}
     */
    public static boolean shouldReflect(MemoryStore store, long threshold) {
        if (store == null) return false;
        long effective = (threshold > 0) ? threshold : DEFAULT_IMPORTANCE_THRESHOLD;
        return store.cumulativeImportanceSinceLastReflection() > effective;
    }

    /** Convenience overload using {@link #DEFAULT_IMPORTANCE_THRESHOLD}. */
    public static boolean shouldReflect(MemoryStore store) {
        return shouldReflect(store, DEFAULT_IMPORTANCE_THRESHOLD);
    }

    /**
     * Session-end trigger predicate: true iff there is at least {@code minImportance} unreflected
     * importance to summarize. The session-end hook calls this BEFORE companion teardown (see the
     * binding ordering in the W6 integration spec) and, if true, enqueues the reflection with the
     * billing context captured while it is still live.
     *
     * @param store         the companion store (null → false)
     * @param minImportance floor below which a session-end reflection is skipped (non-positive →
     *                      {@link #DEFAULT_SESSION_END_MIN_IMPORTANCE})
     */
    public static boolean shouldReflectAtSessionEnd(MemoryStore store, long minImportance) {
        if (store == null) return false;
        long floor = (minImportance > 0) ? minImportance : DEFAULT_SESSION_END_MIN_IMPORTANCE;
        return store.cumulativeImportanceSinceLastReflection() >= floor;
    }
}
