package com.player2.playerengine.player2api.mood;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.memory.MemoryCaps;

import java.util.Optional;

/**
 * Pure static helper: validates and applies an optional model-declared mood object to the
 * companion's current mood (Lightweight Companion Mood System — Workstream 2).
 *
 * <p>Consumed in {@code AgentConversationData.handleLlmResponse} immediately after the
 * {@code message}/{@code command} reads. The caller is responsible for reading the {@code "mood"}
 * field from the LLM response and passing the resulting {@link JsonObject} (or {@code null} when
 * absent) to {@link #apply(JsonObject, CompanionMood)}.
 *
 * <p>All logic is deterministic — no model call, no I/O, no Minecraft API. Common module only.
 *
 * <h3>Egress safety</h3>
 * The raw {@code "cause"} string from the model is capped to {@link #MOOD_CAUSE_MAX} chars via
 * {@link MemoryCaps#cap(String, int)} before it is stored in the returned {@link CompanionMood}.
 * Callers that subsequently inject the mood into the per-turn prompt tail or into the memory graph
 * therefore always receive an already-bounded string (with {@link CompanionMood#toPromptString()}
 * applying its own defensive re-cap as a backstop — DESIGN.md §3).
 */
public final class MoodUpdate {

    private MoodUpdate() {}

    /**
     * Prompt-tail cap (chars) for the model-declared {@code cause}. This is the same value as
     * {@link CompanionMood#CAUSE_MAX} — redeclared here for single-source clarity and so
     * {@link #apply} does not depend on the constant's physical location changing.
     *
     * <p>Callers <em>should</em> prefer {@link CompanionMood#CAUSE_MAX} when building the record
     * outside this class; both are {@code 120}.
     */
    public static final int MOOD_CAUSE_MAX = CompanionMood.CAUSE_MAX; // 120

    // -------------------------------------------------------------------------
    // JSON field names as read from the LLM reply
    // -------------------------------------------------------------------------

