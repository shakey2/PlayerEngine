package com.player2.playerengine.player2api.utils;

/**
 * Thrown by {@link Utils#parseCleanedJson(String)} when an LLM reply cannot be parsed into a JSON
 * object even after lenient parsing and balanced-brace extraction.
 *
 * <p>This is a <em>typed</em> parse failure (distinct from a transport/HTTP error) so the
 * conversation layer can react to a model that produced unparseable output — reporting a concise,
 * human line to the player and reflecting the failure back to the model so it can retry truthfully
 * (DESIGN.md §3) — instead of broadcasting a raw {@code com.google.gson} stack-trace string to chat.
 *
 * <p>The offending (truncated) raw content is carried on {@link #getRawContent()} for logging; it is
 * never put in front of the player.
 */
public class LlmJsonParseException extends Exception {

    /** Prefix carried on {@link #getMessage()} so the LLM error path can recognise a parse failure. */
    public static final String SENTINEL = "LLM_JSON_PARSE_FAILURE";

    private final String rawContent;

    public LlmJsonParseException(String rawContent, Throwable cause) {
        super(SENTINEL + ": model reply was not valid JSON", cause);
        this.rawContent = rawContent;
    }

    /** The raw (possibly truncated) model content that failed to parse. For logs only — never shown to the player. */
    public String getRawContent() {
        return rawContent;
    }
}
