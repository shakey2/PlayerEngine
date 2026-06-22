package com.player2.playerengine.tasks.deferred;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import net.minecraft.core.BlockPos;

/**
 * A single deferred-job record persisted by {@code DeferredJobStore}.
 *
 * <p><b>Schema (JSON, schemaVersion = 1):</b>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "id":                  "minecraft:overworld|120,64,-35",
 *   "kind":                "smelt",
 *   "status":              "COOKING",
 *   "pos":                 [120, 64, -35],
 *   "dimension":           "minecraft:overworld",
 *   "inputItem":           "minecraft:raw_iron",
 *   "outputItem":          "minecraft:iron_ingot",
 *   "inputCount":          16,
 *   "expectedOutputCount": 16,
 *   "fuelItem":            "minecraft:coal",
 *   "fuelCount":           2,
 *   "startGameTime":       123456,
 *   "etaGameTime":         126656,
 *   "owningBotUuid":       "...",
 *   "degradeReason":       ""
 * }
 * }</pre>
 *
 * <p>Unknown fields written by a newer build are preserved verbatim on round-trip via
 * {@link #rawSource} (forward compatibility, mirroring {@code WaypointRecord}).
 *
 * <p>The Block Entity is always the source of truth on load: every reloaded job is reconciled
 * against the live furnace BE before being trusted.
 *
 * <h3>Id format</h3>
 * {@code "<dimensionId>|<x>,<y>,<z>"} (e.g. {@code "minecraft:overworld|120,64,-35"}),
 * derived via {@link #idFor(String, BlockPos)}. Mirrors {@code WaypointRecord.idFor}.
 *
 * <h3>Schema evolution</h3>
 * <ul>
 *   <li>Missing fields default safely (null strings, 0 counts).</li>
 *   <li>Newer schema version → file is quarantined, store starts empty, operator note logged.</li>
 *   <li>Unknown fields survive round-trip via {@link #rawSource}.</li>
 * </ul>
 */
public final class DeferredJobRecord {

    // -------------------------------------------------------------------------
    // Schema version constant
    // -------------------------------------------------------------------------

    /** Current schema version. Bump when the JSON envelope changes incompatibly. */
    public static final int JOB_SCHEMA_VERSION = 1;

    // -------------------------------------------------------------------------
    // Status enum (persisted as the string name)
    // -------------------------------------------------------------------------

    /**
     * Lifecycle status of the deferred job.
     *
     * <p>Transitions: {@code PENDING → COOKING → AWAITING_PICKUP → DONE / DEGRADED}.
     * {@code PENDING} is the initial state before the bot has confirmed loading the furnace;
     * {@code COOKING} is set once input + fuel are written to the BE slots;
     * {@code AWAITING_PICKUP} once the BE reports cook complete (output present, input drained);
     * {@code DONE} after the bot collects the output from the furnace;
     * {@code DEGRADED} on any terminal failure (furnace gone, timeout, etc.).
     */
    public enum Status {
        PENDING,
        COOKING,
        AWAITING_PICKUP,
        DONE,
        DEGRADED
    }

    // -------------------------------------------------------------------------
    // Envelope fields (Gson-serialized verbatim)
    // -------------------------------------------------------------------------

    /** Schema version; always {@link #JOB_SCHEMA_VERSION} for records written by this build. */
    public int schemaVersion = JOB_SCHEMA_VERSION;

    /**
     * Stable identifier for this job: {@code "<dimensionId>|<x>,<y>,<z>"} of the furnace.
     * Two jobs at the same furnace position share the same id (only one should be active).
     */
    public String id;

    /**
     * Short, machine-readable process kind: {@code "smelt"}, {@code "blast"}, or
     * {@code "smoke"}. Matches {@link DeferredProcess#kind()}.
     */
    public String kind;

    /**
     * Current lifecycle status. Stored as the enum constant name (e.g. {@code "COOKING"}).
     */
    public Status status = Status.PENDING;

    /**
     * Furnace block position as {@code [x, y, z]}. Never null for a valid record.
     * Authoritative on resume: never re-scan from BlockScanner when this is present.
     */
    public int[] pos;

