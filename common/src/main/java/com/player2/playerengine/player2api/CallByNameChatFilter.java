package com.player2.playerengine.player2api;

import org.jetbrains.annotations.Nullable;

/**
 * When call-by-name is enabled, only forwards chat to an automaton if the text opens with that
 * character's {@link Character#name()} or {@link Character#shortName()} (if distinct); prefix match is
 * case-insensitive. The hail is stripped.
 */
public final class CallByNameChatFilter {

    private CallByNameChatFilter() {
    }

    /**
     * @return {@code null} if the message should not be delivered to this automaton
     */
    @Nullable
    public static Event.UserMessage filterForAutomaton(Event.UserMessage msg, Character character, boolean callByNameEnabled) {
        if (!callByNameEnabled) {
            return msg;
        }
        if (character == null) {
            return msg;
        }
        String trimmed = msg.message().trim();
        String stripped = stripMatchingPrefix(trimmed, character);
        if (stripped == null) {
            return null;
        }
        return new Event.UserMessage(stripped, msg.userName());
    }

    @Nullable
    private static String stripMatchingPrefix(String trimmed, Character character) {
        String fromName = tryStripPrefix(trimmed, character.name());
        if (fromName != null) {
            return fromName;
        }
        String shortName = character.shortName();
        if (shortName != null && !shortName.isBlank() && !shortName.equalsIgnoreCase(character.name())) {
            return tryStripPrefix(trimmed, shortName);
        }
        return null;
    }

    @Nullable
    private static String tryStripPrefix(String trimmed, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        if (trimmed.length() < prefix.length() || !trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String rest = trimmed.substring(prefix.length()).trim();
        while (!rest.isEmpty() && isHailSeparator(rest.charAt(0))) {
            rest = rest.substring(1).trim();
        }
        if (rest.isEmpty()) {
            return null;
        }
        return rest;
    }

    private static boolean isHailSeparator(char c) {
        return c == ':' || c == ',' || c == ';' || java.lang.Character.isWhitespace(c);
    }
}
