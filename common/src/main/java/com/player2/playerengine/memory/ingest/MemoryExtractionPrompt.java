package com.player2.playerengine.memory.ingest;

/**
 * The static extraction prompt for the Phase D memory pipeline (W3).
 *
 * <p>Synthesises three research lines into one cheap, single-call instruction:
 * <ul>
 *   <li><b>AriGraph atomic triplets</b> — extract entities and {@code (from, relation, to)}
 *       triples, never compound sentences.</li>
 *   <li><b>Graph-of-Group named-significance</b> — keep only durably named, world-significant
 *       entities; drop chit-chat and ephemeral commands ("ok", "go left").</li>
 *   <li><b>Generative-Agents importance</b> — score the batch's overall importance INLINE, in this
 *       same call (no second LLM round-trip), on a 1–10 poignancy scale.</li>
 * </ul>
 *
 * <p><b>Output contract (binding).</b> The model must return a single JSON object — enforced
 * loosely via {@code response_format:{json_object}} and validated CLIENT-SIDE in
 * {@link MemoryExtractionValidator} (the Default/cheapest profile is not guaranteed to honor a
 * server-side {@code json_schema}). Shape:
 * <pre>
 * {
 *   "entities":  [ { "name": "...", "type": "character|place|event|faction|item|fact|preference",
 *                    "content": "one short factual line", "aliases": ["..."],
 *                    "tags": ["..."], "importance": 1-10 } ],
 *   "relations": [ { "from": "name", "to": "name", "relation": "verb phrase",
 *                    "emotion": "neutral|joy|anger|fear|sadness|trust|..." } ],
 *   "keywords":  [ "retrieval", "keywords" ],
 *   "importance": 1-10
 * }
 * </pre>
 *
 * <p><b>Egress.</b> The only dynamic prompt input is the curated {@code ConversationHistory} the
 * caller already built (and which {@link com.player2.playerengine.player2api.LogEgressGuard} caps
 * per message at the {@code MemoryLlmClient} edge). This class contributes only static, authored
 * template text — no log, stack trace, or unbounded external string.
 */
public final class MemoryExtractionPrompt {

    private MemoryExtractionPrompt() {}

    private static final String SYSTEM_PROMPT =
            "You are a memory-extraction module for a Minecraft AI companion. From the recent "
          + "conversation turns, extract durable relationship- or world-significant facts as an atomic knowledge "
          + "graph. Follow these rules strictly:\n"
          + "1. ENTITIES: people, places, events, factions, notable items, and durable owner/player "
          + "facts or preferences the companion should remember long-term. Give each a short canonical "
          + "name and a type from exactly: character, place, event, faction, item, fact, preference. "
          + "Add a one-line factual 'content' summary, optional 'aliases' (other names used), and "
          + "'tags'. For owner/player preferences such as favorite food, color, game, hobbies, or "
          + "dislikes, create a preference node whose content is the atomic preference. For durable "
          + "owner/player facts that are not preferences, create a fact node. Do not store those as "
          + "event-only nodes. FACT/PREFERENCE nodes must be stable long-term knowledge; do not use "
          + "them for one-off tests, deaths, fights, inventory drops, revives, instructions, or what "
          + "happened in the current session.\n"
          + "2. RELATIONS: express facts as atomic triples {from, to, relation}, where 'from' and "
          + "'to' are entity names and 'relation' is a short verb phrase (e.g. 'lives in', "
          + "'allied with', 'gave'). One fact per triple; never compound sentences. Add an "
          + "'emotion' token describing the companion's feeling about the relation if any "
          + "(default 'neutral').\n"
          + "3. SIGNIFICANCE: keep named, durable information the companion should remember: owner/player "
          + "preferences, favorites, dislikes, personal facts, relationship facts, and world facts. DROP "
          + "small talk, acknowledgements, one-off tests, and ephemeral commands such as 'ok', 'go left', "
          + "'follow me'. "
          + "If nothing is worth remembering, return empty 'entities' and 'relations'.\n"
          + "4. KEYWORDS: list short retrieval keywords for this batch.\n"
          + "5. IMPORTANCE: rate the overall poignancy/durability of this batch from 1 (mundane) to "
          + "10 (life-defining), as a single integer 'importance'.\n"
          + "Return ONE JSON object with keys: entities, relations, keywords, importance. "
          + "No prose, no markdown, JSON only.";

    /** The static system prompt (no dynamic input). */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * A short, static instruction appended as the final user turn after the curated conversation
     * turns. Carries no dynamic/unbounded content — the conversation turns themselves are the data
     * (already capped per-message at the egress edge).
     */
    public static String extractionInstruction() {
        return "Extract the durable memory graph from the conversation turns above. "
             + "Respond with the single JSON object described in the system prompt.";
    }
}
