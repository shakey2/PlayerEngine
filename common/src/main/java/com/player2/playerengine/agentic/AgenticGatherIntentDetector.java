package com.player2.playerengine.agentic;

import java.util.Locale;

/** Deterministic gather/drop intent detection for C1 fallback plans. */
public final class AgenticGatherIntentDetector {

    private static final String[] KEYWORDS = {
            "pick up", "pickup", "pick-up", "collect", "grab", "loot", "gather",
            "nearby drop", "nearby item", "item drop", "dropped item", "floor item",
            "ground item", "loose item", "drops around", "drops nearby", "pick up drop"
    };

    private AgenticGatherIntentDetector() {}

    public static boolean looksLikeGatherDropGoal(String goalText) {
        if (goalText == null || goalText.isBlank()) {
            return false;
        }
        String lower = goalText.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return lower.contains("drop") && (lower.contains("near") || lower.contains("around") || lower.contains("here"));
    }
}
