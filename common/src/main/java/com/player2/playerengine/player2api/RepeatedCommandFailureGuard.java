package com.player2.playerengine.player2api;

import java.util.Locale;

/** Bounded fingerprint and two-strike breaker for unchanged command-error decision loops. */
final class RepeatedCommandFailureGuard {
    static final int MAX_IDENTICAL_FAILURES = 2;
    static final int MAX_MODEL_FAILURE_REASON_CHARS = 384;
    private static final int MAX_COMMAND_FINGERPRINT_CHARS = 256;
    private static final int MAX_COMMAND_ID_CHARS = 64;
    private static final String TRUNCATED_SUFFIX = " ...[truncated]";

    enum Decision {
        REPORT_AND_REPROMPT,
        HALT_AUTOMATIC_RETRY
    }

    private String previousFingerprint = "";
    private int consecutiveFailures;

    Decision record(String commandName, String failureReason) {
        String fingerprint = boundedCommandForModel(commandName).toLowerCase(Locale.ROOT)
                + "\n" + boundedFailureReason(failureReason);
        if (fingerprint.equals(previousFingerprint)) {
            consecutiveFailures++;
        } else {
            previousFingerprint = fingerprint;
            consecutiveFailures = 1;
        }
        return consecutiveFailures >= MAX_IDENTICAL_FAILURES
                ? Decision.HALT_AUTOMATIC_RETRY
                : Decision.REPORT_AND_REPROMPT;
    }

    void reset() {
        previousFingerprint = "";
        consecutiveFailures = 0;
    }

    static String boundedFailureReason(String raw) {
        String normalized = normalizeWhitespace(raw);
        if (normalized.isEmpty()) {
            normalized = "command failed without a reason";
        }
        return bounded(normalized, MAX_MODEL_FAILURE_REASON_CHARS);
    }

    static String commandId(String rawCommand) {
        String normalized = canonicalCommand(rawCommand);
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        int separator = normalized.length();
        int space = normalized.indexOf(' ');
        int semicolon = normalized.indexOf(';');
        if (space >= 0) {
            separator = Math.min(separator, space);
        }
        if (semicolon >= 0) {
            separator = Math.min(separator, semicolon);
        }
        String id = normalized.substring(0, separator).strip();
        return id.isEmpty() ? "command" : bounded(id, MAX_COMMAND_ID_CHARS);
    }

    static String boundedCommandForModel(String rawCommand) {
        String normalized = normalizeWhitespace(rawCommand);
        return bounded(normalized.isEmpty() ? "command" : normalized, MAX_COMMAND_FINGERPRINT_CHARS);
    }

    private static String canonicalCommand(String raw) {
        return normalizeWhitespace(raw).toLowerCase(Locale.ROOT);
    }

    private static String normalizeWhitespace(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\r\\n\\t ]+", " ").strip();
    }

    private static String bounded(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int keep = Math.max(0, maxChars - TRUNCATED_SUFFIX.length());
        return value.substring(0, keep) + TRUNCATED_SUFFIX;
    }
}
