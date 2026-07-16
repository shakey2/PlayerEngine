package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.manager.ConversationManager;

/** Pushes bounded bot-only feedback into conversation processing. */
public final class AiConversationFeedback {
    private AiConversationFeedback() {
    }

    public static void enqueueInfo(PlayerEngineController mod, String message) {
        ConversationManager.getOrCreateEventQueueData(mod).onEvent(new Event.InfoMessage(message));
    }

    /**
     * Remembers informational context without starting an LLM round. The note is delivered only when
     * some later, independently dispatchable event already requires a response.
     */
    public static void deferInfo(PlayerEngineController mod, String message) {
        ConversationManager.getOrCreateEventQueueData(mod).deferInfo(new Event.InfoMessage(message));
    }
}
