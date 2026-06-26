package com.player2.playerengine.memory;

/**
 * Centralized write-time hard caps and corpus ceilings for the memory graph (Phase D, W2).
 *
 * <p>Two families of limits:
 * <ul>
 *   <li><b>Write-time string caps</b> ({@link #CONTENT_MAX}, {@link #NAME_MAX},
 *       {@link #RELATION_MAX}, {@link #ALIASES_MAX}) — applied at {@code mergeNode}/{@code mergeEdge}
 *       so the store can <em>never</em> hold an unbounded string. This is the data-egress boundary:
 *       every persisted string is truncated here before it can ever flow into a model-facing
 *       surface (DESIGN.md §3).</li>
 *   <li><b>Corpus ceilings</b> ({@link #MAX_NODES}, {@link #MAX_EDGES}, {@link #MAX_EDGES_PER_PAIR})
 *       and stale windows — enforced by {@link MemoryCompactor} inside {@code flushIfDirty()}.</li>
 * </ul>
 *
 * <p>The numeric values are conservative, playtest-tunable placeholders (plan §W2). They are
 * named constants precisely so a single edit re-tunes every call site.
 *
 * <p>No Minecraft, loader, or I/O dependency.
 */
public final class MemoryCaps {

    private MemoryCaps() {}

    // -------------------------------------------------------------------------
    // Write-time string caps (the egress boundary — applied at merge time)
    // -------------------------------------------------------------------------

    /** Max {@code content} length in chars. Mirrors {@link MemoryNodeCodec#CONTENT_MAX}. */
    public static final int CONTENT_MAX = 280;

    /** Max {@code canonicalName} / per-alias length in chars. */
    public static final int NAME_MAX = 64;

    /** Max {@code relation} length in chars. */
    public static final int RELATION_MAX = 48;

    /** Max number of aliases retained per node. */
    public static final int ALIASES_MAX = 16;

    /** Max number of tags retained per node. */
    public static final int TAGS_MAX = 16;

    // -------------------------------------------------------------------------
    // Corpus ceilings (enforced by MemoryCompactor on flush)
    // -------------------------------------------------------------------------

    /** Hard ceiling on total nodes in one companion's graph. */
    public static final int MAX_NODES = 500;

    /** Hard ceiling on total edges in one companion's graph. */
    public static final int MAX_EDGES = 2000;

    /** Max parallel edges retained between a single ordered {@code (fromId, toId)} pair. */
    public static final int MAX_EDGES_PER_PAIR = 8;

    // -------------------------------------------------------------------------
    // Staleness windows + recency decay (game ticks; 24000 ticks = 1 game day)
    // -------------------------------------------------------------------------

    /** Ticks per game hour (20 ticks/s × 60 s × 60 — but MC day is 24000 ticks/24h = 1000/h). */
    public static final long TICKS_PER_GAME_HOUR = 1000L;

    /**
     * An edge/node is "stale" once this many ticks have elapsed since its {@code lastSeenTick}.
     * Default ~7 game days (7 × 24000).
     */
    public static final long STALE_TICKS = 7L * 24000L;

    /** Per-game-hour recency decay base; effective weight = stored × {@code DECAY_BASE^hoursSince}. */
    public static final double DECAY_BASE = 0.995;

    // -------------------------------------------------------------------------
    // Truncation helpers (null-safe; never throw)
    // -------------------------------------------------------------------------

    /** Truncates {@code s} to {@code max} chars; null → "". */
    public static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Caps a content string to {@link #CONTENT_MAX}. */
    public static String capContent(String s) {
        return cap(s, CONTENT_MAX);
    }

    /** Caps a name/alias string to {@link #NAME_MAX}. */
    public static String capName(String s) {
        return cap(s, NAME_MAX);
    }

    /** Caps a relation string to {@link #RELATION_MAX}. */
    public static String capRelation(String s) {
        return cap(s, RELATION_MAX);
    }
}
