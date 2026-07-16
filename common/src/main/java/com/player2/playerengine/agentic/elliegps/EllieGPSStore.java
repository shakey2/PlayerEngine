package com.player2.playerengine.agentic.elliegps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import com.player2.playerengine.retrieval.RetrievalHit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-world authoritative EllieGPS waypoint store.
 *
 * <p>All mutations are synchronous server-thread operations. A complete candidate JSON document is
 * atomically promoted before candidate memory becomes visible. The derived index is synchronized
 * afterwards and may fail independently, producing a committed degradation rather than a false JSON
 * failure. Query methods return defensive record copies; authoritative records never escape.
 */
public final class EllieGPSStore {

    private static volatile EllieGPSStore INSTANCE;

    private static final int SUPPORTED_SCHEMA_VERSION = WaypointRecord.WAYPOINT_SCHEMA_VERSION;
    private static final String WAYPOINTS_FILE_NAME = "waypoints.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final WaypointPersistenceBackend PRODUCTION_PERSISTENCE_BACKEND =
            EllieGPSStore::persistAtomically;

    private final Path worldRoot;
    private final WaypointPersistenceBackend persistenceBackend;
    private final WaypointIndexBackend indexBackend;

    /** Server-thread-owned authoritative map. Its mutable records never leave this class. */
    private Map<String, WaypointRecord> records = new LinkedHashMap<>();

    /** Immutable private snapshot; accessors deep-copy its elements before returning. */
    private volatile List<WaypointRecord> publishedSnapshot = List.of();
    /** Constant-time type/position membership, including records loaded under legacy IDs. */
    private volatile java.util.Set<PositionTypeKey> positionTypeIndex = java.util.Set.of();
    /** O(1) invalidation token for hot immutable-snapshot consumers. */
    private volatile long mutationGeneration;

    private volatile boolean startedFromQuarantine;
    private JsonObject rootExtras;

    private EllieGPSStore(Path worldRoot) {
        this(worldRoot, PRODUCTION_PERSISTENCE_BACKEND, EllieGPSWaypointIndex.productionBackend());
    }

    /** Package-private injection constructor used by deterministic store tests. */
    EllieGPSStore(
            Path worldRoot,
            WaypointPersistenceBackend persistenceBackend,
            WaypointIndexBackend indexBackend) {
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot");
        this.persistenceBackend = Objects.requireNonNull(persistenceBackend, "persistenceBackend");
        this.indexBackend = Objects.requireNonNull(indexBackend, "indexBackend");
    }

    /** Returns the active world store, or {@code null} outside a loaded world. */
    public static EllieGPSStore get() {
        return INSTANCE;
    }

    /** Existing lifecycle API: load the current world's authoritative JSON store. */
    public static EllieGPSStore loadForServer(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        EllieGPSStore store = new EllieGPSStore(worldRoot);
        store.load();
        INSTANCE = store;
        return store;
    }

    /** Package-private singleton seam for deterministic FarmWaypointService tests. */
    static AutoCloseable overrideForTest(EllieGPSStore replacement) {
        Objects.requireNonNull(replacement, "replacement");
        EllieGPSStore previous = INSTANCE;
        INSTANCE = replacement;
        return () -> {
            if (INSTANCE != replacement) {
                throw new IllegalStateException("EllieGPSStore test overrides must close in LIFO order");
            }
            INSTANCE = previous;
        };
    }

    /** Existing lifecycle API: clear memory and detach this active singleton. */
    public void clear() {
        records = new LinkedHashMap<>();
        publishedSnapshot = List.of();
        positionTypeIndex = java.util.Set.of();
        mutationGeneration++;
        if (INSTANCE == this) {
            INSTANCE = null;
        }
        PlayerEngine.LOGGER.info("EllieGPS: store cleared.");
    }

