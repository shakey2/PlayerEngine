package com.player2.playerengine.help;

import java.util.List;

/**
 * The single canonicalization utility for help command paths.
 *
 * <p>Used by the leaf factory ({@link HelpfulCommand}), the renderer lookups ({@link HelpRenderer})
 * and the coverage walk ({@link HelpCoverageVerifier}) so structure, lookup, and coverage can never
 * disagree. A "path" is the space-joined English literal chain AFTER the root literal — e.g.
 * {@code "rag retrieve"}, {@code "user-settings auto-respawn on"}, {@code "storage op list player"}.
 * Argument nodes (placeholders) are never part of a path; only literal names are.</p>
 *
 * <p>All values handled here are English command tokens — they are NEVER translated.</p>
 */
public final class HelpPath {

    private HelpPath() {
    }

    /**
     * Build a canonical path from ordered literal segments, skipping null/blank segments and
     * collapsing each to single-space separation.
     */
    public static String of(String... segments) {
        if (segments == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            if (seg == null) {
                continue;
            }
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    /**
     * Build a canonical path from an ordered list of literal segments (used by the verifier walk,
     * which accumulates literal node names from the root down).
     */
    public static String of(List<String> segments) {
        if (segments == null) {
            return "";
        }
        return of(segments.toArray(new String[0]));
    }

    /**
     * Normalize an externally supplied path string (e.g. the greedy {@code <command>} argument a
     * player typed) to the canonical form: collapse any internal whitespace run to a single space
     * and trim the ends. Case is preserved (command literals are already lower/dash-cased English).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * Derive the dashed lang-key fragment from a canonical path — e.g. {@code "rag retrieve"} ->
     * {@code "rag-retrieve"}, {@code "user-settings auto-respawn on"} ->
     * {@code "user-settings-auto-respawn-on"}. Spaces become dashes; existing dashes are kept.
     */
    public static String dashed(String path) {
        return normalize(path).replace(' ', '-');
    }
}
