package com.player2.playerengine.agentic.elliegps;

/**
 * Prompt builders for the optional async SUMMARIZATION description-polish call (Part C5, WS4).
 *
 * <p>Following the project's prompt-class convention: one static {@link #systemPrompt()} and one
 * static {@link #userPrompt(String, java.util.List)} — no state, no Gson dependency here.
 *
 * <p>The LLM is asked for exactly one plain-text sentence describing the storage container,
 * routed through {@link com.player2.playerengine.player2api.AiTaskClass#SUMMARIZATION} via
 * {@link com.player2.playerengine.player2api.Player2APIService#completeConversationToString}.
 * No JSON envelope is required; {@code completeConversationToString} already returns the raw
 * text content. Sign-label text is NOT an input (Decision 7: the C3 label_chest step runs after
 * deposit, so the auto-hook can never have it at this point).
 *
 * <p>Budget failures, null replies, and validation rejections all fall back to the deterministic
 * description; this class never controls fallback logic — that is {@link WaypointIngestionService}.
 */
public final class WaypointIngestionPrompt {

    /** Max character length the model is told to stay within. Validated by {@link WaypointIngestionValidator}. */
    static final int MAX_DESCRIPTION_CHARS = 200;

    private WaypointIngestionPrompt() {}

    /**
     * System prompt instructing the model to produce a single concise plain-text sentence.
     */
    public static String systemPrompt() {
        return "You write concise one-sentence descriptions for Minecraft storage chests. "
                + "Reply with a single plain-text sentence, no markdown, no quotes, "
                + "at most " + MAX_DESCRIPTION_CHARS + " characters. "
                + "Describe what is stored and approximately where, using the information provided.";
    }

    /**
     * User prompt providing the deterministic snapshot summary and keyword hints to the model.
     *
     * @param snapshotSummary deterministic description already built (e.g.
     *                        {@code "double chest at (120,64,-35): 320 iron ingot, 10 empty slots"})
     * @param keywords        deterministic keywords already assigned to the waypoint
     * @return the user-turn prompt text
     */
    public static String userPrompt(String snapshotSummary, java.util.List<String> keywords) {
        String kwLine = (keywords == null || keywords.isEmpty())
                ? "(none)"
                : String.join(", ", keywords.subList(0, Math.min(keywords.size(), 20)));
        return "Container summary: " + snapshotSummary + "\n"
                + "Keywords: " + kwLine + "\n"
                + "Write a single concise sentence describing what is stored here and where it is. "
                + "Keep it under " + MAX_DESCRIPTION_CHARS + " characters.";
    }
}