    /** Checked insert/refresh at the candidate stable ID. */
    public WaypointMutationResult upsert(WaypointRecord candidate) {
        WaypointRecord safeCandidate = validatedCopy(candidate);
        WaypointRecord previous = records.get(safeCandidate.id);
        if (previous != null && !Objects.equals(previous.type, safeCandidate.type)) {
            return result(WaypointMutationStatus.REJECTED_TYPE_CONFLICT, previous, previous);
        }
        if (previous != null && previous.semanticallyEquals(safeCandidate)) {
            WaypointIndexUpdateStatus index = ensureIndexSynchronized();
            return result(
                    index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                            ? WaypointMutationStatus.NO_CHANGE
                            : WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED,
                    previous,
                    previous);
        }

        Map<String, WaypointRecord> next = new LinkedHashMap<>(records);
        next.put(safeCandidate.id, safeCandidate);
        if (!commitAndPublish(next)) {
            return result(
                    WaypointMutationStatus.FAILED_JSON_COMMIT,
                    previous,
                    previous);
        }
        WaypointIndexUpdateStatus index = synchronizeIndex();
        WaypointRecord current = records.get(safeCandidate.id);
        WaypointMutationStatus status = index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                ? WaypointMutationStatus.COMMITTED
                : WaypointMutationStatus.COMMITTED_INDEX_DEGRADED;
        PlayerEngine.LOGGER.info("EllieGPS: upserted waypoint id={} status={}", safeCandidate.id, status);
        return result(status, previous, current);
    }

    /**
     * Atomically replaces {@code oldId} with {@code candidate}. This is the only legal
     * re-canonicalization operation.
     */
    public WaypointMutationResult replace(String oldId, WaypointRecord candidate) {
        if (oldId == null || oldId.isBlank()) {
            throw new IllegalArgumentException("oldId is required");
        }
        WaypointRecord safeCandidate = validatedCopy(candidate);
        WaypointRecord previous = records.get(oldId);
        WaypointRecord target = records.get(safeCandidate.id);

        if (previous == null) {
            WaypointIndexUpdateStatus index = ensureIndexSynchronized();
            return result(
                    index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                            ? WaypointMutationStatus.NOT_FOUND
                            : WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                    null,
                    target);
        }

        if (oldId.equals(safeCandidate.id)) {
            return upsert(safeCandidate);
        }

        if (!Objects.equals(previous.type, safeCandidate.type)) {
            return result(WaypointMutationStatus.REJECTED_TYPE_CONFLICT, previous, target);
        }
        if (target != null) {
            WaypointMutationStatus conflict = Objects.equals(target.type, safeCandidate.type)
                    ? WaypointMutationStatus.REJECTED_TARGET_CONFLICT
                    : WaypointMutationStatus.REJECTED_TYPE_CONFLICT;
            return result(conflict, previous, target);
        }

        Map<String, WaypointRecord> next = new LinkedHashMap<>(records);
        next.remove(oldId);
        next.put(safeCandidate.id, safeCandidate);
        if (!commitAndPublish(next)) {
            return result(WaypointMutationStatus.FAILED_JSON_COMMIT, previous, null);
        }
        WaypointIndexUpdateStatus index = synchronizeIndex();
        WaypointMutationStatus status = index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                ? WaypointMutationStatus.COMMITTED
                : WaypointMutationStatus.COMMITTED_INDEX_DEGRADED;
        PlayerEngine.LOGGER.info(
                "EllieGPS: replaced waypoint oldId={} newId={} status={}",
                oldId,
                safeCandidate.id,
                status);
        return result(status, previous, records.get(safeCandidate.id));
    }

    /** Checked explicit deletion. */
    public WaypointMutationResult delete(String id) {
        WaypointRecord previous = id == null ? null : records.get(id);
        if (previous == null) {
            WaypointIndexUpdateStatus index = ensureIndexSynchronized();
            return result(
                    index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                            ? WaypointMutationStatus.NOT_FOUND
                            : WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                    null,
                    null);
        }

        Map<String, WaypointRecord> next = new LinkedHashMap<>(records);
        next.remove(id);
        if (!commitAndPublish(next)) {
            return result(WaypointMutationStatus.FAILED_JSON_COMMIT, previous, previous);
        }
        WaypointIndexUpdateStatus index = synchronizeIndex();
        WaypointMutationStatus status = index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                ? WaypointMutationStatus.COMMITTED
                : WaypointMutationStatus.COMMITTED_INDEX_DEGRADED;
        PlayerEngine.LOGGER.info("EllieGPS: deleted waypoint id={} status={}", id, status);
        return result(status, previous, null);
    }

