package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.manager.ConversationManager;

/**
 * Pushes bot-only feedback into the next LLM round via {@link Event.InfoMessage}.
 */
public final class AiConversationFeedback {
    private AiConversationFeedback() {
    }

    public static void enqueueInfo(PlayerEngineController mod, String message) {
        ConversationManager.getOrCreateEventQueueData(mod).onEvent(new Event.InfoMessage(message));
    }
}
