package com.player2.playerengine.agentic.elliegps;

/**
 * Validates a model-generated description string for the EllieGPS description-polish path
 * (Part C5, WS4).
 *
 * <p>Mirrors the {@code CapabilityEnrichmentValidator} style: throws
 * {@link IllegalArgumentException} with a short machine token on any validation failure.
 * The caller catches the exception and falls back to the deterministic description.
 *
 * <p>Rules (Decision 7):
 * <ul>
 *   <li>Must be non-null and non-blank.</li>
 *   <li>Length must be at most {@link WaypointIngestionPrompt#MAX_DESCRIPTION_CHARS} characters.</li>
 *   <li>Must not contain ISO control characters (tabs, newlines, etc.).</li>
 * </ul>
 *
 * <p>This class has no state and no dependencies on Gson or Minecraft APIs.
 */
public final class WaypointIngestionValidator {

    private WaypointIngestionValidator() {}

    /**
     * Validates the model reply and returns the trimmed description string.
     *
     * @param reply the raw model reply text
     * @return the trimmed description string, guaranteed non-blank and within limits
     * @throws IllegalArgumentException with a short token if validation fails; the caller
     *         must fall back to the deterministic description
     */
    public static String validate(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("description:blank");
        }
        String trimmed = reply.trim();
        if (trimmed.length() > WaypointIngestionPrompt.MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException(
                    "description:len=" + trimmed.length()
                    + ":max=" + WaypointIngestionPrompt.MAX_DESCRIPTION_CHARS);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("description:control_char:code=" + (int) c);
            }
        }
        return trimmed;
    }
}
