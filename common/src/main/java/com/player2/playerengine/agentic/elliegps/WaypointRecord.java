package com.player2.playerengine.agentic.elliegps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * A single EllieGPS waypoint record in the schema-v1 envelope.
 *
 * <p><b>Schema (JSON):</b>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "id":        "minecraft:overworld|120,64,-35",
 *   "type":      "inventory",
 *   "dimension": "minecraft:overworld",
 *   "pos":       [120, 64, -35],
 *   "description": "...",
 *   "keywords":  ["metals", "iron ingot", ...],
 *   "origin":    "bot_placed | explicit_create | heuristic_pass",
 *   "stale":     false,
 *   "createdGameTime": 123456,
 *   "updatedGameTime": 234567,
 *   "data": { ... }
 * }
 * }</pre>
 *
 * <p>The {@code id} is {@code dimensionId + "|" + "x,y,z"} keyed on the canonical position
 * so both halves of a double chest map to exactly one record.
 *
 * <p>Unknown {@code type} values and unknown JSON fields are preserved verbatim on round-trip
 * (forward compatibility). Inventory and supported farm records are indexable.
 *
 * <p>The {@code data} field is always present for inventory-type records and holds an
 * {@link InventoryWaypointData} instance. Unknown types carry raw JSON.
 *
 * <p>Origin tokens: {@code bot_placed}, {@code explicit_create}, {@code heuristic_pass} —
 * audit trail only; not used for retrieval or index decisions.
 */
public final class WaypointRecord {

    // -------------------------------------------------------------------------
    // Schema constant
    // -------------------------------------------------------------------------

    /** Current schema version. Bump when the JSON envelope changes. */
    public static final int WAYPOINT_SCHEMA_VERSION = 1;

    // -------------------------------------------------------------------------
    // Envelope fields (Gson-serialized verbatim)
    // -------------------------------------------------------------------------

    public int schemaVersion = WAYPOINT_SCHEMA_VERSION;
    public String id;
    public String type;
    public String dimension;
    /** Canonical position as {@code [x, y, z]}. Never null for a valid record. */
    public int[] pos;
    public String description;
    public List<String> keywords;
    /**
     * Origin token: one of {@code bot_placed}, {@code explicit_create}, {@code heuristic_pass}.
     * Audit trail only.
     */
    public String origin;
    public boolean stale;
    public long createdGameTime;
    public long updatedGameTime;

    /**
     * The type-specific data object. For {@link WaypointTypes#INVENTORY} this is an
     * {@link InventoryWaypointData}; for {@link WaypointTypes#FARM} it is a
     * {@link FarmWaypointData}. Unknown types carry the raw Gson element.
     * Null when absent.
     *
     * <p>Gson serializes this as a JSON object in the {@code "data"} field.
     */
    public Object data;

    /**
     * The original parsed JSON envelope this record was loaded from, or {@code null} for
     * records built fresh in this session. Retained so unknown envelope fields written by a
     * newer build round-trip verbatim on save (Decision 2 forward compatibility):
     * {@link #toJson()} starts from a deep copy of this object and overlays the known fields.
     *
     * <p>Transient: never serialized itself; package-private so the ingestion service can carry
     * it across a refresh ({@link #inheritUnknownFieldsFrom(WaypointRecord)}).
     */
    transient JsonObject rawSource;

    // -------------------------------------------------------------------------
    // Gson instance for EllieGPS records (pretty, no MC type adapters needed)
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Gson-compatible no-arg constructor. */
    public WaypointRecord() {}

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /**
     * Derives the canonical waypoint id from a dimension id and canonical position.
     *
     * <p>Format: {@code "<dimensionId>|<x>,<y>,<z>"}, e.g. {@code "minecraft:overworld|120,64,-35"}.
     */
    public static String idFor(String dimensionId, BlockPos canonicalPos) {
        return dimensionId + "|" + canonicalPos.getX() + "," + canonicalPos.getY() + "," + canonicalPos.getZ();
    }

