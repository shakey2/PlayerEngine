package com.player2.playerengine.memory.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed, immutable shape of one memory-extraction LLM reply (Phase D, W3).
 *
 * <p>The on-the-wire JSON is the AriGraph-style atomic-triplet shape
 * {@code {entities[], relations[], keywords[], importance}} (see {@link MemoryExtractionPrompt}).
 * This is a <em>pure value object</em>: it holds the raw parsed fields verbatim. All write-time
 * capping, taxonomy filtering, dangling-relation pruning, and importance clamping happen
 * downstream in {@link MemoryExtractionValidator} — never here. Nothing in this class touches the
 * graph, the store, the network, Minecraft, or a loader API.
 *
 * <h3>Field shapes</h3>
 * <ul>
 *   <li>{@link Entity}: {@code name}, {@code type} (lenient wire token), optional {@code content}
 *       (one-line factual summary) and {@code aliases}/{@code tags}, {@code importance} per-entity
 *       hint.</li>
 *   <li>{@link Relation}: {@code from}, {@code to} (entity names), {@code relation} (verb phrase),
 *       optional {@code emotion} (enricher default {@code neutral}).</li>
 *   <li>{@code keywords}: free retrieval keywords for the batch's episodic provenance vertex.</li>
 *   <li>{@code importance}: the batch-level Generative-Agents importance produced INLINE in the
 *       same call (no second call); clamped to {@code [1,10]} by the validator.</li>
 * </ul>
 */
public final class MemoryExtractionResponse {

    /** One extracted entity (a graph node candidate). Raw, uncapped, unvalidated. */
    public static final class Entity {
        public final String name;
        public final String type;        // lenient wire token (MemoryNodeType.wire()) or raw
        public final String content;     // one-line factual summary, nullable
        public final List<String> aliases;
        public final List<String> tags;
        public final int importance;     // per-entity hint, 0 = unscored

        public Entity(String name, String type, String content,
                      List<String> aliases, List<String> tags, int importance) {
            this.name = name;
            this.type = type;
            this.content = content;
            this.aliases = aliases == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(aliases));
            this.tags = tags == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(tags));
            this.importance = importance;
        }
    }

    /** One extracted relation (a graph edge candidate keyed on entity NAMES). Raw, unvalidated. */
    public static final class Relation {
        public final String from;        // entity name
        public final String to;          // entity name
        public final String relation;    // verb phrase
        public final String emotion;     // nullable; enricher defaults to "neutral"

        public Relation(String from, String to, String relation, String emotion) {
            this.from = from;
            this.to = to;
            this.relation = relation;
            this.emotion = emotion;
        }
    }

    private final List<Entity> entities;
    private final List<Relation> relations;
    private final List<String> keywords;
    private final int importance;        // batch-level importance, [1,10] after validation

    public MemoryExtractionResponse(List<Entity> entities, List<Relation> relations,
                                    List<String> keywords, int importance) {
        this.entities = entities == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(entities));
        this.relations = relations == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(relations));
        this.keywords = keywords == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(keywords));
        this.importance = importance;
    }

    public List<Entity> entities()  { return entities; }
    public List<Relation> relations() { return relations; }
    public List<String> keywords()  { return keywords; }
    public int importance()         { return importance; }

    public boolean isEmpty() {
        return entities.isEmpty() && relations.isEmpty();
    }
}