    /** Checked stale-mark operation. Already-stale is a semantic no-op with index validation. */
    public WaypointMutationResult markStale(String id) {
        WaypointRecord previous = id == null ? null : records.get(id);
        if (previous == null) {
            WaypointIndexUpdateStatus index = ensureIndexSynchronized();
            return result(
                    index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                            ? WaypointMutationStatus.NOT_FOUND
                            : WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                    null,
                    null);
        }

        WaypointRecord stale = previous.copy();
        stale.stale = true;
        if (previous.semanticallyEquals(stale)) {
            WaypointIndexUpdateStatus index = ensureIndexSynchronized();
            return result(
                    index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                            ? WaypointMutationStatus.NO_CHANGE
                            : WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED,
                    previous,
                    previous);
        }

        Map<String, WaypointRecord> next = new LinkedHashMap<>(records);
        next.put(id, stale);
        if (!commitAndPublish(next)) {
            return result(WaypointMutationStatus.FAILED_JSON_COMMIT, previous, previous);
        }
        WaypointIndexUpdateStatus index = synchronizeIndex();
        WaypointMutationStatus status = index == WaypointIndexUpdateStatus.INDEX_COMMITTED
                ? WaypointMutationStatus.COMMITTED
                : WaypointMutationStatus.COMMITTED_INDEX_DEGRADED;
        PlayerEngine.LOGGER.info("EllieGPS: marked waypoint id={} stale status={}", id, status);
        return result(status, previous, records.get(id));
    }

    /** Position lookup in current dimension; returns a defensive copy. */
    public WaypointRecord byPosition(String dimensionId, BlockPos pos) {
        for (WaypointRecord record : records.values()) {
            if (!Objects.equals(dimensionId, record.dimension)) {
                continue;
            }
            BlockPos canonical = record.canonicalBlockPos();
            if (canonical != null && canonical.equals(pos)) {
                return record.copy();
            }
            BlockPos secondary = record.secondaryBlockPos();
            if (secondary != null && secondary.equals(pos)) {
                return record.copy();
            }
        }
        return null;
    }

    /** Stable-ID lookup in the authoritative server-thread store; returns a defensive copy. */
    public WaypointRecord byId(String id) {
        WaypointRecord record = id == null ? null : records.get(id);
        return record == null ? null : record.copy();
    }

