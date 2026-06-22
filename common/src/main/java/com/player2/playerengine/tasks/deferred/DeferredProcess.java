package com.player2.playerengine.tasks.deferred;

import com.player2.playerengine.PlayerEngineController;
import net.minecraft.core.BlockPos;

/**
 * Generic abstraction for a long-running, station-based background process (furnace smelting,
 * brewing, campfire cooking, etc.) that a bot can start, yield from, and resume later.
 *
 * <p>The Minecraft Block Entity at {@link #stationPos()} is always the source of truth for
 * completion and progress. The {@link #etaGameTime()} is a planning hint only — it is NEVER the
 * completion gate.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Read live BE state on every {@link #poll} call (no cached internal progress timer).</li>
 *   <li>Gate any "is the station making progress" check on a ticking-level chunk check, not merely
 *       a chunk-loaded check (only a block-ticking chunk advances a furnace or brewing stand).</li>
 *   <li>Carry no forced-load tickets; checks only.</li>
 * </ul>
 *
 * <p>v1 ships the furnace-family adapter ({@code DeferredCookProcess}). The interface is
 * intentionally generic so brewing/campfire/breeding/growth can plug in without redesign.
 *
 * <p>Poll cadence: callers (e.g. {@code SmeltDeferredTask.onTick()}) poll once per server tick
 * (20 polls/second, returning {@code null} to park between polls). Implementations must be
 * idempotent and cheap on every call.
 */
public interface DeferredProcess {

    /**
     * Short, machine-readable identifier for this process type.
     * Examples: {@code "smelt"}, {@code "blast"}, {@code "smoke"}.
     *
     * <p>Stored in {@link DeferredJobRecord#kind} for persistence and logging.
     */
    String kind();

    /**
     * The world position of the processing station (furnace, brewing stand, campfire, etc.).
     * Stored in the job record so resume paths never depend on scanner proximity.
     */
    BlockPos stationPos();

    /**
     * The game time (in ticks, from {@code Level.getGameTime()}) when the process was started.
     * Used for timeout calculations and job records.
     */
    long startGameTime();

    /**
     * Estimated completion game time. This is a PLANNING HINT computed from
     * {@code startGameTime + inputCount * cookTicksPerItem}; it is used only to decide
     * "when to come back" — it is NEVER used as the completion gate.
     *
     * <p>If the chunk is not simulated (block-ticking), the station makes no progress, and this
     * ETA is effectively paused. Callers must tolerate an ETA that is stale or past without
     * treating it as completion.
     */
    long etaGameTime();

    /**
     * Reads the current process state from the live Block Entity (and ticking check), returning
     * a snapshot of progress as of this tick.
     *
     * <p>Must be cheap and side-effect-free (read-only). Called once per server tick by the
     * owning task while it is parked.
     *
     * @param controller the active {@link PlayerEngineController} (provides world, chunk-source,
     *                   and block access).
     * @return a fresh {@link DeferredProgress} snapshot. Never null.
     */
    DeferredProgress poll(PlayerEngineController controller);

    /**
     * Returns {@code true} when the process has successfully completed according to the live BE
     * state captured in {@code progress}. On true, the task should advance to the collect step.
     *
     * <p>Completion is decided by real BE state (e.g. output-slot count + input drained), NOT
     * by elapsed game time.
     */
    boolean isComplete(DeferredProgress progress);

    /**
     * Returns the {@link DeferredDegradation} classification for the given progress snapshot,
     * or {@code null} if the process is healthy (in-progress or complete without anomaly).
     *
     * <p>Degradation cases (station-dependent, enumerated in the furnace adapter):
     * out-of-fuel (lit time zero mid-batch), stalled (chunk not ticking for N consecutive polls
     * while incomplete), tampered/missing output, station-gone (BE no longer present).
     */
    DeferredDegradation degradationOf(DeferredProgress progress);
}
