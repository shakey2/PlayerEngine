package com.player2.playerengine.player2api.mood;

import java.util.Optional;

/**
 * Fixed, lightweight companion-mood vocabulary (Lightweight Companion Mood System — Workstream 1).
 *
 * <p>The mood {@code label} the model declares in its per-turn reply is validated against this enum
 * <b>code-side</b> (deterministic-over-model, DESIGN.md): an unrecognized label never changes the
 * companion's mood and is reported to the model only. The enum is intentionally small — 10 labels are
 * the whole emotion space; this is a roleplay + memory hint, not an emotion engine.
 *
 * <p>No Minecraft, loader, or I/O dependency — common module only.
 */
public enum MoodLabel {
    NEUTRAL,
    HAPPY,
    CONTENT,
    EXCITED,
    CURIOUS,
    SAD,
    ANXIOUS,
    ANGRY,
    AFRAID,
    DETERMINED;

    /**
     * Resolves a free-text label (model-declared) to a {@link MoodLabel}, case-insensitively and
     * trimmed. Unknown / blank / null → {@link Optional#empty()} (the caller leaves mood unchanged and
     * reports the invalid label to the model). Pure, deterministic, never throws.
     */
    public static Optional<MoodLabel> resolve(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String token = raw.trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        for (MoodLabel label : values()) {
            if (label.name().equalsIgnoreCase(token)) {
                return Optional.of(label);
            }
        }
        return Optional.empty();
    }
}
