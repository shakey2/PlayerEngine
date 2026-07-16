package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Deterministic checks for bounded conversation-history retention. */
public final class ConversationHistoryRetentionSelfTest {
    private ConversationHistoryRetentionSelfTest() {}

    public static void runAll() {
        require(ConversationHistory.compactionTailStart(17) == 9,
                "17-message history must keep the eight most recent entries");

        List<JsonObject> history = new ArrayList<>();
        history.add(message("system", 0));
        for (int i = 1; i <= 40; i++) {
            history.add(message(i % 2 == 0 ? "assistant" : "user", i));
        }
        ConversationHistory.trimOldestToLimit(history);
        require(history.size() == ConversationHistory.MAX_HISTORY, "history must be capped at 16 messages");
        require("system".equals(history.get(0).get("role").getAsString()), "system prompt must survive trimming");
        require(history.get(1).get("seq").getAsInt() == 26, "oldest excess turns must be removed");
        require(history.get(history.size() - 1).get("seq").getAsInt() == 40, "newest turn must survive trimming");

        ConversationHistory live = new ConversationHistory("system");
        for (int i = 0; i < 20; i++) {
            live.addUserMessage("failure-feedback-" + i, null);
        }
        require(live.getListJSON().size() == ConversationHistory.MAX_HISTORY,
                "user/info additions must enforce the limit before the next API request");
        require(live.getListJSON().get(live.getListJSON().size() - 1)
                        .get("content").getAsString().endsWith("19"),
                "pre-request trimming must retain the newest feedback turn");

        ConversationHistory restored = new ConversationHistory("temporary");
        restored.getListJSON().clear();
        for (int i = 0; i < ConversationHistory.MAX_HISTORY; i++) {
            restored.getListJSON().add(message("user", i));
        }
        restored.setBaseSystemPrompt("restored-system");
        require(restored.getListJSON().size() == ConversationHistory.MAX_HISTORY,
                "restoring a missing system prompt must reserve its slot inside the history cap");
        require("system".equals(restored.getListJSON().get(0).get("role").getAsString()),
                "restored base prompt must be first after bounded recovery");
        require(restored.getListJSON().get(1).get("seq").getAsInt() == 1,
                "restored base prompt must evict only the oldest non-system entry");
    }

    private static JsonObject message(String role, int seq) {
        JsonObject out = new JsonObject();
        out.addProperty("role", role);
        out.addProperty("content", "message-" + seq);
        out.addProperty("seq", seq);
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
