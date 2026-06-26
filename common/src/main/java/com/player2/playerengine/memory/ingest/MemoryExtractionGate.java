package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The zero-LLM eligibility gate for a single curated turn (Phase D, W3). NOTHING here makes a
 * network call; it is a cheap pre-filter that decides whether a turn is worth batching toward an
 * extraction. It is the first cost lever after the patron gate: a stream of trivial chatter never
 * reaches the LLM.
 *
 * <p>A turn is eligible when ANY of:
 * <ol>
 *   <li><b>Length threshold</b> — the turn text is at least {@code lengthThreshold} chars
 *       (config {@code memoryExtractionLengthThreshold}, default ~40). Long turns tend to carry
 *       durable content.</li>
 *   <li><b>Known-entity dictionary hit</b> — the turn mentions an entity already in the companion's
 *       graph (the free, on-device lexical dictionary built from existing canonical names/aliases —
 *       the same corpus W5's BM25 {@code LexicalIndex} indexes). Reinforcing a known entity is
 *       always worth a look.</li>
 *   <li><b>Proper-noun heuristic</b> — the turn contains a capitalized word that is not a
 *       sentence-initial common word, a weak signal of a named entity.</li>
 * </ol>
 *
 * <p>Junk exclusion (Graph-of-Group): regardless of the above, a turn whose trimmed lower-cased
 * text is a known ephemeral command/acknowledgement ("ok", "go left", "follow me", …) is dropped.
 *
 * <p>No Minecraft, loader, network, log, or stack-trace dependency.
 */
public final class MemoryExtractionGate {

    private MemoryExtractionGate() {}

    /** Short ephemeral commands / acknowledgements that never carry durable memory (GoG junk). */
    private static final Set<String> JUNK_PHRASES = new HashSet<>();
    static {
        JUNK_PHRASES.add("ok");
        JUNK_PHRASES.add("okay");
        JUNK_PHRASES.add("k");
        JUNK_PHRASES.add("yes");
        JUNK_PHRASES.add("no");
        JUNK_PHRASES.add("yep");
        JUNK_PHRASES.add("nope");
        JUNK_PHRASES.add("sure");
        JUNK_PHRASES.add("thanks");
        JUNK_PHRASES.add("thank you");
        JUNK_PHRASES.add("stop");
        JUNK_PHRASES.add("wait");
        JUNK_PHRASES.add("go");
        JUNK_PHRASES.add("go left");
        JUNK_PHRASES.add("go right");
        JUNK_PHRASES.add("come here");
        JUNK_PHRASES.add("follow me");
        JUNK_PHRASES.add("follow");
        JUNK_PHRASES.add("stay");
        JUNK_PHRASES.add("hi");
        JUNK_PHRASES.add("hey");
        JUNK_PHRASES.add("hello");
    }

    /** Sentence-initial common words that should NOT count as proper nouns when capitalized. */
    private static final Set<String> SENTENCE_INITIAL_COMMON = new HashSet<>();
    static {
        for (String w : new String[]{
                "The", "A", "An", "I", "You", "We", "He", "She", "It", "They",
                "This", "That", "These", "Those", "My", "Your", "Our", "Their",
                "Let", "Go", "Come", "Can", "Will", "Do", "Did", "Is", "Are",
                "What", "Where", "When", "Why", "How", "Who", "Yes", "No", "Ok", "Okay",
                "Please", "Hey", "Hi", "Hello", "Thanks", "Thank", "Stop", "Wait", "Now"}) {
            SENTENCE_INITIAL_COMMON.add(w);
        }
    }

    /**
     * Decides whether a single curated turn is worth batching toward an extraction. Null/blank →
     * never eligible.
     *
     * @param turnText        the curated turn text (already curated; this method only reads it)
     * @param lengthThreshold the config length floor ({@code memoryExtractionLengthThreshold})
     * @param graph           the companion's current graph snapshot for the known-entity dictionary
     *                        (may be {@code null} or empty — then dictionary hits never fire)
     */
    public static boolean isEligible(String turnText, int lengthThreshold, MemoryGraph graph) {
        if (turnText == null) return false;
        String trimmed = turnText.trim();
        if (trimmed.isEmpty()) return false;

        // Junk exclusion first (GoG): drop ephemeral acks/commands outright.
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (JUNK_PHRASES.contains(stripTrailingPunct(lower))) {
            return false;
        }

        // (1) Length threshold.
        if (trimmed.length() >= Math.max(1, lengthThreshold)) {
            return true;
        }

        // (2) Known-entity dictionary hit (free, on-device).
        if (mentionsKnownEntity(lower, graph)) {
            return true;
        }

        // (3) Proper-noun heuristic.
        return containsProperNoun(trimmed);
    }

    // -------------------------------------------------------------------------
    // Heuristics
    // -------------------------------------------------------------------------

    private static boolean mentionsKnownEntity(String lowerText, MemoryGraph graph) {
        if (graph == null || graph.nodeCount() == 0) return false;
        for (MemoryNode node : graph.nodes()) {
            if (containsName(lowerText, node.canonicalName())) return true;
            for (String alias : node.aliases()) {
                if (containsName(lowerText, alias)) return true;
            }
        }
        return false;
    }

    private static boolean containsName(String lowerText, String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.length() < 3) return false; // avoid spurious 1-2 char matches
        return lowerText.contains(n);
    }

    private static boolean containsProperNoun(String text) {
        String[] words = text.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = stripPunct(words[i]);
            if (w.length() < 2) continue;
            char c0 = w.charAt(0);
            if (!Character.isUpperCase(c0)) continue;
            // A capitalized word counts only if it is not the sentence-initial common word.
            if (i == 0 && SENTENCE_INITIAL_COMMON.contains(w)) continue;
            // Require the rest to be lower-case-ish (avoid ALL-CAPS shouting being treated as names).
            boolean restLower = true;
            for (int k = 1; k < w.length(); k++) {
                if (Character.isUpperCase(w.charAt(k))) { restLower = false; break; }
            }
            if (restLower) return true;
        }
        return false;
    }

    private static String stripPunct(String w) {
        return w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private static String stripTrailingPunct(String s) {
        return s.replaceAll("[\\p{Punct}\\s]+$", "");
    }
}