    /**
     * Returns the canonical position as a {@link BlockPos}.
     * Returns {@code null} if {@link #pos} is missing or malformed.
     */
    public BlockPos canonicalBlockPos() {
        if (pos == null || pos.length < 3) return null;
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    /**
     * Returns the secondary position as a {@link BlockPos}, or {@code null} when absent.
     * Only meaningful for {@link WaypointTypes#INVENTORY} records with a double-chest.
     */
    public BlockPos secondaryBlockPos() {
        if (!(data instanceof InventoryWaypointData inv)) return null;
        int[] sp = inv.secondaryPos;
        if (sp == null || sp.length < 3) return null;
        return new BlockPos(sp[0], sp[1], sp[2]);
    }

    /**
     * Returns the typed {@link InventoryWaypointData} for inventory-type records, or
     * {@code null} for records of other types (or when {@link #data} is not yet parsed).
     */
    public InventoryWaypointData inventoryData() {
        if (data instanceof InventoryWaypointData inv) return inv;
        return null;
    }

    /** Returns typed farm data for farm records, or {@code null} for every other shape. */
    public FarmWaypointData farmData() {
        if (data instanceof FarmWaypointData farm) return farm;
        return null;
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    /**
     * Serializes this record to a {@link JsonElement} suitable for embedding in the store's
     * {@code "waypoints"} array.
     *
     * <p>For inventory-type records the {@code data} object is serialized as an
     * {@link InventoryWaypointData}. For other types the raw {@code data} object (if it is
     * already a {@code JsonElement}) is preserved verbatim.
     *
     * <p><b>Forward compatibility (Decision 2):</b> when this record was parsed from JSON, the
     * envelope starts as a deep copy of the original object and the known fields are overlaid
     * onto it ({@code JsonObject.add} replaces existing members) — any envelope field this
     * build does not recognize is carried through unchanged.
     */
    public JsonElement toJson() {
        JsonObject envelope = (rawSource != null) ? rawSource.deepCopy() : new JsonObject();
        envelope.addProperty("schemaVersion", schemaVersion);
        envelope.addProperty("id", id);
        envelope.addProperty("type", type);
        envelope.addProperty("dimension", dimension);
        // pos as int array
        com.google.gson.JsonArray posArr = new com.google.gson.JsonArray();
        if (pos != null && pos.length >= 3) {
            posArr.add(pos[0]); posArr.add(pos[1]); posArr.add(pos[2]);
        }
        envelope.add("pos", posArr);
        envelope.addProperty("description", description != null ? description : "");
        com.google.gson.JsonArray kw = new com.google.gson.JsonArray();
        if (keywords != null) { for (String k : keywords) kw.add(k); }
        envelope.add("keywords", kw);
        envelope.addProperty("origin", origin != null ? origin : "");
        envelope.addProperty("stale", stale);
        envelope.addProperty("createdGameTime", createdGameTime);
        envelope.addProperty("updatedGameTime", updatedGameTime);
        // data
        if (WaypointTypes.INVENTORY.equals(type) && data instanceof InventoryWaypointData inv) {
            envelope.add("data", GSON.toJsonTree(inv));
        } else if (WaypointTypes.FARM.equals(type) && data instanceof FarmWaypointData farm) {
            envelope.add("data", farm.toJson());
        } else if (data instanceof JsonElement je) {
            envelope.add("data", je);
        } else if (data != null) {
            envelope.add("data", GSON.toJsonTree(data));
        } else if (!envelope.has("data")) {
            // Only synthesize an empty data object when the original envelope had none —
            // a non-object "data" member from a newer build round-trips via rawSource.
            envelope.add("data", new JsonObject());
        }
        return envelope;
    }

    /**
     * Carries the retained original JSON envelope (unknown-field source) from {@code other}
     * onto this record. Used by the ingestion service when refreshing an existing record so a
     * newer build's unknown envelope fields survive an audit/create refresh on this build.
     * No-op when {@code other} is null or has no retained source.
     */
    void inheritUnknownFieldsFrom(WaypointRecord other) {
        if (other != null && other.rawSource != null) {
            this.rawSource = other.rawSource.deepCopy();
        }
    }

    /**
     * Returns a deep defensive copy, including unknown envelope fields and type-specific payload.
     * Store query APIs use this method so callers can never mutate the authoritative in-memory map.
     */
    public WaypointRecord copy() {
        WaypointRecord copied = fromJson(toJson());
        if (copied == null) {
            throw new IllegalStateException("could not copy waypoint record");
        }
        return copied;
    }

    /**
     * Persisted semantic equality used by checked mutations. Scan time alone is deliberately ignored;
     * every other envelope, payload, and retained-extra field participates.
     */
    public boolean semanticallyEquals(WaypointRecord other) {
        if (other == null) {
            return false;
        }
        JsonElement left = toJson().deepCopy();
        JsonElement right = other.toJson().deepCopy();
        if (left.isJsonObject()) {
            left.getAsJsonObject().remove("updatedGameTime");
        }
        if (right.isJsonObject()) {
            right.getAsJsonObject().remove("updatedGameTime");
        }
        return left.equals(right);
    }

    /**
     * Deserializes a {@link WaypointRecord} from a {@link JsonElement}.
     *
     * <p>For inventory-type records, the {@code "data"} object is parsed into an
     * {@link InventoryWaypointData}. For unknown types, {@code data} is left as a
     * {@code JsonElement} to round-trip verbatim.
     *
     * <p>Returns {@code null} (with a WARN log) if the element is malformed.
     */
    public static WaypointRecord fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            JsonObject obj = element.getAsJsonObject();
            WaypointRecord r = new WaypointRecord();
            // Retain the original envelope so unknown fields round-trip on save (Decision 2).
            r.rawSource       = obj.deepCopy();
            r.schemaVersion   = obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : 0;
            r.id              = obj.has("id") ? obj.get("id").getAsString() : null;
            r.type            = obj.has("type") ? obj.get("type").getAsString() : null;
            r.dimension       = obj.has("dimension") ? obj.get("dimension").getAsString() : null;
            if (obj.has("pos") && obj.get("pos").isJsonArray()) {
                com.google.gson.JsonArray arr = obj.get("pos").getAsJsonArray();
                if (arr.size() == 3) {
                    r.pos = new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
                }
            }
            r.description     = obj.has("description") ? obj.get("description").getAsString() : "";
            if (obj.has("keywords") && obj.get("keywords").isJsonArray()) {
                com.google.gson.JsonArray arr = obj.get("keywords").getAsJsonArray();
                java.util.ArrayList<String> kws = new java.util.ArrayList<>();
                for (JsonElement e : arr) kws.add(e.getAsString());
                r.keywords = java.util.Collections.unmodifiableList(kws);
            } else {
                r.keywords = List.of();
            }
            r.origin          = obj.has("origin") ? obj.get("origin").getAsString() : "";
            r.stale           = obj.has("stale") && obj.get("stale").getAsBoolean();
            r.createdGameTime = obj.has("createdGameTime") ? obj.get("createdGameTime").getAsLong() : 0L;
            r.updatedGameTime = obj.has("updatedGameTime") ? obj.get("updatedGameTime").getAsLong() : 0L;
            // Parse data
            if (obj.has("data") && obj.get("data").isJsonObject()) {
                JsonElement dataElem = obj.get("data");
                if (WaypointTypes.INVENTORY.equals(r.type)) {
                    r.data = GSON.fromJson(dataElem, InventoryWaypointData.class);
                } else if (WaypointTypes.FARM.equals(r.type)) {
                    r.data = FarmWaypointData.fromJson(dataElem);
                } else {
                    // Unknown type: preserve raw
                    r.data = dataElem;
                }
            }
            return r;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS: failed to parse waypoint record: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Origin token constants (audit trail; not retrieval keys)
    // -------------------------------------------------------------------------

    public static final String ORIGIN_BOT_PLACED      = "bot_placed";
    public static final String ORIGIN_EXPLICIT_CREATE  = "explicit_create";
    public static final String ORIGIN_HEURISTIC_PASS   = "heuristic_pass";
}
