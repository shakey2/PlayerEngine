package com.player2.playerengine.memory;

import java.util.Locale;

/**
 * The canonical node types for the memory graph (Phase D, W2).
 *
 * <p>Stored on {@link MemoryNode} as a lenient lowercase string ({@code type}); this enum is the
 * authoritative set for retrieval/scoring. An unknown stored {@code type} value is retained
 * verbatim by the codec but is not one of these constants and is excluded from typed retrieval.
 *
 * <p>{@link #REFLECTION} (W6) is the self-authored relationship-reflection node type; it is
 * declared up front (W2) so W6 never has to edit this enum.
 *
 * <p>No Minecraft, loader, or I/O dependency.
 */
public enum MemoryNodeType {
    CHARACTER,
    PLACE,
    EVENT,
    FACTION,
    ITEM,
    /** Durable atomic biographical/world fact, distinct from temporary conversation history. */
    FACT,
    /** Durable owner/player preference such as favorite color, food, game, or dislikes. */
    PREFERENCE,
    /** Self-authored reflection node (W6); the relation {@code reflected_about} points at it. */
    REFLECTION;

    /** Lowercase wire token stored in {@link MemoryNode#type()}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a stored {@code type} string leniently. Returns {@code null} for null/blank/unknown
     * values (the node keeps its raw string; it is simply not a recognized type).
     */
    public static MemoryNodeType fromWire(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        for (MemoryNodeType v : values()) {
            if (v.name().equals(t)) return v;
        }
        return null;
    }

    /** True iff {@code raw} maps to one of the recognized types. */
    public static boolean isKnown(String raw) {
        return fromWire(raw) != null;
    }
}
