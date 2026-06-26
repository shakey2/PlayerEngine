package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.player2.playerengine.tasks.movement.BodyLanguageTask;

/**
 * Pure, deterministic parser for inline body-language markers embedded in an LLM message.
 *
 * <p>Markers use the square-bracket form {@code [bl:<action>]} (e.g. {@code [bl:nod_head]}). The
 * marker position is BOTH the gesture cue and the TTS chunk-split point: the text is split into
 * chunks at each marker, and the marker sits <em>between</em> chunk {@code k} and chunk
 * {@code k+1}. When the client finishes playing chunk {@code k} it fires that boundary's gesture.
 *
 * <p>This class has <strong>no Minecraft API dependency</strong> (it only references the
 * {@link BodyLanguageTask.Type} enum) so it is unit-testable in isolation. It is deterministic: the
 * model only emits marker tokens; mapping token to action and flagging unknown tokens happens here.
 *
 * <h2>Boundary semantics (Workstream 1)</h2>
 * <ul>
 *   <li>A marker at the very start ({@code "[bl:greeting] Hi"}) yields an empty leading chunk; the
 *       boundary's {@code chunkIndexAfter} is the index of that empty chunk (0) so the client can
 *       "fire immediately, no audio for this chunk".</li>
 *   <li>A marker at the very end ({@code "Bye [bl:shake_head]"}) is a boundary after the last chunk
 *       (no following chunk).</li>
 *   <li>Adjacent markers ({@code "[bl:nod_head][bl:victory]"}) fire in order at the same boundary;
 *       order is preserved (both share the same {@code chunkIndexAfter}).</li>
 *   <li>Whitespace around a removed mid-sentence marker is normalized so no double spaces remain.</li>
 * </ul>
 *
 * <p><strong>Invalid markers are kept in the boundary list with {@code valid=false}</strong> so the
 * caller (handleLlmResponse) can report them to both audiences; the chunk split still happens so the
 * timing of valid markers is unaffected. (Workstream 2 omits invalid boundaries from the wire — the
 * server-side wire list is valid-only — but the parser returns the full list for reporting.)
 */
public final class MarkerParser {

    /** Marker syntax: {@code [bl:<action>]} where action is lowercase letters/underscores. */
    private static final Pattern MARKER = Pattern.compile("\\[bl:([a-z_]+)\\]");

    private MarkerParser() {
    }

    /**
     * One body-language cue extracted from the message.
     *
     * @param chunkIndexAfter the chunk index this gesture fires AFTER (0-based into {@link ParsedMessage#chunks()})
     * @param action          the resolved gesture type, or {@code null} when {@code valid == false}
     * @param valid           {@code true} when {@code rawToken} mapped to a known {@link BodyLanguageTask.Type}
     * @param rawToken        the raw action token as the model wrote it (lowercase), for reporting unknowns
     */
    public record SegmentBoundary(int chunkIndexAfter, BodyLanguageTask.Type action, boolean valid, String rawToken) {
    }

    /**
     * Result of parsing a raw LLM message.
     *
     * @param strippedText the message with all markers removed and whitespace normalized (this is the
     *                     text shown in chat and sent to TTS)
     * @param chunks       the stripped text split at marker positions (always at least one element;
     *                     may contain empty strings for leading/adjacent/trailing markers)
     * @param boundaries   ordered list of gesture cues, including invalid ones (valid=false)
     */
    public record ParsedMessage(String strippedText, List<String> chunks, List<SegmentBoundary> boundaries) {
    }

    /**
     * Deterministically parse {@code rawMessage} into stripped text, chunks, and ordered boundaries.
     * Never returns null. A null/blank message yields a single (possibly empty) chunk and no boundaries.
     */
    public static ParsedMessage parse(String rawMessage) {
        if (rawMessage == null) {
            rawMessage = "";
        }

        List<String> chunks = new ArrayList<>();
        List<SegmentBoundary> boundaries = new ArrayList<>();

        Matcher m = MARKER.matcher(rawMessage);
        int lastEnd = 0;
        // The chunk index a marker fires AFTER is the index of the chunk that ends at this marker —
        // i.e. the number of chunks already emitted (this is the chunk being closed at this marker).
        while (m.find()) {
            String chunkText = rawMessage.substring(lastEnd, m.start());
            chunks.add(chunkText);
            int chunkIndexAfter = chunks.size() - 1;

            String rawToken = m.group(1); // already lowercase by the regex character class
            BodyLanguageTask.Type action = mapToken(rawToken);
            boundaries.add(new SegmentBoundary(chunkIndexAfter, action, action != null, rawToken));

            lastEnd = m.end();
        }
        // Trailing text after the last marker (or the whole message if no markers).
        chunks.add(rawMessage.substring(lastEnd));

        // Normalize whitespace per chunk and rebuild the stripped text. Trim each chunk so that a
        // mid-sentence marker (which sat between two spaces) does not leave a leading/trailing space
        // on the adjacent chunks, and collapse internal runs of whitespace.
        List<String> normalizedChunks = new ArrayList<>(chunks.size());
        for (String c : chunks) {
            normalizedChunks.add(normalize(c));
        }

        // Stripped text = the non-empty normalized chunks joined by a single space, so chat/TTS see
        // a clean sentence with no double spaces where a marker was removed.
        StringBuilder stripped = new StringBuilder();
        for (String c : normalizedChunks) {
            if (c.isEmpty()) {
                continue;
            }
            if (stripped.length() > 0) {
                stripped.append(' ');
            }
            stripped.append(c);
        }

        return new ParsedMessage(stripped.toString(), normalizedChunks, boundaries);
    }

    /** Map a raw marker token to its {@link BodyLanguageTask.Type}, or {@code null} if unknown. */
    private static BodyLanguageTask.Type mapToken(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        try {
            return BodyLanguageTask.Type.valueOf(rawToken.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Collapse internal whitespace runs to a single space and trim the ends. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }
}
