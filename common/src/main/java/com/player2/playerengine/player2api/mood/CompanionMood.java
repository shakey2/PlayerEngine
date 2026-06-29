package com.player2.playerengine.player2api.mood;

import com.google.gson.JsonObject;

/**
 * Immutable per-companion mood record (Lightweight Companion Mood System — Workstream 1).
 *
 * <p>A single current record per companion: a fixed {@link MoodLabel}, one short bounded {@code cause}
 * phrase, a 1–5 {@code intensity}, and an update timestamp. This is the minimum needed to (a) tell the
 * model how it feels and why and (b) detect a meaningful mood transition. No vectors, no per-emotion
 * scalars, no history list.
 *
 * <p>Persisted to {@code mood.json} beside {@code conversation.jsonl}. {@link #fromJson(JsonObject)} is
 * tolerant: unknown/missing label → {@link MoodLabel#NEUTRAL}, out-of-range intensity clamped to
 * {@code [1,5]}, missing cause → {@code ""}. Missing/corrupt files degrade to {@link #neutral()} at the
 * call site (never crash — DESIGN.md).
 *
 * <p>Egress: {@code cause} is model-generated free text; callers MUST cap it via
 * {@link com.player2.playerengine.memory.MemoryCaps#cap(String, int)} with {@link #CAUSE_MAX} before it
 * enters any model-facing surface. {@link #toPromptString()} relies on the stored value already being
 * bounded (the construction path caps it) and applies a defensive cap as a backstop.
 *
 * <p>No Minecraft, loader, or I/O dependency beyond Gson — common module only.
 */
public record CompanionMood(
        MoodLabel label,       // never null
        String cause,          // bounded short phrase; may be "" (no cause)
        int intensity,         // clamped [1,5]
        long updatedAtEpochMs
) {

    /** Default intensity for a neutral / unspecified mood (mid-scale). */
    public static final int DEFAULT_INTENSITY = 3;

    /** Lowest valid intensity. */
    public static final int MIN_INTENSITY = 1;

    /** Highest valid intensity. */
    public static final int MAX_INTENSITY = 5;

    /**
     * Prompt-tail cap (chars) for {@code cause}. This bounds the model-declared cause before it enters
     * the per-turn tail. It is deliberately below {@link com.player2.playerengine.memory.MemoryCaps#CONTENT_MAX}
     * (280) so the tail cause and any stored EVENT cause coincide (no surprising divergence).
     */
    public static final int CAUSE_MAX = 120;

    /** JSON keys for {@code mood.json} (schema v1; absent {@code schema} key == v1). */
    public static final String KEY_LABEL = "label";
    public static final String KEY_CAUSE = "cause";
    public static final String KEY_INTENSITY = "intensity";
    public static final String KEY_UPDATED_AT = "updatedAtEpochMs";

    /** Canonical constructor: normalizes null label → NEUTRAL, null cause → "", clamps intensity. */
    public CompanionMood {
        if (label == null) {
            label = MoodLabel.NEUTRAL;
        }
        if (cause == null) {
            cause = "";
        }
        intensity = clampIntensity(intensity);
    }

    /** A fresh neutral mood: {@link MoodLabel#NEUTRAL}, empty cause, mid intensity, timestamped now. */
    public static CompanionMood neutral() {
        return new CompanionMood(MoodLabel.NEUTRAL, "", DEFAULT_INTENSITY, System.currentTimeMillis());
    }

    /** Clamps an arbitrary intensity into {@code [1,5]}. */
    public static int clampIntensity(int value) {
        if (value < MIN_INTENSITY) {
            return MIN_INTENSITY;
        }
        if (value > MAX_INTENSITY) {
            return MAX_INTENSITY;
        }
        return value;
    }

    /**
     * Short, bounded human/model-facing phrase, e.g. {@code "happy (intensity 4): the player gave me iron"}
     * or {@code "neutral (intensity 3)"} when there is no cause. The label is an enum token (inherently
     * bounded); the cause is defensively re-capped to {@link #CAUSE_MAX} so this is always safe to inject.
     */
    public String toPromptString() {
        String name = label.name().toLowerCase(java.util.Locale.ROOT);
        String boundedCause = com.player2.playerengine.memory.MemoryCaps.cap(cause, CAUSE_MAX);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (intensity ").append(intensity).append(")");
        if (boundedCause != null && !boundedCause.isBlank()) {
            sb.append(": ").append(boundedCause);
        }
        return sb.toString();
    }

    /** Serializes to the {@code mood.json} shape (schema v1). */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty(KEY_LABEL, label.name());
        obj.addProperty(KEY_CAUSE, cause);
        obj.addProperty(KEY_INTENSITY, intensity);
        obj.addProperty(KEY_UPDATED_AT, updatedAtEpochMs);
        return obj;
    }

    /**
     * Tolerant deserialization (forward-compatible read): unknown/missing {@code label} → NEUTRAL,
     * out-of-range {@code intensity} clamped, missing {@code cause} → {@code ""}, missing timestamp →
     * now. Never throws on a malformed object; unknown extra keys are ignored. A {@code null} object
     * yields {@link #neutral()}.
     */
    public static CompanionMood fromJson(JsonObject obj) {
        if (obj == null) {
            return neutral();
        }

        MoodLabel label = MoodLabel.NEUTRAL;
        try {
            if (obj.has(KEY_LABEL) && obj.get(KEY_LABEL).isJsonPrimitive()) {
                label = MoodLabel.resolve(obj.get(KEY_LABEL).getAsString()).orElse(MoodLabel.NEUTRAL);
            }
        } catch (Exception ignored) {
            label = MoodLabel.NEUTRAL;
        }

        String cause = "";
        try {
            if (obj.has(KEY_CAUSE) && obj.get(KEY_CAUSE).isJsonPrimitive()) {
                cause = obj.get(KEY_CAUSE).getAsString();
            }
        } catch (Exception ignored) {
            cause = "";
        }

        int intensity = DEFAULT_INTENSITY;
        try {
            if (obj.has(KEY_INTENSITY) && obj.get(KEY_INTENSITY).isJsonPrimitive()) {
                intensity = obj.get(KEY_INTENSITY).getAsInt();
            }
        } catch (Exception ignored) {
            intensity = DEFAULT_INTENSITY;
        }

        long updatedAt = System.currentTimeMillis();
        try {
            if (obj.has(KEY_UPDATED_AT) && obj.get(KEY_UPDATED_AT).isJsonPrimitive()) {
                updatedAt = obj.get(KEY_UPDATED_AT).getAsLong();
            }
        } catch (Exception ignored) {
            updatedAt = System.currentTimeMillis();
        }

        // Canonical constructor re-normalizes (null label/cause, clamp intensity).
        return new CompanionMood(label, cause, intensity, updatedAt);
    }
}
