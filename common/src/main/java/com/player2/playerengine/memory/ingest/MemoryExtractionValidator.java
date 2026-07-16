package com.player2.playerengine.memory.ingest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryDurableFactClassifier;
import com.player2.playerengine.memory.MemoryNodeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CLIENT-SIDE validation + write-time cap enforcement for one memory-extraction reply (Phase D, W3).
 *
 * <p>This is the DESIGN.md §3 egress-enforcement choke point on the ingestion side: an arbitrary
 * user-bound Default model produced the reply, so NOTHING from it is trusted. Every string is
 * length-capped via {@link MemoryCaps}, every collection is count-capped, out-of-taxonomy entity
 * types are dropped, relations referencing unknown entities are pruned (no dangling edges), and the
 * batch importance is clamped to {@code [1,10]}. A 10 KB {@code content} is truncated to
 * {@link MemoryCaps#CONTENT_MAX}; malformed JSON yields {@code null} (the caller logs WARN and keeps
 * the graph unchanged — no false success).
 *
 * <p>Hard collection caps (plan §598): entities ≤ {@link #MAX_ENTITIES}, relations ≤
 * {@link #MAX_RELATIONS}, keywords ≤ {@link #MAX_KEYWORDS}.
 *
 * <p>No Minecraft, loader, network, log, or stack-trace dependency. {@link #validate} never throws.
 */
public final class MemoryExtractionValidator {

    /** Max entities kept from one extraction batch. */
    public static final int MAX_ENTITIES = 12;
    /** Max relations kept from one extraction batch. */
    public static final int MAX_RELATIONS = 16;
    /** Max keywords kept from one extraction batch. */
    public static final int MAX_KEYWORDS = 12;

    /** Importance clamp bounds (Generative-Agents 1-10 poignancy). */
    public static final int IMPORTANCE_MIN = 1;
    public static final int IMPORTANCE_MAX = 10;

    private MemoryExtractionValidator() {}

    /**
     * Parses and fully validates a raw model reply. Returns {@code null} when the reply is null,
     * blank, not a JSON object, or contains no usable entity (an extraction that produced nothing
     * durable). Never throws.
     */
    public static MemoryExtractionResponse validate(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return null;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(rawReply);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException parseFailure) {
            // Malformed JSON — distilled, bounded reason only; never the raw reply or a stack.
            return null;
        }

        // --- Entities (taxonomy-filtered, capped) ---
        List<MemoryExtractionResponse.Entity> entities = new ArrayList<>();
        Set<String> acceptedNames = new HashSet<>(); // lower-cased canonical names, for dangling check
        JsonArray entityArr = optArray(root, "entities");
        if (entityArr != null) {
            for (JsonElement el : entityArr) {
                if (entities.size() >= MAX_ENTITIES) break;
                if (!el.isJsonObject()) continue;
                MemoryExtractionResponse.Entity e = validateEntity(el.getAsJsonObject());
                if (e == null) continue;
                entities.add(e);
                acceptedNames.add(e.name.toLowerCase(Locale.ROOT));
            }
        }
        if (entities.isEmpty()) {
            // Nothing durable to remember — not an error, just a no-op batch.
            return null;
        }

        // --- Relations (drop dangling: both endpoints must be accepted entities) ---
        List<MemoryExtractionResponse.Relation> relations = new ArrayList<>();
        JsonArray relArr = optArray(root, "relations");
        if (relArr != null) {
            for (JsonElement el : relArr) {
                if (relations.size() >= MAX_RELATIONS) break;
                if (!el.isJsonObject()) continue;
                MemoryExtractionResponse.Relation r = validateRelation(el.getAsJsonObject(), acceptedNames);
                if (r == null) continue;
                relations.add(r);
            }
        }

        // --- Keywords (capped, de-duplicated, per-string name-capped) ---
        List<String> keywords = capStringList(optArray(root, "keywords"), MAX_KEYWORDS);

        // --- Batch importance (clamped) ---
        int importance = clampImportance(optInt(root, "importance", 0));

        return new MemoryExtractionResponse(entities, relations, keywords, importance);
    }

    // -------------------------------------------------------------------------
    // Entity / relation validation
    // -------------------------------------------------------------------------

    private static MemoryExtractionResponse.Entity validateEntity(JsonObject o) {
        String name = MemoryCaps.capName(trimOrNull(optString(o, "name", null)));
        if (name == null || name.isEmpty()) {
            return null; // an entity with no name is unusable
        }
        // Type must be one of the known taxonomy tokens; otherwise drop the entity (plan §598).
        String rawType = trimOrNull(optString(o, "type", null));
        if (rawType == null || !MemoryNodeType.isKnown(rawType)) {
            return null;
        }

        String content = MemoryCaps.capContent(emptyIfNull(optString(o, "content", "")));
        List<String> aliases = capNameList(optArray(o, "aliases"), MemoryCaps.ALIASES_MAX);
        List<String> tags = capNameList(optArray(o, "tags"), MemoryCaps.TAGS_MAX);
        int importance = clampImportanceOrUnscored(optInt(o, "importance", 0));
        MemoryNodeType type = MemoryDurableFactClassifier.normalizeExtractedType(
                MemoryNodeType.fromWire(rawType), name, content, tags);
        if (type == null) {
            return null;
        }

        return new MemoryExtractionResponse.Entity(name, type.wire(), content, aliases, tags, importance);
    }

    private static MemoryExtractionResponse.Relation validateRelation(JsonObject o, Set<String> accepted) {
        String from = MemoryCaps.capName(trimOrNull(optString(o, "from", null)));
        String to = MemoryCaps.capName(trimOrNull(optString(o, "to", null)));
        String relation = MemoryCaps.capRelation(trimOrNull(optString(o, "relation", null)));
        if (from == null || to == null || relation == null
                || from.isEmpty() || to.isEmpty() || relation.isEmpty()) {
            return null;
        }
        // No dangling edges: both endpoints must reference an accepted entity name.
        if (!accepted.contains(from.toLowerCase(Locale.ROOT))
                || !accepted.contains(to.toLowerCase(Locale.ROOT))) {
            return null;
        }
        // emotion is enriched downstream; keep the raw (capped) token here, may be null.
        String emotion = trimOrNull(optString(o, "emotion", null));
        if (emotion != null) {
            emotion = MemoryCaps.capName(emotion);
        }
        return new MemoryExtractionResponse.Relation(from, to, relation, emotion);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> capNameList(JsonArray arr, int maxCount) {
        if (arr == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (out.size() >= maxCount) break;
            if (!el.isJsonPrimitive()) continue;
            String s = MemoryCaps.capName(trimOrNull(safeAsString(el)));
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return new ArrayList<>(out);
    }

    private static List<String> capStringList(JsonArray arr, int maxCount) {
        if (arr == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (out.size() >= maxCount) break;
            if (!el.isJsonPrimitive()) continue;
            String s = MemoryCaps.capName(trimOrNull(safeAsString(el)));
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return new ArrayList<>(out);
    }

    private static int clampImportance(int raw) {
        if (raw < IMPORTANCE_MIN) return IMPORTANCE_MIN;
        return Math.min(raw, IMPORTANCE_MAX);
    }

    /** Per-entity importance: 0 stays unscored; otherwise clamp to [1,10]. */
    private static int clampImportanceOrUnscored(int raw) {
        if (raw <= 0) return 0;
        return Math.min(raw, IMPORTANCE_MAX);
    }

    private static JsonArray optArray(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }

    private static String optString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? safeAsString(el) : def;
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return def;
        try {
            return o.get(key).getAsInt();
        } catch (NumberFormatException nfe) {
            return def;
        }
    }

    private static String safeAsString(JsonElement el) {
        try {
            return el.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }
}
