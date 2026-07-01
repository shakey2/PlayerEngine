package com.player2.playerengine.memory.dense;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.memory.budget.MemoryLlmClient;
import com.player2.playerengine.player2api.Player2PayerResolution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Off-tick cache of read-path <b>turn-query</b> embeddings (W8d, Bug-1 fix).
 *
 * <h3>Why this exists</h3>
 * The per-turn prompt (and therefore the memory block, and therefore
 * {@link com.player2.playerengine.memory.retrieval.MemoryRetriever#retrieve}) is assembled
 * <b>synchronously on the MC server tick thread</b> ({@code AgentConversationData.processChatWithAPI}
 * builds the history before the async LLM dispatch). {@link EmbeddingProvider#embed} hard-guards the
 * tick ({@link EmbeddingProvider#assertOffTick()} throws on {@code "Server thread"}) — correctly, because
 * a synchronous {@code POST /v1/embeddings} on the tick would stall the whole server on the network.
 *
 * <p>So the read-path turn-embed <b>cannot</b> be computed inline on the tick, and it must never block it.
 * This cache resolves the conflict with a <b>compute-off-tick, contribute-next-turn</b> strategy:
 * <ol>
 *   <li>On the tick, {@link #lookup(String)} is a non-blocking map read. A <b>hit</b> returns the vector
 *       so dense fusion runs this turn; a <b>miss</b> returns null (dense OFF this turn → the read path is
 *       byte-identical to the pre-W8 lexical/MinHash/graph fusion).</li>
 *   <li>On a miss, {@link #scheduleIfAbsent} enqueues the embed on
 *       {@link MemoryLlmClient#MEMORY_EXECUTOR} (off-tick, where {@code assertOffTick} passes). When it
 *       completes the vector lands in the cache, so a repeated / re-asked query on a later turn hits warm
 *       and contributes dense.</li>
 * </ol>
 *
 * <h3>Safety invariants</h3>
 * <ul>
 *   <li><b>Never blocks the tick.</b> The tick only ever does a {@link ConcurrentHashMap} read and an
 *       {@code executor.submit}; it never awaits the network.</li>
 *   <li><b>No data race.</b> The map is a {@link ConcurrentHashMap}; the in-flight set dedupes concurrent
 *       schedules for the same key so one text is embedded at most once at a time.</li>
 *   <li><b>Graceful degradation.</b> A miss (or any embed failure) simply leaves dense off that turn — the
 *       byte-identical fallback. The embed failure token is logged console-only by the provider.</li>
 *   <li><b>Bounded.</b> LRU-evicted at {@link #MAX_ENTRIES}; keys are hashed+length-tagged, not the raw
 *       text — the cache never becomes an unbounded string sink, and nothing here is model-facing.</li>
 * </ul>
 *
 * <p>The cache is process-global (keyed by the exact turn text) and intentionally simple: turn queries
 * are short and often repeated across a conversation, so even a small warm cache lifts dense on the
 * common "the user re-asks / rephrases the same thing" path without ever touching the tick's latency.
 */
public final class TurnEmbeddingCache {

    /** Max distinct turn-query vectors retained (LRU). Bounded so the cache is never a memory sink. */
    private static final int MAX_ENTRIES = 256;

    /** Cache-key length cap — raw turn text longer than this is embedded but not used as a verbatim key. */
    private static final int KEY_TEXT_CAP = 512;

    /** key -> computed query vector (frozen model). */
    private static final Map<String, float[]> CACHE = new ConcurrentHashMap<>();

    /** LRU order of keys (most-recent at tail). Guarded by {@link #CACHE}'s own concurrency + this deque. */
    private static final ConcurrentLinkedDeque<String> LRU = new ConcurrentLinkedDeque<>();

    /** Keys with an embed currently scheduled off-tick — dedupes concurrent schedules for one text. */
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private TurnEmbeddingCache() {}

    /**
     * Non-blocking cache read for {@code turnText}. Safe to call on the tick. Returns the frozen-model
     * query vector when warm, or null on a miss (caller degrades dense off this turn).
     */
    public static float[] lookup(String turnText) {
        String key = key(turnText);
        if (key == null) {
            return null;
        }
        float[] v = CACHE.get(key);
        if (v != null) {
            touch(key); // mark most-recently-used
        }
        return v;
    }

    /**
     * Schedules an OFF-TICK embed of {@code turnText} on {@link MemoryLlmClient#MEMORY_EXECUTOR} when the
     * key is absent and no embed is already in flight for it. Non-blocking — returns immediately. The
     * computed vector populates the cache for a future turn. Any failure is a silent no-op (dense stays
     * off; the provider logs the bounded token console-only).
     *
     * <p>Must be called with the OWNER's billing context (never the prompter's), matching every other
     * embed caller.
     */
    public static void scheduleIfAbsent(String turnText,
                                        PlayerEngineController controller,
                                        Player2PayerResolution.ApiBillingContext billing) {
        String key = key(turnText);
        if (key == null || controller == null
                || billing == null || billing.billingKey() == null
                || !EmbeddingProvider.isAvailable()) {
            return;
        }
        if (CACHE.containsKey(key)) {
            return; // already warm
        }
        if (!IN_FLIGHT.add(key)) {
            return; // an embed is already scheduled for this exact text
        }
        MemoryLlmClient.MEMORY_EXECUTOR.submit(() -> {
            try {
                // assertOffTick passes here (memory-llm thread, not "Server thread").
                EmbeddingResult r = EmbeddingProvider.embed(List.of(turnText), controller, billing);
                if (r.ok() && r.vectors().length > 0 && r.vectors()[0] != null) {
                    put(key, r.vectors()[0]);
                }
                // Degraded → bounded token already logged console-only by the provider; leave uncached.
            } catch (RuntimeException e) {
                // Never propagate: a background read-path embed failure must not disturb anything.
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    // -------------------------------------------------------------------------

    private static void put(String key, float[] vector) {
        if (CACHE.put(key, vector) == null) {
            LRU.addLast(key);
        } else {
            touch(key);
        }
        evictIfNeeded();
    }

    private static void touch(String key) {
        // Cheap LRU bump: remove + re-append. O(n) worst case on the deque, but MAX_ENTRIES is small.
        LRU.remove(key);
        LRU.addLast(key);
    }

    private static void evictIfNeeded() {
        while (CACHE.size() > MAX_ENTRIES) {
            String oldest = LRU.pollFirst();
            if (oldest == null) {
                break;
            }
            CACHE.remove(oldest);
        }
    }

    /**
     * Builds a bounded cache key from the turn text: trimmed, and for very long turns replaced by a
     * hash+length tag so the key set never grows unbounded on pathological input. Blank → null (no key).
     *
     * <p>For the long-text path the key is a compound fingerprint rather than a bare 32-bit
     * {@link String#hashCode()}: two distinct long turns of the same length would only collide (and so
     * return the wrong cached vector) if they matched on <em>all</em> of length, a 64-bit FNV-1a hash, a
     * 32-bit {@code hashCode}, and the leading/trailing text anchors — driving the collision probability
     * far below the birthday bound of a bare 32-bit hash. A miss/collision degrades gracefully (dense off
     * / slightly-off vector, never a crash), but this keeps long player messages correct.
     */
    private static String key(String turnText) {
        if (turnText == null) {
            return null;
        }
        String t = turnText.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() <= KEY_TEXT_CAP) {
            return t;
        }
        int cap = KEY_TEXT_CAP / 2;
        String head = t.substring(0, cap);
        String tail = t.substring(t.length() - cap);
        return "h:" + t.length()
                + ':' + Long.toHexString(fnv1a64(t))
                + ':' + Integer.toHexString(t.hashCode())
                + ':' + head + '~' + tail;
    }

    /** 64-bit FNV-1a over the UTF-16 code units of {@code s} — a wider, low-collision fingerprint. */
    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
