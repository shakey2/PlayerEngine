package com.player2.playerengine.player2api;

import org.jetbrains.annotations.Nullable;

public sealed interface Event // tagged union basically of the below events
        permits Event.UserMessage, Event.CharacterMessage, Event.InfoMessage {
    String message();

    public String getConversationHistoryString();

    public record UserMessage(String message, String userName) implements Event {
        public String getConversationHistoryString() {
            return String.format("User Message: [%s]: %s", userName, message);
        }

        public String toString() {
            return String.format("UserMessage(userName='%s', message='%s')", userName, message);
        }
    }

    public record InfoMessage(String message) implements Event {
        public String getConversationHistoryString() {
            return String.format("Info: %s", message);
        }

        public String toString() {
            return getConversationHistoryString();
        }
    }

    public record CharacterMessage(String message, String command, AgentConversationData sendingCharacterData,
            @Nullable String originatingUserName)
            implements Event {
        public CharacterMessage(String message, String command, AgentConversationData sendingCharacterData) {
            this(message, command, sendingCharacterData, null);
        }

        public String getConversationHistoryString() {
            return String.format("Other AI Message: [%s]: %s", sendingCharacterData.getName(), message);
        }

        public String toString() {
            return String.format("CharacterMessage(name='%s', message='%s', command='%s', originatingUser=%s)",
                    sendingCharacterData.getName(), message, command, originatingUserName);
        }

    }
}
