package com.player2.playerengine.agentic;

import java.util.Locale;

/** Deterministic gather/drop intent detection for C1 fallback plans. */
public final class AgenticGatherIntentDetector {

    private static final String[] KEYWORDS = {
            "pick up", "pickup", "pick-up", "collect", "grab", "loot", "gather",
            "nearby drop", "nearby item", "item drop", "dropped item", "floor item",
            "ground item", "loose item", "drops around", "drops nearby", "pick up drop"
    };

    /**
     * Resource-acquisition verbs that mean "go obtain item X" (mine/get/fetch), which the agentic
     * step kinds ({@code gather_loose_items}, {@code resolve_storage_chest}, {@code deposit_items},
     * {@code label_chest}) cannot satisfy — there is no mine/get step. Deliberately excludes the
     * gather-drop verbs ("collect"/"gather"/"grab"/"loot"), which {@link #looksLikeGatherDropGoal}
     * already handles upstream in the fallback chain.
     */
    private static final String[] MINE_GET_KEYWORDS = {
            "find", "mine", "dig", "get", "fetch", "acquire", "obtain", "bring", "retrieve"
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

    /**
     * True for a pure mine/get goal ("find diamond_ore", "mine iron", "get spruce logs") that no
     * agentic step kind can fulfil. Conservative on purpose: it is only consulted as the LAST branch
     * of {@code tryDeterministicFallback}, AFTER the deposit, gather-and-storage, storage-prep, and
     * gather-drop branches have each had their chance to match. It therefore does not need its own
     * storage/deposit exclusion — any goal containing those intents has already been claimed upstream
     * before control reaches here.
     */
    public static boolean looksLikeMineOrGetGoal(String goalText) {
        if (goalText == null || goalText.isBlank()) {
            return false;
        }
        String lower = goalText.toLowerCase(Locale.ROOT);
        for (String keyword : MINE_GET_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
