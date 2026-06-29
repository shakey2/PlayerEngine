package com.player2.playerengine.player2api.mood;

/**
 * Deterministic gate that decides whether a mood transition is narratively meaningful enough to
 * mint a memory EVENT node (Lightweight Companion Mood System — Workstream 4).
 *
 * <p>The overall trigger is <b>model-flagged AND code-confirmed</b>. The model sets
 * {@code "memorable": true} as the primary signal that a transition is worth remembering. This rule
 * is the secondary, deterministic filter: it blocks the model from minting empty or no-op events
 * even when it sets {@code memorable:true}. All three conditions must hold:
 *
 * <ol>
 *   <li>The new label differs from the previous label (an actual transition, not a re-assert).</li>
 *   <li>A non-blank {@code cause} is present on the new mood (the model gave a reason).</li>
 *   <li>The transition is non-trivial: {@code intensity >= 4} <b>OR</b> a crossing from a negative
 *       to a positive label (SAD/ANGRY/AFRAID/ANXIOUS → HAPPY/CONTENT/EXCITED — the "cheered me up"
 *       case). Transition direction is computed code-side (deterministic).</li>
 * </ol>
 *
 * <p><b>Intensity threshold note:</b> the default intensity is {@link CompanionMood#DEFAULT_INTENSITY}
 * = 3, which is intentionally <em>below</em> the {@code >= 4} arm. An omitted intensity defaults to
 * 3 and does NOT auto-pass on intensity alone; it must then satisfy the negative→positive crossing
 * arm. Do NOT lower the threshold to {@code >= 3}: the deliberate gap prevents the neutral default
 * from silently flooding memory on every label change.
 *
 * <p>No Minecraft, loader, or I/O dependency — common module only.
 */
public final class MoodMemoryRule {

    private MoodMemoryRule() {}

    // -------------------------------------------------------------------------
    // Label classification tables (code-side, deterministic)
    // -------------------------------------------------------------------------

    /** Labels that represent a negative emotional state for the crossing-to-positive check. */
    private static boolean isNegative(MoodLabel label) {
        if (label == null) return false;
        switch (label) {
            case SAD:
            case ANGRY:
            case AFRAID:
            case ANXIOUS:
                return true;
            default:
                return false;
        }
    }

    /** Labels that represent a positive emotional state for the crossing-from-negative check. */
    private static boolean isPositive(MoodLabel label) {
        if (label == null) return false;
        switch (label) {
            case HAPPY:
            case CONTENT:
            case EXCITED:
                return true;
            default:
                return false;
        }
    }

    // -------------------------------------------------------------------------
    // Public predicate
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} iff the mood transition from {@code prev} to {@code next} is narratively
     * meaningful enough to mint a memory EVENT node.
     *
     * <p>All of the following must hold:
     * <ul>
     *   <li>{@code memorableFlag} is {@code true} (model declared the transition memorable).</li>
     *   <li>The new label differs from the previous label (actual transition, not a re-assert).</li>
     *   <li>The new mood has a non-blank cause (a reason was given).</li>
     *   <li>The transition is non-trivial: {@code intensity >= 4} OR a negative→positive crossing
     *       (SAD/ANGRY/AFRAID/ANXIOUS → HAPPY/CONTENT/EXCITED).</li>
     * </ul>
     *
     * <p>Null arguments are treated conservatively (returns {@code false}), never throws.
     *
     * @param prev          the companion's mood before this turn (may be {@link CompanionMood#neutral()})
     * @param next          the newly declared mood (already normalized + capped by {@code CompanionMood})
     * @param memorableFlag {@code true} when the model set {@code "memorable": true} in its reply
     * @return {@code true} to mint an EVENT node; {@code false} to skip
     */
    public static boolean isMeaningfulTransition(CompanionMood prev,
                                                 CompanionMood next,
                                                 boolean memorableFlag) {
        // (0) Model must have flagged this as memorable.
        if (!memorableFlag) {
            return false;
        }

        // Guard nulls conservatively.
        if (prev == null || next == null) {
            return false;
        }

        // (1) Must be an actual label change (not a re-assert of the same label).
        if (next.label() == prev.label()) {
            return false;
        }

        // (2) Non-blank cause required (the model gave a reason).
        String cause = next.cause();
        if (cause == null || cause.isBlank()) {
            return false;
        }

        // (3) Non-trivial: intensity >= 4 OR negative→positive crossing.
        if (next.intensity() >= 4) {
            return true;
        }
        if (isNegative(prev.label()) && isPositive(next.label())) {
            return true;
        }

        return false;
    }
}
