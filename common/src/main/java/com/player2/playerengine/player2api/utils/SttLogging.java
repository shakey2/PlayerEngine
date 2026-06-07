package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared log formatting for STT / voice diagnostics (client and server).
 */
public final class SttLogging {
    private static final int PREVIEW_MAX_CHARS = 120;

    private SttLogging() {
    }

    public static String messagePreview(String message) {
        if (message == null) {
            return "<null>";
        }
        String trimmed = message.strip();
        if (trimmed.isEmpty()) {
            return "<blank>";
        }
        if (trimmed.length() <= PREVIEW_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_CHARS) + "...";
    }

    public static String jsonResponseKeys(Map<String, JsonElement> response) {
        if (response == null || response.isEmpty()) {
            return "<empty>";
        }
        return response.keySet().stream().sorted().collect(Collectors.joining(", "));
    }
}
