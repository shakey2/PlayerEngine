package com.player2.playerengine.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Gson (de)serialization for {@link MemoryNode} — to/from {@link JsonObject} and {@link String}
 * only. <b>No file I/O</b> (the store owns disk access).
 *
 * <p>Three guarantees on the contract:
 * <ol>
 *   <li><b>Write-time content cap.</b> {@code content} is truncated to {@link #CONTENT_MAX}
 *       characters on serialize — defense-in-depth at the egress boundary even if an upstream
 *       cap was missed. (The authoritative cap lives in W2's {@code MemoryCaps}; this mirrors it
 *       so the codec alone can never emit an over-cap string.)</li>
 *   <li><b>Nullable-vector omission.</b> When {@code vector == null} the {@code vector} (and
 *       {@code vectorModel}) keys are omitted entirely — v1 nodes carry no embedding key.</li>
 *   <li><b>Forward-compat extras.</b> Unknown JSON members on a node round-trip verbatim
 *       (mirrors {@code EllieGPSStore}'s root-level {@code rootExtras} at
 *       {@code EllieGPSStore.java:341,392}, applied here at the node level) so a newer build's
 *       node fields survive a save by this build.</li>
 * </ol>
 */
public final class MemoryNodeCodec {

    private MemoryNodeCodec() {}

    /**
     * Write-time hard cap on {@code content} length (chars). Conservative playtest-tunable
     * placeholder mirroring W2's {@code MemoryCaps.CONTENT_MAX}; this is the egress boundary.
     */
    public static final int CONTENT_MAX = 280;

    private static final Gson GSON = new Gson();

    // Known JSON keys — anything not in this set is preserved as a forward-compat extra.
    private static final Set<String> KNOWN_KEYS = Set.of(
            "id", "content", "type", "canonicalName", "aliases", "tags", "importance",
            "createdTick", "timestampMs", "lastSeenTick", "lastRetrievedTick", "mentionCount",
            "vector", "vectorModel", "schemaVersion");

    /**
     * Serializes a node to a {@link JsonObject}. The {@code vector}/{@code vectorModel} keys are
     * omitted when the node has no vector; {@code content} is capped to {@link #CONTENT_MAX}.
     *
     * @param node    the node to serialize
     * @param extras  optional forward-compat unknown members to re-emit (may be {@code null});
     *                known keys within it are ignored so they cannot shadow real fields
     */
    public static JsonObject toJson(MemoryNode node, JsonObject extras) {
        // Start from preserved unknown members so newer-build fields survive a round-trip.
        JsonObject obj = new JsonObject();
        if (extras != null) {
            for (var entry : extras.entrySet()) {
                if (!KNOWN_KEYS.contains(entry.getKey())) {
                    obj.add(entry.getKey(), entry.getValue());
                }
            }
        }

        obj.addProperty("id", node.id());
        obj.addProperty("content", cap(node.content(), CONTENT_MAX));
        if (node.type() != null) obj.addProperty("type", node.type());
        if (node.canonicalName() != null) obj.addProperty("canonicalName", node.canonicalName());

        obj.add("aliases", toStringArray(node.aliases()));
        obj.add("tags", toStringArray(node.tags()));

        obj.addProperty("importance", node.importance());
        obj.addProperty("createdTick", node.createdTick());
        obj.addProperty("timestampMs", node.timestampMs());
        obj.addProperty("lastSeenTick", node.lastSeenTick());
        obj.addProperty("lastRetrievedTick", node.lastRetrievedTick());
        obj.addProperty("mentionCount", node.mentionCount());

        // Vector key omitted entirely when absent (v1 default).
        float[] vec = node.vector();
        if (vec != null) {
            JsonArray va = new JsonArray(vec.length);
            for (float v : vec) va.add(v);
            obj.add("vector", va);
            if (node.vectorModel() != null) obj.addProperty("vectorModel", node.vectorModel());
        }

        obj.addProperty("schemaVersion", node.schemaVersion());
        return obj;
    }

    /** Serializes a node with no preserved extras. */
    public static JsonObject toJson(MemoryNode node) {
        return toJson(node, null);
    }

    /** Serializes a node to a JSON string (no extras). */
    public static String toJsonString(MemoryNode node) {
        return GSON.toJson(toJson(node, null));
    }

    /**
     * Deserializes a node from a {@link JsonObject}. Missing fields fall back to sensible
     * defaults; {@code content} is capped on read as well so a hand-edited oversized file cannot
     * load an over-cap string. Unknown members are <em>not</em> consumed here — callers that need
     * round-trip preservation should call {@link #extractExtras(JsonObject)} and feed the result
     * back into {@link #toJson(MemoryNode, JsonObject)}.
     */
    public static MemoryNode fromJson(JsonObject obj) {
        String id            = optString(obj, "id", null);
        String content       = cap(optString(obj, "content", ""), CONTENT_MAX);
        String type          = optString(obj, "type", null);
        String canonicalName = optString(obj, "canonicalName", null);
        String[] aliases     = optStringArray(obj, "aliases");
        String[] tags        = optStringArray(obj, "tags");
        int importance       = optInt(obj, "importance", 0);
        long createdTick     = optLong(obj, "createdTick", 0L);
        long timestampMs     = optLong(obj, "timestampMs", 0L);
        long lastSeenTick    = optLong(obj, "lastSeenTick", 0L);
        long lastRetrieved   = optLong(obj, "lastRetrievedTick", 0L);
        int mentionCount     = optInt(obj, "mentionCount", 0);
        int schemaVersion    = optInt(obj, "schemaVersion", MemoryNode.CURRENT_SCHEMA_VERSION);

        float[] vector = null;
        String vectorModel = null;
        if (obj.has("vector") && obj.get("vector").isJsonArray()) {
            JsonArray va = obj.getAsJsonArray("vector");
            vector = new float[va.size()];
            for (int i = 0; i < va.size(); i++) vector[i] = va.get(i).getAsFloat();
            vectorModel = optString(obj, "vectorModel", null);
        }

        return new MemoryNode(id, content, type, canonicalName, aliases, tags, importance,
                createdTick, timestampMs, lastSeenTick, lastRetrieved, mentionCount,
                vector, vectorModel, schemaVersion);
    }

    /**
     * Extracts the forward-compat unknown members of a node JSON object (everything not in
     * {@link #KNOWN_KEYS}), or {@code null} if there are none. Pair with
     * {@link #toJson(MemoryNode, JsonObject)} to round-trip them.
     */
    public static JsonObject extractExtras(JsonObject obj) {
        JsonObject extras = new JsonObject();
        for (var entry : obj.entrySet()) {
            if (!KNOWN_KEYS.contains(entry.getKey())) {
                extras.add(entry.getKey(), entry.getValue());
            }
        }
        return extras.size() > 0 ? extras : null;
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static JsonArray toStringArray(List<String> values) {
        JsonArray arr = new JsonArray(values.size());
        for (String v : values) arr.add(v);
        return arr;
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive())
                ? obj.get(key).getAsString() : fallback;
    }

    private static int optInt(JsonObject obj, String key, int fallback) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive())
                ? obj.get(key).getAsInt() : fallback;
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        return (obj.has(key) && obj.get(key).isJsonPrimitive())
                ? obj.get(key).getAsLong() : fallback;
    }

    private static String[] optStringArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return new String[0];
        JsonArray arr = obj.getAsJsonArray(key);
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out.toArray(new String[0]);
    }
}
