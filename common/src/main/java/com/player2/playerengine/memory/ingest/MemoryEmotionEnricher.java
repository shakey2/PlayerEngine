package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryCaps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, zero-LLM post-processing of a validated {@link MemoryExtractionResponse}
 * (Phase D, W3). Runs AFTER {@link MemoryExtractionValidator}; never makes a network call.
 *
 * <p>Two guarantees the plan (§601) requires before a batch becomes graph mutations:
 * <ul>
 *   <li><b>Every relation carries a bounded emotion</b> from the closed {@link Emotion} enum.
 *       A null/blank/unrecognized emotion token clamps to {@link Emotion#NEUTRAL}. This keeps the
 *       emotion model-facing surface a closed, templated vocabulary (never raw model text).</li>
 *   <li><b>Keywords are never empty</b>: when the model returned none, they are derived from the
 *       accepted entity names (the durable nouns of the batch).</li>
 * </ul>
 *
 * <p>The enricher returns a NEW response carrying the same entities but emotion-normalized
 * relations and a non-empty keyword list. No Minecraft, loader, log, or stack-trace dependency.
 */
public final class MemoryEmotionEnricher {

    /** The closed emotion vocabulary stored on a relation (Plutchik-ish; bounded model-facing token). */
    public enum Emotion {
        NEUTRAL, JOY, TRUST, FEAR, SURPRISE, SADNESS, DISGUST, ANGER, ANTICIPATION;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Lenient parse; null/blank/unknown → {@link #NEUTRAL}. */
        public static Emotion fromWireOrNeutral(String raw) {
            if (raw == null) return NEUTRAL;
            String t = raw.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) return NEUTRAL;
            for (Emotion e : values()) {
                if (e.name().equals(t)) return e;
            }
            return NEUTRAL;
        }
    }

    private MemoryEmotionEnricher() {}

    /**
     * Normalizes emotions to the closed enum and guarantees a non-empty keyword list.
     *
     * @param validated the result of {@link MemoryExtractionValidator#validate} (never null here)
     * @return a new response with clamped emotions and non-empty keywords
     */
    public static MemoryExtractionResponse enrich(MemoryExtractionResponse validated) {
        if (validated == null) {
            return null;
        }

        // Emotion-normalize every relation to the closed enum token.
        List<MemoryExtractionResponse.Relation> relations = new ArrayList<>(validated.relations().size());
        for (MemoryExtractionResponse.Relation r : validated.relations()) {
            String emotionToken = Emotion.fromWireOrNeutral(r.emotion).wire();
            relations.add(new MemoryExtractionResponse.Relation(r.from, r.to, r.relation, emotionToken));
        }

        // Keywords never empty: derive from entity names when absent.
        List<String> keywords = new ArrayList<>(validated.keywords());
        if (keywords.isEmpty()) {
            Set<String> derived = new LinkedHashSet<>();
            for (MemoryExtractionResponse.Entity e : validated.entities()) {
                String kw = MemoryCaps.capName(e.name);
                if (kw != null && !kw.isEmpty()) {
                    derived.add(kw);
                }
                if (derived.size() >= MemoryExtractionValidator.MAX_KEYWORDS) break;
            }
            keywords = new ArrayList<>(derived);
        }

        return new MemoryExtractionResponse(validated.entities(), relations, keywords, validated.importance());
    }
}
