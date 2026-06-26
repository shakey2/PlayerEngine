package com.player2.playerengine.memory.resolution;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, on-device normalization of a raw entity mention into a stable lookup key
 * (Phase D, W4 layer 1). Pure: no Minecraft, loader, I/O, LLM, or network dependency.
 *
 * <p>Pipeline (plan §W4, lines 515-518), applied in order:
 * <ol>
 *   <li>{@code trim} + lowercase via {@link Locale#ROOT} (mirrors {@code LexicalIndex.java:26}'s
 *       lowercase-then-split convention so memory keys and lexical tokens agree);</li>
 *   <li>strip up to {@link #MAX_HONORIFIC_STRIPS} leading honorific / title / determiner tokens
 *       ({@link #LEADING_STRIP_TOKENS}: {@code the/a/an/mr/mrs/ms/sir/lord/lady/old/young/my});</li>
 *   <li>replace every non-alphanumeric char with a space (punctuation → space, keep word breaks);</li>
 *   <li>collapse runs of whitespace to a single space and {@code trim}.</li>
 * </ol>
 *
 * <p>The result is the case-insensitive key used by {@link EntityResolver}'s layer-1 exact map and
 * the {@code normalized.length()} basis for the layer-2 length gate. Never throws; a {@code null} or
 * effectively-empty mention normalizes to {@code ""}.
 */
public final class MentionNormalizer {

    private MentionNormalizer() {}

    /** Maximum number of leading honorific/title/determiner tokens stripped (plan §W4). */
    public static final int MAX_HONORIFIC_STRIPS = 3;

    /**
     * Leading tokens removed before keying. Lowercase, dot-free (punctuation is stripped to space
     * before this comparison, so {@code "Mr."} becomes {@code "mr"}). Plan §W4 line 517.
     */
    public static final Set<String> LEADING_STRIP_TOKENS = Set.of(
            "the", "a", "an", "mr", "mrs", "ms", "sir", "lord", "lady", "old", "young", "my");

    /**
     * Normalizes {@code mention} to its canonical lookup key. Null-safe and never throws.
     *
     * @param mention the raw mention text (may be null)
     * @return the normalized key (possibly {@code ""})
     */
    public static String normalize(String mention) {
        if (mention == null) {
            return "";
        }
        // 1. trim + lowercase (Locale.ROOT — mirror LexicalIndex tokenization).
        String lower = mention.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return "";
        }
        // 2. punctuation → space, collapse whitespace (done first so honorific tokens like "Mr."
        //    compare cleanly against the dot-free strip set).
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append((Character.isLetterOrDigit(c)) ? c : ' ');
        }
        String[] tokens = sb.toString().trim().split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            return "";
        }
        // 3. strip up to MAX_HONORIFIC_STRIPS leading honorific/title/determiner tokens.
        int start = 0;
        int strips = 0;
        while (start < tokens.length
                && strips < MAX_HONORIFIC_STRIPS
                && LEADING_STRIP_TOKENS.contains(tokens[start])) {
            start++;
            strips++;
        }
        // Guard: never strip away the entire mention (e.g. a node literally named "the").
        if (start >= tokens.length) {
            start = tokens.length - 1;
        }
        // 4. re-join with single spaces.
        StringBuilder out = new StringBuilder();
        for (int i = start; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(tokens[i]);
        }
        return out.toString();
    }
}