    /** Dimension resource-location string (e.g. {@code "minecraft:overworld"}). */
    public String dimension;

    /**
     * Registry name of the input item (e.g. {@code "minecraft:raw_iron"}).
     * Used to reconcile the loaded recipe against the furnace's actual input slot on resume.
     */
    public String inputItem;

    /**
     * Registry name of the expected output item (e.g. {@code "minecraft:iron_ingot"}).
     * Used to detect tampered-output degradation when the furnace output doesn't match.
     */
    public String outputItem;

    /** How many input items were loaded into the furnace for this job. */
    public int inputCount;

    /**
     * How many output items are expected when the job completes
     * (normally equals {@code inputCount * outputYieldPerItem}; usually 1:1 for vanilla recipes).
     */
    public int expectedOutputCount;

    /** Registry name of the fuel item used (e.g. {@code "minecraft:coal"}). */
    public String fuelItem;

    /** How many fuel items were loaded into the fuel slot. */
    public int fuelCount;

    /**
     * The {@code Level.getGameTime()} value when the job was started (input+fuel written to BE).
     * Used for ETA and timeout calculations.
     */
    public long startGameTime;

    /**
     * Planning-hint ETA: {@code startGameTime + inputCount * cookTicksPerItem}.
     * NEVER used as the completion gate; BE state is the source of truth.
     */
    public long etaGameTime;

    /**
     * UUID string of the companion bot that owns this job.
     * A furnace is a world object; multiple bots may have active jobs (distinct furnaces or
     * sequenced jobs at the same furnace).
     */
    public String owningBotUuid;

    /**
     * Machine-readable degradation reason token when {@link #status} is {@link Status#DEGRADED},
     * or empty string / null otherwise. Matches {@link DeferredDegradation#label()}.
     */
    public String degradeReason = "";

    /**
     * The original parsed JSON envelope, retained for forward-compatibility round-tripping.
     * Unknown fields written by a newer build survive a save by this build.
     *
     * <p>Transient: never serialized as a field itself. Package-private for store access.
     */
    transient JsonObject rawSource;

    // -------------------------------------------------------------------------
    // Gson instance
    // -------------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Gson-compatible no-arg constructor required for deserialization. */
    public DeferredJobRecord() {}

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /**
     * Derives the canonical job id from a dimension resource-location string and the
     * furnace block position.
     *
     * <p>Format: {@code "<dimensionId>|<x>,<y>,<z>"}, e.g.
     * {@code "minecraft:overworld|120,64,-35"}. Mirrors {@code WaypointRecord.idFor}.
     */
    public static String idFor(String dimensionId, BlockPos furnacePos) {
        return dimensionId + "|"
                + furnacePos.getX() + "," + furnacePos.getY() + "," + furnacePos.getZ();
    }