    /** Constant-time position/type check for hot server-thread safety gates. */
    public boolean hasTypeAtPosition(String type, String dimensionId, BlockPos pos) {
        if (type == null || dimensionId == null || pos == null) {
            return false;
        }
        return positionTypeIndex.contains(new PositionTypeKey(
                type, dimensionId, pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Monotonic in-process generation for O(1) cache invalidation after authoritative mutation. */
    public long mutationGeneration() {
        return mutationGeneration;
    }

    /** O(1) authoritative size gate for callers that must refuse before defensive copying. */
    public int recordCount() {
        return records.size();
    }

    /**
     * Availability/completeness-bearing authoritative snapshot. The size gate runs before any
     * defensive record copy, so hot fail-closed consumers cannot allocate an oversized snapshot.
     */
    public BoundedSnapshot boundedSnapshot(int maxRecords) {
        return boundedSnapshot(records, maxRecords);
    }

    static BoundedSnapshot boundedSnapshot(
            Map<String, WaypointRecord> source,
            int maxRecords) {
        if (maxRecords < 0) {
            throw new IllegalArgumentException("maxRecords cannot be negative");
        }
        if (source == null) {
            return new BoundedSnapshot(false, false, List.of());
        }
        if (source.size() > maxRecords) {
            return new BoundedSnapshot(true, false, List.of());
        }
        try {
            return new BoundedSnapshot(true, true, copyRecords(source.values()));
        } catch (RuntimeException copyFailure) {
            return new BoundedSnapshot(false, false, List.of());
        }
    }

    public record BoundedSnapshot(
            boolean available,
            boolean complete,
            List<WaypointRecord> records) {
        public BoundedSnapshot {
            records = records == null ? List.of() : List.copyOf(records);
            if ((!available || !complete) && !records.isEmpty()) {
                throw new IllegalArgumentException(
                        "incomplete bounded snapshots cannot expose records");
            }
        }
    }

    /** Server-thread authoritative snapshot as defensive record copies. */
    public List<WaypointRecord> all() {
        return copyRecords(records.values());
    }

    /** Lock-free published snapshot as fresh defensive record copies. */
    public List<WaypointRecord> publishedRecords() {
        return copyRecords(publishedSnapshot);
    }

    public boolean startedFromQuarantine() {
        return startedFromQuarantine;
    }

    public boolean consumeQuarantineNote() {
        if (startedFromQuarantine) {
            startedFromQuarantine = false;
            return true;
        }
        return false;
    }

    public Path worldRoot() {
        return worldRoot;
    }

    /**
     * Token prefix 2 covers every supported indexable record, including typed farm payload data.
     * Full record JSON is used so any document-affecting field necessarily invalidates the index.
     */
    public String versionToken() {
        ArrayList<WaypointRecord> indexable = new ArrayList<>();
        for (WaypointRecord record : records.values()) {
            if (isIndexable(record)) {
                indexable.add(record);
            }
        }
        indexable.sort((left, right) -> left.id.compareTo(right.id));
        StringBuilder material = new StringBuilder();
        for (WaypointRecord record : indexable) {
            material.append(record.toJson()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return "2:" + hex;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS: SHA-256 unavailable for version token: {}", e.getMessage());
            return "2:unknown";
        }
    }

    /** Frozen package-private search seam. */
    boolean isIndexHealthy() {
        try {
            return indexBackend.isHealthyFor(versionToken());
        } catch (RuntimeException e) {
            PlayerEngine.LOGGER.warn("EllieGPS index health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Frozen package-private search seam. */
    WaypointIndexUpdateStatus synchronizeIndex() {
        try {
            WaypointIndexUpdateStatus status = indexBackend.synchronize(this);
            return status == null ? WaypointIndexUpdateStatus.INDEX_FAILED : status;
        } catch (RuntimeException e) {
            PlayerEngine.LOGGER.warn("EllieGPS index synchronization failed: {}", e.getMessage());
            return WaypointIndexUpdateStatus.INDEX_FAILED;
        }
    }

    /** Frozen package-private search seam. */
    List<RetrievalHit> queryIndex(String query, int limit) throws Exception {
        return indexBackend.query(query, limit);
    }

    private WaypointIndexUpdateStatus ensureIndexSynchronized() {
        return isIndexHealthy()
                ? WaypointIndexUpdateStatus.INDEX_COMMITTED
                : synchronizeIndex();
    }

    private boolean commitAndPublish(Map<String, WaypointRecord> next) {
        final List<WaypointRecord> nextSnapshot;
        final java.util.Set<PositionTypeKey> nextPositionTypeIndex;
        final String json;
        try {
            nextSnapshot = copyRecords(next.values());
            nextPositionTypeIndex = buildPositionTypeIndex(next.values());
            json = serialize(next);
        } catch (RuntimeException e) {
            PlayerEngine.LOGGER.warn("EllieGPS: candidate waypoint set was invalid: {}", e.getMessage());
            return false;
        }

        Path file = Player2NpcPersistencePaths.ellieGpsWaypointsFile(worldRoot);
        try {
            persistenceBackend.persist(file, json);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS: failed to commit waypoints.json: {}", e.getMessage());
            return false;
        }

        records = next;
        publishedSnapshot = nextSnapshot;
        positionTypeIndex = nextPositionTypeIndex;
        mutationGeneration++;
        return true;
    }

    private String serialize(Map<String, WaypointRecord> source) {
        JsonObject root = rootExtras == null ? new JsonObject() : rootExtras.deepCopy();
        root.addProperty("schemaVersion", SUPPORTED_SCHEMA_VERSION);
        JsonArray array = new JsonArray();
        for (WaypointRecord record : source.values()) {
            array.add(record.toJson());
        }
        root.add("waypoints", array);
        return GSON.toJson(root);
    }

    private static WaypointRecord validatedCopy(WaypointRecord candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("waypoint candidate is required");
        }
        WaypointRecord copy = candidate.copy();
        if (copy.schemaVersion != WaypointRecord.WAYPOINT_SCHEMA_VERSION
                || copy.id == null || copy.id.isBlank()
                || copy.type == null || copy.type.isBlank()
                || copy.dimension == null || copy.dimension.isBlank()
                || copy.pos == null || copy.pos.length != 3) {
            throw new IllegalArgumentException("waypoint candidate has an invalid schema-v1 envelope");
        }
        BlockPos canonical = copy.canonicalBlockPos();
        if (canonical == null || !copy.id.equals(WaypointRecord.idFor(copy.dimension, canonical))) {
            throw new IllegalArgumentException("waypoint candidate id does not match dimension and position");
        }
        if (WaypointTypes.FARM.equals(copy.type)) {
            FarmWaypointData farm = copy.farmData();
            if (farm == null || !farm.isSupportedVersion()) {
                throw new IllegalArgumentException("cannot mutate an unsupported farm payload");
            }
        }
        return copy;
    }

    private static boolean isIndexable(WaypointRecord record) {
        if (WaypointTypes.INVENTORY.equals(record.type)) {
            return record.inventoryData() != null;
        }
        if (WaypointTypes.FARM.equals(record.type)) {
            FarmWaypointData farm = record.farmData();
            return farm != null && farm.isSupportedVersion();
        }
        return false;
    }

    private static WaypointMutationResult result(
            WaypointMutationStatus status,
            WaypointRecord previous,
            WaypointRecord current) {
        return new WaypointMutationResult(
                status,
                previous == null ? null : previous.copy(),
                current == null ? null : current.copy());
    }

    private static List<WaypointRecord> copyRecords(Iterable<WaypointRecord> source) {
        ArrayList<WaypointRecord> copies = new ArrayList<>();
        for (WaypointRecord record : source) {
            copies.add(record.copy());
        }
        return List.copyOf(copies);
    }

    private void load() {
        Path file = Player2NpcPersistencePaths.ellieGpsWaypointsFile(worldRoot);
        if (!Files.exists(file)) {
            PlayerEngine.LOGGER.info("EllieGPS: no waypoints.json found; starting empty.");
            publishCurrentSnapshot();
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

            JsonObject extras = root.deepCopy();
            extras.remove("schemaVersion");
            extras.remove("waypoints");
            rootExtras = extras.size() == 0 ? null : extras;

            LinkedHashMap<String, WaypointRecord> loadedRecords = new LinkedHashMap<>();
            int loaded = 0;
            int skipped = 0;
            for (JsonElement element : root.getAsJsonArray("waypoints")) {
                WaypointRecord record = WaypointRecord.fromJson(element);
                if (record == null || record.id == null) {
                    skipped++;
                    continue;
                }
                loadedRecords.put(record.id, record);
                loaded++;
            }
            records = loadedRecords;
            publishCurrentSnapshot();
            PlayerEngine.LOGGER.info("EllieGPS: loaded {} waypoint(s), {} skipped.", loaded, skipped);
        } catch (Exception e) {
            quarantine(file, e.getMessage());
        }
    }

    private void quarantine(Path file, String reason) {
        String quarantineName = WAYPOINTS_FILE_NAME + ".corrupt-" + System.currentTimeMillis();
        try {
            Path target = file.getParent().resolve(quarantineName);
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.warn(
                    "EllieGPS: quarantined corrupt waypoints.json as {} (reason: {}); starting empty.",
                    quarantineName,
                    reason);
        } catch (IOException e) {
            PlayerEngine.LOGGER.warn(
                    "EllieGPS: could not quarantine waypoints.json (reason: {}; rename error: {}); starting empty.",
                    reason,
                    e.getMessage());
        }
        records = new LinkedHashMap<>();
        publishedSnapshot = List.of();
        positionTypeIndex = java.util.Set.of();
        mutationGeneration++;
        rootExtras = null;
        startedFromQuarantine = true;
    }

    private void publishCurrentSnapshot() {
        publishedSnapshot = copyRecords(records.values());
        positionTypeIndex = buildPositionTypeIndex(records.values());
        mutationGeneration++;
    }

    private static java.util.Set<PositionTypeKey> buildPositionTypeIndex(
            Iterable<WaypointRecord> source) {
        HashSet<PositionTypeKey> result = new HashSet<>();
        for (WaypointRecord record : source) {
            BlockPos pos = record == null ? null : record.canonicalBlockPos();
            if (pos != null && record.type != null && record.dimension != null) {
                result.add(new PositionTypeKey(
                        record.type, record.dimension, pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        return java.util.Set.copyOf(result);
    }

    private record PositionTypeKey(String type, String dimension, int x, int y, int z) {
    }

    private static void persistAtomically(Path file, String serializedStore) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(WAYPOINTS_FILE_NAME + ".tmp");
        Files.writeString(temporary, serializedStore, StandardCharsets.UTF_8);
        atomicReplace(temporary, file);
    }

    private static void atomicReplace(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
