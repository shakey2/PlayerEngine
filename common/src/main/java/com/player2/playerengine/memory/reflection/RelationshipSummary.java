package com.player2.playerengine.memory.reflection;

import com.player2.playerengine.memory.MemoryCaps;

/**
 * Value/helper for the per-companion relationship summary — the single bounded sentence (or two)
 * that lives in the <b>static</b> system block (the day-one global-recall answer; Phase D, W6).
 *
 * <p>This type is a thin, pure wrapper that guarantees the summary is always:
 * <ul>
 *   <li><b>hard-capped at write time</b> to {@link #CHAR_CAP} characters — the egress boundary, so a
 *       summary can never carry an unbounded model-facing string into the system prompt (DESIGN.md §3);</li>
 *   <li><b>normalized</b> (trimmed, internal newlines collapsed to single spaces) so the
 *       prefix-cache-injected suffix is byte-stable for a given {@code summaryVersion} regardless of
 *       how the model formatted its reflection reply;</li>
 *   <li><b>never null</b> — an absent summary is the empty string, which the injection helper omits
 *       entirely (byte-identical to today).</li>
 * </ul>
 *
 * <p>No Minecraft, loader, I/O, or LLM dependency. It does not read logs, capture streams, or
 * concatenate a {@code Throwable}; it only normalizes and caps a curated string.
 */
public final class RelationshipSummary {

    /**
     * Hard ceiling on the relationship summary length in characters (plan {@code relationshipSummaryCharCap}).
     * This is the read-side egress bound re-applied by {@link #suffixFor} at the system-block combine
     * point, and the hard maximum the operator-configured cap is clamped to in
     * {@code MemoryStore.effectiveSummaryCharCap()}. The operator config ({@code relationshipSummaryCharCap})
     * may only TIGHTEN below this at write time; the stored summary is therefore always already within
     * this bound, so {@code suffixFor} never truncates a stored value (prefix-cache byte-stability).
     * Playtest-tunable placeholder.
     */
    public static final int CHAR_CAP = 280;

    /** The bracketed label the injection suffix uses in the system block. */
    public static final String LABEL = "[Relationship]";

    private final String text;

    private RelationshipSummary(String text) {
        this.text = text;
    }

    /**
     * Builds a normalized, capped summary from a raw model reply (or any curated string). Null/blank
     * collapses to {@link #empty()}.
     */
    public static RelationshipSummary of(String raw) {
        return new RelationshipSummary(normalizeAndCap(raw));
    }

    /** The empty (absent) summary — its suffix is omitted entirely by the injection helper. */
    public static RelationshipSummary empty() {
        return new RelationshipSummary("");
    }

    /** The normalized, capped summary text; never null, possibly empty. */
    public String text() {
        return text;
    }

    /** True iff there is no summary to inject (suffix omitted → byte-identical to baseline). */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /**
     * The exact system-block suffix for this summary, or the empty string when absent. The combine
     * helper in {@code AIPersistantData} appends this verbatim AFTER the base prompt so every
     * {@code setBaseSystemPrompt} rebuild path emits an identical suffix for a given summary value.
     *
     * <p>Format: {@code "\n\n[Relationship] <summary>"} — a leading blank line separates it from the
     * base prompt. Empty summary → {@code ""}.
     */
    public String systemBlockSuffix() {
        return text.isEmpty() ? "" : "\n\n" + LABEL + " " + text;
    }

    /**
     * Static convenience: the suffix for a raw stored summary string without constructing an instance.
     * Used by the {@code AIPersistantData} combine helper so the same normalization/cap applies at
     * every rebuild site (prefix-cache byte-stability invariant).
     */
    public static String suffixFor(String rawStoredSummary) {
        return of(rawStoredSummary).systemBlockSuffix();
    }

    /**
     * Trims, collapses internal whitespace/newlines to single spaces, and caps to {@link #CHAR_CAP}.
     * Null → "". Whitespace collapse is what makes the suffix byte-stable across reflections that
     * produce semantically-equal but differently-formatted text.
     */
    private static String normalizeAndCap(String raw) {
        if (raw == null) return "";
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        return MemoryCaps.cap(collapsed, CHAR_CAP);
    }
}
