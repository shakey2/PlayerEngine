package com.player2.playerengine.agentic;

import java.util.Locale;

/** Deterministic storage-preparation intent for C2 fallback plans. */
public final class AgenticStorageIntentDetector {

    private static final String[] STORAGE_KEYWORDS = {
            "find a chest", "find chest", "place a chest", "place chest", "prepare storage",
            "storage chest", "put a chest", "need a chest", "chest nearby", "make a chest",
            "get a chest", "set up storage", "prepare a chest"
    };

    private static final String[] DEPOSIT_ONLY = {
            "deposit", "store items in", "put items in chest", "stash items", "put everything in"
    };

    private AgenticStorageIntentDetector() {}

    public static boolean looksLikeStoragePrepGoal(String goalText) {
        if (goalText == null || goalText.isBlank()) {
            return false;
        }
        String lower = goalText.toLowerCase(Locale.ROOT);
        for (String keyword : STORAGE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return lower.contains("chest") && (lower.contains("find") || lower.contains("place") || lower.contains("prepare"));
    }

    public static boolean looksLikeDepositOnlyGoal(String goalText) {
        if (goalText == null || goalText.isBlank()) {
            return false;
        }
        String lower = goalText.toLowerCase(Locale.ROOT);
        for (String keyword : DEPOSIT_ONLY) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return lower.contains("deposit") || (lower.contains("store") && !lower.contains("prepare"));
    }

    public static boolean looksLikeGatherAndStorageGoal(String goalText) {
        return AgenticGatherIntentDetector.looksLikeGatherDropGoal(goalText)
                && looksLikeStoragePrepGoal(goalText);
    }

    /** True when the goal also asks to label/sign the chest, so an optional label step is appropriate. */
    public static boolean looksLikeLabelGoal(String goalText) {
        if (goalText == null || goalText.isBlank()) {
            return false;
        }
        String lower = goalText.toLowerCase(Locale.ROOT);
        return lower.contains("label") || lower.contains("sign")
                || lower.contains("tag the chest") || lower.contains("mark the chest");
    }
}
