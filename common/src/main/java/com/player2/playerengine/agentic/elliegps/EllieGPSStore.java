package com.player2.playerengine.agentic.elliegps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-world EllieGPS waypoint store.
 *
 * <p>Owns {@code waypoints.json} under
 * {@code <worldRoot>/player2npc/persistentdata/elliegps/} and provides the CRUD API all other
 * EllieGPS workstreams build against.
 *
 * <h3>Threading contract</h3>
 * All mutations ({@link #upsert}, {@link #delete}, {@link #markStale}) MUST be called on the
 * server thread. A {@code volatile} immutable published snapshot is maintained for the
 * counting service to read lock-free from any thread.
 *
 * <h3>Persistence invariant</h3>
 * {@code waypoints.json} is always the source of truth; the index (bin + version.txt) is
 * always rebuildable from it. Every mutation writes JSON first via atomic tmp-rename, then
 * rebuilds the in-memory {@link EllieGPSWaypointIndex} (and its persisted bin + version
 * token) internally — callers never need to reindex after a store mutation, so the index can
 * never silently drift from the store (Decision 3: corpus is tiny, full rebuild per mutation
 * is the pinned design).
 *
 * <h3>Corrupt-file quarantine (Decision 14)</h3>
 * If {@code waypoints.json} cannot be parsed, it is renamed to
 * {@code waypoints.json.corrupt-<epoch>} and the store starts empty with a WARN log. A
 * one-time operator-visible note is surfaced on first command use.
 *
 * <h3>Forward compatibility</h3>
 * Records with unknown {@code type} values and unknown fields are preserved verbatim.
 * Files with {@code schemaVersion > 1} are quarantined rather than silently truncated.
 *
 * <h3>Singleton pattern</h3>
 * The static {@link #INSTANCE} is set by {@link #loadForServer(MinecraftServer)} at
 * {@code SERVER_STARTING} and cleared by {@link #clear()} at {@code SERVER_STOPPING}.
 */
public final class EllieGPSStore {

    // -------------------------------------------------------------------------
    // Singleton holder
    // -------------------------------------------------------------------------

    /** The store for the currently loaded world. Null when no world is loaded. */
    private static volatile EllieGPSStore INSTANCE;

    /** Returns the store for the currently loaded world, or {@code null} when no world is loaded. */
    public static EllieGPSStore get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int SUPPORTED_SCHEMA_VERSION = WaypointRecord.WAYPOINT_SCHEMA_VERSION;
    private static final String WAYPOINTS_FILE_NAME    = "waypoints.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The world-root path this store was loaded for (for path builders). */
    private final Path worldRoot;

    /**
     * Primary record store keyed by waypoint id. Maintains insertion order.
     * Mutated only on the server thread.
     */
    private final Map<String, WaypointRecord> records = new LinkedHashMap<>();

    /**
     * Immutable published snapshot for lock-free reads by the counting service.
     * Updated atomically via {@code volatile} on every mutation.
     */
    private volatile List<WaypointRecord> publishedSnapshot = List.of();

    /**
     * True if the store was started empty due to a corrupt-file quarantine.
     * Surfaced to the operator ONCE on first command use via {@link #consumeQuarantineNote()}.
     */
    private volatile boolean startedFromQuarantine = false;

    /**
     * Unknown top-level members of the loaded {@code waypoints.json} root object (everything
     * except {@code schemaVersion} and {@code waypoints}). Retained so a newer build's root
     * fields round-trip on persist (Decision 2 forward compatibility). Null when the file was
     * absent or freshly created.
     */
    private JsonObject rootExtras;

    // -------------------------------------------------------------------------
    // Load / clear lifecycle
    // -------------------------------------------------------------------------

    private EllieGPSStore(Path worldRoot) {
        this.worldRoot = worldRoot;
    }

    /**
     * Loads the store for the given server's world. Sets {@link #INSTANCE}.
     * Called from {@code SERVER_STARTING}; never throws — failures quarantine + start empty.
     */
    public static EllieGPSStore loadForServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        EllieGPSStore store = new EllieGPSStore(worldRoot);
        store.load();
        INSTANCE = store;
        return store;
    }

    /**
     * Clears the in-memory state and nulls the singleton. Called from {@code SERVER_STOPPING}.
     * After this the counting service degrades to 0.
     */
    public void clear() {
        records.clear();
        publishedSnapshot = List.of();
        INSTANCE = null;
        PlayerEngine.LOGGER.info("EllieGPS: store cleared.");
    }

    // -------------------------------------------------------------------------
    // CRUD API
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces the record with the given id. Preserves {@code createdGameTime} on
     * refresh (the caller should set it from the existing record when refreshing).
     *
     * <p>Persists {@code waypoints.json}, publishes the new snapshot, and rebuilds the index.
     */
    public void upsert(WaypointRecord record) {
        records.put(record.id, record);
        publishSnapshot();
        persist();
        reindex();
        PlayerEngine.LOGGER.info("EllieGPS: upserted waypoint id={}", record.id);
    }

    /**
     * Deletes the record with the given id (if present). Works without resolving the block —
     * stale records with the block gone are always deletable.
     *
     * <p>Persists, publishes, and rebuilds the index if a record was removed.
     *
     * @return true if a record was found and deleted
     */
    public boolean delete(String id) {
        WaypointRecord removed = records.remove(id);
        if (removed != null) {
            publishSnapshot();
            persist();
            reindex();
            PlayerEngine.LOGGER.info("EllieGPS: deleted waypoint id={}", id);
            return true;
        }
        return false;
    }

    /**
     * Marks the record with the given id as stale or un-stale. Persists, publishes, and
     * rebuilds the index.
     *
     * @return true if the record was found and updated
     */
    public boolean markStale(String id, boolean stale) {
        WaypointRecord r = records.get(id);
        if (r == null) return false;
        r.stale = stale;
        publishSnapshot();
        persist();
        reindex();
        PlayerEngine.LOGGER.info("EllieGPS: marked waypoint id={} stale={}", id, stale);
        return true;
    }

    /**
     * Rebuilds the in-memory {@link EllieGPSWaypointIndex} (and its persisted bin + version
     * token) from this store's current records. Called internally after every mutation so the
     * index can never silently drift from the store. {@code rebuildFrom} also installs the
     * rebuilt index as the static current instance and never throws.
     */
    private void reindex() {
        EllieGPSWaypointIndex.rebuildFrom(this);
    }

    // -------------------------------------------------------------------------
    // Query API
    // -------------------------------------------------------------------------

    /**
     * Finds the record whose stored canonical or secondary position equals {@code pos} in the
     * given dimension. Returns {@code null} if not found.
     *
     * <p>Called on the server thread by commands and the auto-hook.
     */
    public WaypointRecord byPosition(String dimensionId, BlockPos pos) {
        for (WaypointRecord r : records.values()) {
            if (!dimensionId.equals(r.dimension)) continue;
            BlockPos canon = r.canonicalBlockPos();
            if (canon != null && canon.equals(pos)) return r;
            BlockPos secondary = r.secondaryBlockPos();
            if (secondary != null && secondary.equals(pos)) return r;
        }
        return null;
    }

    /**
     * Returns all records in insertion order. Immutable view; safe to iterate.
     * Called on the server thread only.
     */
    public List<WaypointRecord> all() {
        return List.copyOf(records.values());
    }

    /**
     * Returns the immutable published snapshot for lock-free reads by the counting service.
     * May be called from any thread.
     */
    public List<WaypointRecord> publishedRecords() {
        return publishedSnapshot;
    }

    /**
     * Returns true if this store was started empty because its source file was quarantined.
     * Prefer {@link #consumeQuarantineNote()} on command paths — this accessor does not clear
     * the flag.
     */
    public boolean startedFromQuarantine() {
        return startedFromQuarantine;
    }

    /**
     * One-time quarantine-note check for command guard chains (Decision 14): returns true
     * exactly once after a corrupt-file quarantine, then clears the flag so the operator note
     * is surfaced on the FIRST EllieGPS command only — whichever of the five commands runs
     * first — and never repeated.
     */
    public boolean consumeQuarantineNote() {
        if (startedFromQuarantine) {
            startedFromQuarantine = false;
            return true;
        }
        return false;
    }

    /**
     * Returns the world root this store was loaded for (used by {@link EllieGPSWaypointIndex}
     * to locate the index directory).
     */
    public Path worldRoot() {
        return worldRoot;
    }

    // -------------------------------------------------------------------------
    // Version token (Decision 3 — pinned format)
    // -------------------------------------------------------------------------

    /**
     * Computes the deterministic version token for the current inventory-type records.
     *
     * <p>Format: {@code "1:" + SHA-256} over the UTF-8 bytes of one line per inventory-type
     * record, sorted ascending by id, joined with {@code \n}. Each line is:
     * {@code id + "|" + updatedGameTime + "|" + stale + "|" + description + "|" + String.join(",", keywords)}.
     */
    public String versionToken() {
        List<WaypointRecord> inv = records.values().stream()
                .filter(r -> WaypointTypes.INVENTORY.equals(r.type))
                .sorted((a, b) -> a.id.compareTo(b.id))
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        for (WaypointRecord r : inv) {
            String kwLine = r.keywords != null ? String.join(",", r.keywords) : "";
            sb.append(r.id).append('|')
              .append(r.updatedGameTime).append('|')
              .append(r.stale).append('|')
              .append(r.description != null ? r.description : "").append('|')
              .append(kwLine).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return "1:" + hex;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS: SHA-256 unavailable for version token: {}", e.getMessage());
            return "1:unknown";
        }
    }

    // -------------------------------------------------------------------------
    // Internal: load and persist
    // -------------------------------------------------------------------------

    private void load() {
        Path file = Player2NpcPersistencePaths.ellieGpsWaypointsFile(worldRoot);
        if (!Files.exists(file)) {
            PlayerEngine.LOGGER.info("EllieGPS: no waypoints.json found — starting empty.");
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                quarantine(file, "root is null");
                return;
            }
            int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
            if (version > SUPPORTED_SCHEMA_VERSION) {
                quarantine(file, "schemaVersion " + version + " > supported " + SUPPORTED_SCHEMA_VERSION);
                return;
            }
            if (!root.has("waypoints") || !root.get("waypoints").isJsonArray()) {
                quarantine(file, "missing or invalid waypoints array");
                return;
            }
            JsonArray arr = root.get("waypoints").getAsJsonArray();
            // Retain unknown top-level root members so they round-trip on persist (Decision 2).
            JsonObject extras = root.deepCopy();
            extras.remove("schemaVersion");
            extras.remove("waypoints");
            rootExtras = extras.size() > 0 ? extras : null;
            int loaded = 0, skipped = 0;
            for (JsonElement elem : arr) {
                WaypointRecord r = WaypointRecord.fromJson(elem);
                if (r == null || r.id == null) { skipped++; continue; }
                records.put(r.id, r);
                loaded++;
            }
            PlayerEngine.LOGGER.info("EllieGPS: loaded {} waypoint(s), {} skipped.", loaded, skipped);
            publishSnapshot();
        } catch (Exception e) {
            quarantine(file, e.getMessage());
        }
    }

    /**
     * Quarantines the given file by renaming it to {@code waypoints.json.corrupt-<epoch>},
     * logs a WARN, marks the store as started-from-quarantine, and starts empty.
     */
    private void quarantine(Path file, String reason) {
        String quarantineName = WAYPOINTS_FILE_NAME + ".corrupt-" + System.currentTimeMillis();
        try {
            Path quarantineTarget = file.getParent().resolve(quarantineName);
            Files.move(file, quarantineTarget, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.warn(
                "EllieGPS: quarantined corrupt waypoints.json as {} (reason: {}); starting empty.",
                quarantineName, reason);
        } catch (IOException ex) {
            PlayerEngine.LOGGER.warn(
                "EllieGPS: could not quarantine waypoints.json (reason: {}; rename error: {}); starting empty.",
                reason, ex.getMessage());
        }
        records.clear();
        publishedSnapshot = List.of();
        rootExtras = null;
        startedFromQuarantine = true;
    }

    /**
     * Writes the current records to {@code waypoints.json} via atomic tmp-rename. The root
     * object starts from any retained unknown top-level members ({@link #rootExtras}) so a
     * newer build's root fields survive a save by this build (Decision 2).
     */
    private void persist() {
        Path file = Player2NpcPersistencePaths.ellieGpsWaypointsFile(worldRoot);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = (rootExtras != null) ? rootExtras.deepCopy() : new JsonObject();
            root.addProperty("schemaVersion", SUPPORTED_SCHEMA_VERSION);
            JsonArray arr = new JsonArray();
            for (WaypointRecord r : records.values()) arr.add(r.toJson());
            root.add("waypoints", arr);
            String json = GSON.toJson(root);
            // Write to tmp, then atomic-move
            Path tmp = file.resolveSibling(WAYPOINTS_FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            atomicReplace(tmp, file);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS: failed to persist waypoints.json: {}", e.getMessage());
        }
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.debug("EllieGPS: atomic move unavailable for {}", target.getFileName());
        }
    }

    /** Publishes an immutable snapshot of the current records for the counting service. */
    private void publishSnapshot() {
        publishedSnapshot = List.copyOf(records.values());
    }
}
