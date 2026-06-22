package com.player2.playerengine.tasks.deferred;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world deferred-job registry.
 *
 * <p>Owns {@code jobs.json} under
 * {@code <worldRoot>/player2npc/persistentdata/deferredjobs/} and provides the CRUD API
 * all deferred-smelting workstreams build against.
 *
 * <h3>Threading contract</h3>
 * All mutations ({@link #upsert}, {@link #delete}) MUST be called on the server thread.
 * A {@code volatile} immutable published snapshot is maintained for lock-free reads from
 * any thread.
 *
 * <h3>Persistence invariant</h3>
 * {@code jobs.json} is always the source of truth. Every mutation writes JSON first via
 * atomic tmp-rename, then updates the in-memory map — callers never need to re-persist
 * after a store mutation.
 *
 * <h3>Corrupt-file quarantine</h3>
 * If {@code jobs.json} cannot be parsed it is renamed to
 * {@code jobs.json.corrupt-<epoch>} and the store starts empty with a WARN log. A
 * one-time operator-visible note is surfaced on first command use.
 *
 * <h3>Forward compatibility</h3>
 * Records with unknown fields are preserved verbatim via {@link DeferredJobRecord#rawSource}.
 * Files with {@code schemaVersion > 1} are quarantined rather than silently truncated.
 *
 * <h3>Singleton pattern</h3>
 * The static {@link #INSTANCE} is set by {@link #loadForServer(MinecraftServer)} at
 * {@code SERVER_STARTING} and cleared by {@link #clear()} at {@code SERVER_STOPPING}.
 */
public final class DeferredJobStore {

    // -------------------------------------------------------------------------
    // Singleton holder
    // -------------------------------------------------------------------------

    /** The store for the currently loaded world. Null when no world is loaded. */
    private static volatile DeferredJobStore INSTANCE;

    /** Returns the store for the currently loaded world, or {@code null} when no world is loaded. */
    public static DeferredJobStore get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int SUPPORTED_SCHEMA_VERSION = DeferredJobRecord.JOB_SCHEMA_VERSION;
    private static final String JOBS_FILE_NAME         = "jobs.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The world-root path this store was loaded for (for path builders). */
    private final Path worldRoot;

    /**
     * Primary record store keyed by job id. Maintains insertion order.
     * Mutated only on the server thread.
     */
    private final Map<String, DeferredJobRecord> records = new LinkedHashMap<>();

    /**
     * Immutable published snapshot for lock-free reads.
     * Updated atomically via {@code volatile} on every mutation.
     */
    private volatile List<DeferredJobRecord> publishedSnapshot = List.of();

    /**
     * True if the store was started empty due to a corrupt-file quarantine.
     * Surfaced to the operator ONCE on first command use via {@link #consumeQuarantineNote()}.
     */
    private volatile boolean startedFromQuarantine = false;

    /**
     * Unknown top-level members of the loaded {@code jobs.json} root object (everything
     * except {@code schemaVersion} and {@code jobs}). Retained so a newer build's root
     * fields round-trip on persist. Null when the file was absent or freshly created.
     */
    private JsonObject rootExtras;

    // -------------------------------------------------------------------------
    // Load / clear lifecycle
    // -------------------------------------------------------------------------

    private DeferredJobStore(Path worldRoot) {
        this.worldRoot = worldRoot;
    }

    /**
     * Loads the store for the given server's world. Sets {@link #INSTANCE}.
     * Called from {@code SERVER_STARTING}; never throws — failures quarantine + start empty.
     */
    public static DeferredJobStore loadForServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        DeferredJobStore store = new DeferredJobStore(worldRoot);
        store.load();
        INSTANCE = store;
        return store;
    }

    /**
     * Clears the in-memory state and nulls the singleton. Called from {@code SERVER_STOPPING}.
     */
    public void clear() {
        records.clear();
        publishedSnapshot = List.of();
        INSTANCE = null;
        PlayerEngine.LOGGER.info("DeferredJobStore: store cleared.");
    }

    // -------------------------------------------------------------------------
    // CRUD API
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces the record with the given id.
     *
     * <p>Persists {@code jobs.json} and publishes the new snapshot.
     */
    public void upsert(DeferredJobRecord record) {
        records.put(record.id, record);
        publishSnapshot();
        persist();
        PlayerEngine.LOGGER.info("DeferredJobStore: upserted job id={}", record.id);
    }

    /**
     * Deletes the record with the given id (if present).
     *
     * <p>Persists and publishes if a record was removed.
     *
     * @return true if a record was found and deleted
     */
    public boolean delete(String id) {
        DeferredJobRecord removed = records.remove(id);
        if (removed != null) {
            publishSnapshot();
            persist();
            PlayerEngine.LOGGER.info("DeferredJobStore: deleted job id={}", id);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Query API
    // -------------------------------------------------------------------------

    /**
     * Returns the record for the given id, or {@code null} if not found.
     * Called on the server thread.
     */
    public DeferredJobRecord get(String id) {
        return records.get(id);
    }

    /**
     * Returns all records in insertion order. Immutable view; safe to iterate.
     * Called on the server thread only.
     */
    public List<DeferredJobRecord> all() {
        return List.copyOf(records.values());
    }

    /**
     * Returns the immutable published snapshot for lock-free reads.
     * May be called from any thread.
     */
    public List<DeferredJobRecord> publishedRecords() {
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
     * One-time quarantine-note check for command guard chains: returns true exactly once after
     * a corrupt-file quarantine, then clears the flag so the operator note is surfaced on the
     * FIRST command use only and never repeated.
     */
    public boolean consumeQuarantineNote() {
        if (startedFromQuarantine) {
            startedFromQuarantine = false;
            return true;
        }
        return false;
    }

    /**
     * Returns the world root this store was loaded for.
     */
    public Path worldRoot() {
        return worldRoot;
    }

    // -------------------------------------------------------------------------
    // Internal: load and persist
    // -------------------------------------------------------------------------

    private void load() {
        Path file = Player2NpcPersistencePaths.deferredJobsFile(worldRoot);
        if (!Files.exists(file)) {
            PlayerEngine.LOGGER.info("DeferredJobStore: no jobs.json found — starting empty.");
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
            if (!root.has("jobs") || !root.get("jobs").isJsonArray()) {
                quarantine(file, "missing or invalid jobs array");
                return;
            }
            JsonArray arr = root.get("jobs").getAsJsonArray();
            // Retain unknown top-level root members so they round-trip on persist.
            JsonObject extras = root.deepCopy();
            extras.remove("schemaVersion");
            extras.remove("jobs");
            rootExtras = extras.size() > 0 ? extras : null;
            int loaded = 0, skipped = 0;
            for (JsonElement elem : arr) {
                DeferredJobRecord r = DeferredJobRecord.fromJson(elem);
                if (r == null || r.id == null) { skipped++; continue; }
                records.put(r.id, r);
                loaded++;
            }
            PlayerEngine.LOGGER.info("DeferredJobStore: loaded {} job(s), {} skipped.", loaded, skipped);
            publishSnapshot();
        } catch (Exception e) {
            quarantine(file, e.getMessage());
        }
    }

    /**
     * Quarantines the given file by renaming it to {@code jobs.json.corrupt-<epoch>},
     * logs a WARN, marks the store as started-from-quarantine, and starts empty.
     */
    private void quarantine(Path file, String reason) {
        String quarantineName = JOBS_FILE_NAME + ".corrupt-" + System.currentTimeMillis();
        try {
            Path quarantineTarget = file.getParent().resolve(quarantineName);
            Files.move(file, quarantineTarget, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.warn(
                "DeferredJobStore: quarantined corrupt jobs.json as {} (reason: {}); starting empty.",
                quarantineName, reason);
        } catch (IOException ex) {
            PlayerEngine.LOGGER.warn(
                "DeferredJobStore: could not quarantine jobs.json (reason: {}; rename error: {}); starting empty.",
                reason, ex.getMessage());
        }
        records.clear();
        publishedSnapshot = List.of();
        rootExtras = null;
        startedFromQuarantine = true;
    }

    /**
     * Writes the current records to {@code jobs.json} via atomic tmp-rename. The root object
     * starts from any retained unknown top-level members so a newer build's root fields survive
     * a save by this build.
     */
    private void persist() {
        Path file = Player2NpcPersistencePaths.deferredJobsFile(worldRoot);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = (rootExtras != null) ? rootExtras.deepCopy() : new JsonObject();
            root.addProperty("schemaVersion", SUPPORTED_SCHEMA_VERSION);
            JsonArray arr = new JsonArray();
            for (DeferredJobRecord r : records.values()) arr.add(r.toJson());
            root.add("jobs", arr);
            String json = GSON.toJson(root);
            // Write to tmp, then atomic-move
            Path tmp = file.resolveSibling(JOBS_FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            atomicReplace(tmp, file);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("DeferredJobStore: failed to persist jobs.json: {}", e.getMessage());
        }
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.debug("DeferredJobStore: atomic move unavailable for {}", target.getFileName());
        }
    }

    /** Publishes an immutable snapshot of the current records. */
    private void publishSnapshot() {
        publishedSnapshot = List.copyOf(records.values());
    }
}
