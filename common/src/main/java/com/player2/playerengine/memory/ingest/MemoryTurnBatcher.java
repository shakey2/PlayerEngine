package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryScope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-companion-scope buffer of gate-eligible curated turns (Phase D, W3). Accumulates eligible
 * turns until at least {@code batchMin} are buffered, then yields a batch to schedule exactly ONE
 * extraction call (never per-turn). Caps the buffer at {@code batchMax} so it never grows unbounded
 * even if extraction never fires (e.g. non-patron — though in that case nothing is ever offered
 * here, since the patron gate short-circuits upstream).
 *
 * <h3>Threading contract</h3>
 * All methods run on the SERVER THREAD (the ingestion-service entry point is server-thread). The
 * buffer is a plain {@code HashMap} keyed by {@link MemoryScope}; no synchronization is needed
 * because there is exactly one mutating thread. The drained batch is a detached copy handed to the
 * async executor — the executor never touches this batcher.
 *
 * <p>No Minecraft, loader, network, log, or stack-trace dependency.
 */
public final class MemoryTurnBatcher {

    private final Map<MemoryScope, List<String>> buffers = new HashMap<>();

    /**
     * Offers one eligible turn for the given scope. If, after adding, the buffer has reached
     * {@code batchMin}, the buffered turns are DRAINED and returned as a batch to extract; otherwise
     * {@code null} is returned and the turn stays buffered.
     *
     * <p>The buffer is hard-capped at {@code batchMax}: if adding would exceed it, the oldest turns
     * are dropped so the newest {@code batchMax} are retained (recency-biased) before the
     * batchMin check.
     *
     * @param scope    the per-companion scope
     * @param turnText the eligible curated turn text
     * @param batchMin flush floor (config {@code memoryExtractionBatchMin}, default 5)
     * @param batchMax buffer ceiling (config {@code memoryExtractionBatchMax}, default 10)
     * @return a detached batch to extract (size in {@code [batchMin, batchMax]}), or {@code null}
     */
    public List<String> offer(MemoryScope scope, String turnText, int batchMin, int batchMax) {
        if (scope == null || turnText == null || turnText.isBlank()) {
            return null;
        }
        int min = Math.max(1, batchMin);
        int max = Math.max(min, batchMax);

        List<String> buf = buffers.computeIfAbsent(scope, k -> new ArrayList<>());
        buf.add(turnText);

        // Hard cap: keep the newest `max` turns (drop oldest overflow).
        while (buf.size() > max) {
            buf.remove(0);
        }

        if (buf.size() >= min) {
            List<String> batch = new ArrayList<>(buf);
            buf.clear();
            return batch;
        }
        return null;
    }

    /** Current buffered count for a scope (test/telemetry; server thread). */
    public int bufferedCount(MemoryScope scope) {
        List<String> buf = buffers.get(scope);
        return buf == null ? 0 : buf.size();
    }

    /** Drops all buffered turns for a scope (e.g. on companion unload). Server thread. */
    public void discard(MemoryScope scope) {
        buffers.remove(scope);
    }

    /** Drops everything (e.g. SERVER_STOPPING). Server thread. */
    public void clear() {
        buffers.clear();
    }
}