    /**
     * Returns the furnace position as a {@link BlockPos}, or {@code null} if {@link #pos} is
     * missing or malformed.
     */
    public BlockPos furnaceBlockPos() {
        if (pos == null || pos.length < 3) return null;
        return new BlockPos(pos[0], pos[1], pos[2]);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * Serializes this record to a {@link JsonElement} for embedding in the store's
     * {@code "jobs"} array.
     *
     * <p><b>Forward compatibility:</b> when loaded from JSON, the envelope starts as a deep
     * copy of the original and known fields are overlaid — unknown fields from a newer build
     * survive this round-trip unchanged. Mirrors {@code WaypointRecord.toJson()}.
     */
    public JsonElement toJson() {
        JsonObject envelope = (rawSource != null) ? rawSource.deepCopy() : new JsonObject();
        envelope.addProperty("schemaVersion", schemaVersion);
        envelope.addProperty("id",            id != null ? id : "");
        envelope.addProperty("kind",          kind != null ? kind : "");
        envelope.addProperty("status",        status != null ? status.name() : Status.PENDING.name());
        JsonArray posArr = new JsonArray();
        if (pos != null && pos.length >= 3) {
            posArr.add(pos[0]); posArr.add(pos[1]); posArr.add(pos[2]);
        }
        envelope.add("pos", posArr);
        envelope.addProperty("dimension",           dimension != null ? dimension : "");
        envelope.addProperty("inputItem",           inputItem != null ? inputItem : "");
        envelope.addProperty("outputItem",          outputItem != null ? outputItem : "");
        envelope.addProperty("inputCount",          inputCount);
        envelope.addProperty("expectedOutputCount", expectedOutputCount);
        envelope.addProperty("fuelItem",            fuelItem != null ? fuelItem : "");
        envelope.addProperty("fuelCount",           fuelCount);
        envelope.addProperty("startGameTime",       startGameTime);
        envelope.addProperty("etaGameTime",         etaGameTime);
        envelope.addProperty("owningBotUuid",       owningBotUuid != null ? owningBotUuid : "");
        envelope.addProperty("degradeReason",       degradeReason != null ? degradeReason : "");
        return envelope;
    }

    /**
     * Deserializes a {@link DeferredJobRecord} from a {@link JsonElement}.
     *
     * <p>Missing fields default safely (null strings, 0 counts, {@link Status#PENDING}).
     * Returns {@code null} (with a WARN log) if the element is malformed or not a JSON object.
     * Mirrors {@code WaypointRecord.fromJson}.
     */
    public static DeferredJobRecord fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            JsonObject obj = element.getAsJsonObject();
            DeferredJobRecord r = new DeferredJobRecord();
            // Retain original envelope so unknown fields from a newer build round-trip on save.
            r.rawSource            = obj.deepCopy();
            r.schemaVersion        = obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : 0;
            r.id                   = obj.has("id") ? obj.get("id").getAsString() : null;
            r.kind                 = obj.has("kind") ? obj.get("kind").getAsString() : null;
            // Parse status enum tolerantly — unknown values fall back to PENDING.
            if (obj.has("status")) {
                try {
                    r.status = Status.valueOf(obj.get("status").getAsString());
                } catch (IllegalArgumentException e) {
                    r.status = Status.PENDING;
                }
            } else {
                r.status = Status.PENDING;
            }
            if (obj.has("pos") && obj.get("pos").isJsonArray()) {
                JsonArray arr = obj.get("pos").getAsJsonArray();
                if (arr.size() == 3) {
                    r.pos = new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
                }
            }
            r.dimension            = obj.has("dimension") ? obj.get("dimension").getAsString() : null;
            r.inputItem            = obj.has("inputItem") ? obj.get("inputItem").getAsString() : null;
            r.outputItem           = obj.has("outputItem") ? obj.get("outputItem").getAsString() : null;
            r.inputCount           = obj.has("inputCount") ? obj.get("inputCount").getAsInt() : 0;
            r.expectedOutputCount  = obj.has("expectedOutputCount") ? obj.get("expectedOutputCount").getAsInt() : 0;
            r.fuelItem             = obj.has("fuelItem") ? obj.get("fuelItem").getAsString() : null;
            r.fuelCount            = obj.has("fuelCount") ? obj.get("fuelCount").getAsInt() : 0;
            r.startGameTime        = obj.has("startGameTime") ? obj.get("startGameTime").getAsLong() : 0L;
            r.etaGameTime          = obj.has("etaGameTime") ? obj.get("etaGameTime").getAsLong() : 0L;
            r.owningBotUuid        = obj.has("owningBotUuid") ? obj.get("owningBotUuid").getAsString() : null;
            r.degradeReason        = obj.has("degradeReason") ? obj.get("degradeReason").getAsString() : "";
            return r;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("DeferredJobStore: failed to parse job record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Carries the original JSON envelope (unknown-field source) from {@code other} onto this
     * record. Used by the store when refreshing an existing record so unknown fields from a
     * newer build survive this build's save. No-op when {@code other} is null or has no source.
     */
    public void inheritUnknownFieldsFrom(DeferredJobRecord other) {
        if (other != null && other.rawSource != null) {
            this.rawSource = other.rawSource;
        }
    }

    @Override
    public String toString() {
        return "DeferredJobRecord{id='" + id + "', kind='" + kind + "', status=" + status
                + ", inputItem='" + inputItem + "', inputCount=" + inputCount + "}";
    }
}
