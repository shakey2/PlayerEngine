package com.player2.playerengine.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared classifier for durable fact/preference memory nodes.
 *
 * <p>The graph still stores characters, places, events, factions, items, and reflections. This helper
 * defines the stricter contract for nodes that may be treated as durable player-facing facts.
 */
public final class MemoryDurableFactClassifier {
    private static final Pattern PREFERENCE_SIGNAL = Pattern.compile(
            "\\b(favou?rite|prefers?|preference|likes?|loves?|enjoys?|dislikes?|hates?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STABLE_FACT_SIGNAL = Pattern.compile(
            "\\b(is|are|has|have|lives|works|plays|owns|uses|speaks|knows|wants|needs|birthday|name|age|home)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_WRAPPER = Pattern.compile(
            "^(?:a|an|the)\\s+[^.]{0,100}\\b(?:who|that)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODIC_MARKER = Pattern.compile(
            "\\b(conversation episode|today|yesterday|last session|this session|earlier|just now|during|after|before|when|while|to test|testing|tested|inventory drop|on death|automatically revived|respawned|was slain|were slain|was killed|were killed|got killed|got slain|died|was revived|were revived)\\b",
            Pattern.CASE_INSENSITIVE);

    private MemoryDurableFactClassifier() {}

    public static boolean isDurableFactType(MemoryNodeType type) {
        return type == MemoryNodeType.FACT || type == MemoryNodeType.PREFERENCE;
    }

    public static boolean isDisplayableFactNode(MemoryNode node) {
        if (node == null) {
            return false;
        }
        MemoryNodeType type = MemoryNodeType.fromWire(node.type());
        return isDurableFactNode(type, node.canonicalName(), node.content(), node.tags());
    }

    /**
     * Normalizes model output for durable fact/preference nodes. Invalid durable fact nodes return
     * {@code null}; non fact/preference graph node types are returned unchanged.
     */
    public static MemoryNodeType normalizeExtractedType(MemoryNodeType type,
                                                        String name,
                                                        String content,
                                                        List<String> tags) {
        if (type == null) {
            return null;
        }
        if (!isDurableFactType(type)) {
            return type;
        }
        if (looksEpisodic(name, content, tags)) {
            return null;
        }
        if (hasPreferenceSignal(name, content, tags)) {
            return MemoryNodeType.PREFERENCE;
        }
        return looksLikeStableFact(name, content, tags) ? MemoryNodeType.FACT : null;
    }

    public static boolean isDurableFactNode(MemoryNodeType type,
                                            String name,
                                            String content,
                                            List<String> tags) {
        if (!isDurableFactType(type) || looksEpisodic(name, content, tags)) {
            return false;
        }
        if (type == MemoryNodeType.PREFERENCE) {
            return hasPreferenceSignal(name, content, tags);
        }
        return !hasPreferenceSignal(name, content, tags) && looksLikeStableFact(name, content, tags);
    }

    private static boolean hasPreferenceSignal(String name, String content, List<String> tags) {
        return PREFERENCE_SIGNAL.matcher(join(name, content, tags)).find();
    }

    private static boolean looksLikeStableFact(String name, String content, List<String> tags) {
        String text = join(name, content, tags);
        return !text.isBlank()
                && !PROFILE_WRAPPER.matcher(text).find()
                && STABLE_FACT_SIGNAL.matcher(text).find();
    }

    private static boolean looksEpisodic(String name, String content, List<String> tags) {
        String text = join(name, content, tags);
        return text.isBlank()
                || PROFILE_WRAPPER.matcher(text).find()
                || EPISODIC_MARKER.matcher(text).find();
    }

    private static String join(String name, String content, List<String> tags) {
        StringBuilder sb = new StringBuilder();
        append(sb, name);
        append(sb, content);
        if (tags != null) {
            for (String tag : tags) {
                append(sb, tag);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value.trim());
    }
}