    private static final String FIELD_LABEL      = "label";
    private static final String FIELD_CAUSE      = "cause";
    private static final String FIELD_INTENSITY  = "intensity";
    private static final String FIELD_MEMORABLE  = "memorable";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Applies an optional model-declared mood object to the companion's current mood.
     *
     * <p>Returns:
     * <ul>
     *   <li>The <em>unchanged</em> current mood when {@code moodObj} is {@code null} or contains no
     *       recognised {@code label} (with {@link MoodUpdateResult#invalidLabel()} {@code true} when
     *       a {@code label} key was present but not in the vocabulary).</li>
     *   <li>A fresh {@link CompanionMood} (timestamped {@link System#currentTimeMillis()}) when the
     *       label is valid; {@code cause} is capped, {@code intensity} is clamped or defaulted.</li>
     * </ul>
     *
     * <p>Never throws; on any unexpected error the current mood is returned unchanged.
     *
     * @param moodObj   the parsed {@code "mood"} field from the LLM reply, or {@code null} if absent
     * @param current   the companion's current persisted mood (never null — caller ensures this)
     * @return          a {@link MoodUpdateResult} carrying the (possibly unchanged) mood, the
     *                  invalid-label flag, and the memorable flag
     */
    public static MoodUpdateResult apply(JsonObject moodObj, CompanionMood current) {
        // Null or empty object → no change, no error
        if (moodObj == null || moodObj.size() == 0) {
            return MoodUpdateResult.unchanged(current);
        }

        // --- Label resolution ---
        // Treat a missing "label" key as: the model sent a partial mood object — leave unchanged,
        // no invalidLabel signal (the key was simply omitted; not an error the model must be told
        // about, because absent = no change per design decision 1).
        if (!moodObj.has(FIELD_LABEL)) {
            return MoodUpdateResult.unchanged(current);
        }

        String rawLabel = null;
        try {
            JsonElement labelEl = moodObj.get(FIELD_LABEL);
            if (labelEl != null && labelEl.isJsonPrimitive()) {
                rawLabel = labelEl.getAsString();
            }
        } catch (Exception ignored) {
            // malformed element — treat as unrecognised
        }

        Optional<MoodLabel> resolved = MoodLabel.resolve(rawLabel);
        if (!resolved.isPresent()) {
            // A "label" key was present but unrecognised → signal invalidLabel; mood unchanged.
            return MoodUpdateResult.invalidLabel(current);
        }

        MoodLabel newLabel = resolved.get();

        // --- Cause (cap to MOOD_CAUSE_MAX; null/missing → "") ---
        String cause = "";
        try {
            if (moodObj.has(FIELD_CAUSE)) {
                JsonElement causeEl = moodObj.get(FIELD_CAUSE);
                if (causeEl != null && causeEl.isJsonPrimitive()) {
                    cause = causeEl.getAsString();
                }
            }
        } catch (Exception ignored) {
            cause = "";
        }
        cause = MemoryCaps.cap(cause, MOOD_CAUSE_MAX); // data-egress cap (DESIGN.md §3)

        // --- Intensity (clamp [1,5]; default 3 if absent or invalid) ---
        int intensity = CompanionMood.DEFAULT_INTENSITY;
        try {
            if (moodObj.has(FIELD_INTENSITY)) {
                JsonElement intensityEl = moodObj.get(FIELD_INTENSITY);
                if (intensityEl != null && intensityEl.isJsonPrimitive()) {
                    intensity = intensityEl.getAsInt();
                }
            }
        } catch (Exception ignored) {
            intensity = CompanionMood.DEFAULT_INTENSITY;
        }
        intensity = CompanionMood.clampIntensity(intensity);

        // --- Memorable flag (read; false if absent or non-boolean) ---
        boolean memorable = false;
        try {
            if (moodObj.has(FIELD_MEMORABLE)) {
                JsonElement memorableEl = moodObj.get(FIELD_MEMORABLE);
                if (memorableEl != null && memorableEl.isJsonPrimitive()) {
                    memorable = memorableEl.getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            memorable = false;
        }

        // --- Build the new mood (timestamped now) ---
        CompanionMood newMood = new CompanionMood(newLabel, cause, intensity, System.currentTimeMillis());
        return MoodUpdateResult.updated(newMood, memorable);
    }

    // =========================================================================
    // Result carrier
    // =========================================================================

    /**
     * Carries the result of a {@link MoodUpdate#apply} call: the (possibly updated or unchanged)
     * companion mood, whether the label was unrecognised, and whether the model flagged the
     * transition as memorable (for Workstream 4 — mood→memory).
     *
     * <p>All three fields are always populated; the caller does not need to null-check them.
     */
    public static final class MoodUpdateResult {

        private final CompanionMood mood;
        private final boolean invalidLabel;
        private final boolean memorable;
        private final boolean updated;

        private MoodUpdateResult(CompanionMood mood, boolean invalidLabel, boolean memorable,
                                 boolean updated) {
            this.mood         = mood;
            this.invalidLabel = invalidLabel;
            this.memorable    = memorable;
            this.updated      = updated;
        }

        /** Factory: mood is unchanged, no error. */
        static MoodUpdateResult unchanged(CompanionMood current) {
            return new MoodUpdateResult(current, false, false, false);
        }

        /** Factory: a "label" key was present but not in the vocabulary; mood is unchanged. */
        static MoodUpdateResult invalidLabel(CompanionMood current) {
            return new MoodUpdateResult(current, true, false, false);
        }

        /** Factory: a valid new mood was produced. */
        static MoodUpdateResult updated(CompanionMood newMood, boolean memorable) {
            return new MoodUpdateResult(newMood, false, memorable, true);
        }

        // -----------------------------------------------------------------
        // Accessors
        // -----------------------------------------------------------------

        /**
         * The resulting companion mood. This is always non-null: either the passed-in current mood
         * (when the model declared nothing, or the label was invalid) or the new mood (when the
         * label was valid). The caller should write this back via
         * {@code aiPersistantData.updateMood(result.mood())} regardless — when unchanged the write
         * is effectively a no-op (same object).
         */
        public CompanionMood mood() {
            return mood;
        }

        /**
         * {@code true} when the LLM reply contained a {@code "label"} key whose value was not in
         * the {@link MoodLabel} vocabulary. The caller should emit exactly one model-facing
         * {@code InfoMessage} listing valid labels and noting the mood was not changed (mirroring
         * the invalid-marker reporting at {@code AgentConversationData.java:834–841}). No player
         * chat; no crash.
         */
        public boolean invalidLabel() {
            return invalidLabel;
        }

        /**
         * {@code true} when the model set {@code "memorable": true} on this mood declaration.
         * Used by the mood→memory gate (Workstream 4): this flag alone does not mint a memory
         * event — the deterministic code rule in {@code MoodMemoryRule} must also confirm the
         * transition is non-trivial.
         */
        public boolean memorable() {
            return memorable;
        }

        /**
         * Convenience: was a NEW mood actually applied (i.e. a valid new label was resolved and a
         * fresh {@link CompanionMood} produced)? {@code false} for the unchanged case (absent/empty
         * mood object or missing {@code label} key) and for the invalid-label case. Set only by the
         * {@link #updated(CompanionMood, boolean)} factory so the name matches the behaviour.
         */
        public boolean wasUpdated() {
            return updated;
        }
    }
}
