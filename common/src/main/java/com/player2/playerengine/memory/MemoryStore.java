package com.player2.playerengine.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-companion on-disk persistence for one {@link MemoryGraph} (Phase D, W2).
 *
 * <p>Mirrors {@code EllieGPSStore}'s load / atomic-write / quarantine / version-token machinery,
 * <b>plus</b> a dirty-flag debounce adapted from {@code PlayerPlacedBlockStore}: instead of a
 * timer the memory store flushes <b>on tick-end</b> ({@link #flushIfDirty()} called by the
 * lifecycle workstream). Mutations set the dirty flag and republish the snapshot but never persist
 * synchronously — the graph mutates far more often than waypoints.
 *
 * <h3>Threading contract</h3>
 * Mutations ({@link #mergeCandidates}, {@link #recordMention}, {@link #removeNode},
 * {@link #setRelationshipSummary}, {@link #addCumulativeImportance}) and {@link #flushIfDirty()} /
 * {@link #clear()} run on the <b>server thread</b>. The {@code volatile} {@link #snapshot()} is a
 * lock-free read of an immutable graph for any thread (W5 builds its index from it).
 *
 * <h3>Egress boundary</h3>
 * Every persisted string is capped at merge time inside {@link MemoryGraph} via {@link MemoryCaps};
 * this store reads/writes only those bounded strings and never reads a log, captures a stream, or
 * serializes a {@code Throwable} into any node/edge (DESIGN.md §3). Quarantine notes are a short,
 * bounded, templated phrase — never the raw exception.
 *
 * <p>No Minecraft types beyond the world root {@link Path} (supplied by the caller from
 * {@code server.getWorldPath(LevelResource.ROOT)}); this class itself imports no loader/MC API.
 */
public final class MemoryStore {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Root + node schema version this build supports; a higher on-disk value quarantines. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------------------------------------------------------------------------
    // Identity / location
    // -------------------------------------------------------------------------

    private final MemoryScope scope;
    private final Path graphFile;

    // -------------------------------------------------------------------------
    // Live state (server-thread mutated)
    // -------------------------------------------------------------------------

    private final MemoryGraph graph = new MemoryGraph();

    /** Root-level reflection fields (W6 owns the values; W2 owns the persistence). */
    private String relationshipSummary = "";
    private long cumulativeImportanceSinceLastReflection = 0L;
    private int importanceRubricVersion = 1;
    private int summaryVersion = 0;

    /** Unknown top-level members of the loaded root, retained for forward-compat round-trip. */
    private JsonObject rootExtras;

    /** True once a mutation has occurred since the last flush. */
    private boolean dirty = false;

    /** True if this store started empty due to a corrupt-file/schema quarantine. */
    private volatile boolean startedFromQuarantine = false;

    // -------------------------------------------------------------------------
    // Published snapshot (lock-free any-thread read)
    // -------------------------------------------------------------------------

    /** Immutable snapshot published on every mutation. */
    private volatile Snapshot snapshot;

    /** A point-in-time immutable view of the graph plus its version token and reflection summary. */
    public static final class Snapshot {
        private final MemoryGraph graph;
        private final String versionToken;
        private final String relationshipSummary;

        Snapshot(MemoryGraph graph, String versionToken, String relationshipSummary) {
            this.graph = graph;
            this.versionToken = versionToken;
            this.relationshipSummary = relationshipSummary;
        }

        /** The immutable graph (do not mutate; reads only). */
        public MemoryGraph graph()             { return graph; }
        public String versionToken()           { return versionToken; }
        public String relationshipSummary()    { return relationshipSummary; }
    }

    // -------------------------------------------------------------------------
    // Construction / load
    // -------------------------------------------------------------------------

    private MemoryStore(MemoryScope scope, Path graphFile) {
        this.scope = scope;
        this.graphFile = graphFile;
    }

    /**
     * Loads (or lazily initializes) the memory store for one companion. Never throws — a parse or
     * schema failure quarantines the file and starts empty.
     *
     * @param worldRoot the world root path ({@code server.getWorldPath(LevelResource.ROOT)})
     * @param scope     the per-companion scope key
     */
    public static MemoryStore loadForCompanion(Path worldRoot, MemoryScope scope) {
        Path file = scope.hasOwner()
                ? Player2NpcPersistencePaths.memoryGraphFile(worldRoot, scope.ownerUuid(), scope.companionId())
                : Player2NpcPersistencePaths.memoryGraphFileEntityFallback(worldRoot, scope.entityUuid(), scope.companionId());
        MemoryStore store = new MemoryStore(scope, file);
        store.load();
        store.publishSnapshot();
        return store;
    }

    private void load() {
        if (!Files.exists(graphFile)) {
            PlayerEngine.LOGGER.info("Memory: no graph.json for {} — starting empty.", scope);
            return;
        }
        try {
            String json = Files.readString(graphFile, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                quarantine("root is null");
                return;
            }
            int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 0;
            if (version > SUPPORTED_SCHEMA_VERSION) {
                quarantine("schemaVersion " + version + " > supported " + SUPPORTED_SCHEMA_VERSION);
                return;
            }

            // Reflection / counter root fields.
            relationshipSummary = optString(root, "relationshipSummary", "");
            cumulativeImportanceSinceLastReflection =
                    optLong(root, "cumulativeImportanceSinceLastReflection", 0L);
            importanceRubricVersion = optInt(root, "importanceRubricVersion", 1);
            summaryVersion = optInt(root, "summaryVersion", 0);

            // Retain unknown root members for forward-compat round-trip.
            JsonObject extras = root.deepCopy();
            for (String known : KNOWN_ROOT_KEYS) extras.remove(known);
            rootExtras = extras.size() > 0 ? extras : null;

            int loadedNodes = loadNodes(root);
            int loadedEdges = loadEdges(root);
            PlayerEngine.LOGGER.info("Memory: loaded {} node(s), {} edge(s) for {}.",
                    loadedNodes, loadedEdges, scope);
        } catch (Exception e) {
            quarantine("parse error: " + safeReason(e));
        }
    }

    private int loadNodes(JsonObject root) {
        if (!root.has("nodes") || !root.get("nodes").isJsonArray()) return 0;
        int loaded = 0;
        for (JsonElement elem : root.getAsJsonArray("nodes")) {
            if (!elem.isJsonObject()) continue;
            MemoryNode n = MemoryNodeCodec.fromJson(elem.getAsJsonObject());
            if (n == null || n.id() == null) continue;
            graph.mergeNode(n);
            loaded++;
        }
        return loaded;
    }

    private int loadEdges(JsonObject root) {
        if (!root.has("edges") || !root.get("edges").isJsonArray()) return 0;
        int loaded = 0;
        for (JsonElement elem : root.getAsJsonArray("edges")) {
            if (!elem.isJsonObject()) continue;
            JsonObject o = elem.getAsJsonObject();
            String fromId = optString(o, "fromId", null);
            String toId = optString(o, "toId", null);
            if (fromId == null || toId == null) continue;
            String relation = optString(o, "relation", "");
            double weight = o.has("weight") ? o.get("weight").getAsDouble() : 1.0;
            long lastSeen = optLong(o, "lastSeenTick", 0L);
            int mentionCount = Math.max(1, optInt(o, "mentionCount", 1));
            // Reconstruct the edge with its persisted weight/recency/count via mergeEdge then
            // overlay (mergeEdge would otherwise start fresh at weight=addWeight,count=1); we mint
            // it directly through the graph's reinforcement contract.
            graph.mergeEdge(fromId, toId, relation, weight, lastSeen);
            // mergeEdge created count=1; reinforce up to the persisted count.
            for (int i = 1; i < mentionCount; i++) {
                graph.mergeEdge(fromId, toId, relation, 0.0, lastSeen);
            }
            loaded++;
        }
        return loaded;
    }

    /**
     * Quarantines the on-disk graph by renaming it to {@code graph.json.corrupt-<epoch>}, clears
     * in-memory state, and starts empty. Surfaces a one-time bounded note. The reason is a short,
     * templated phrase — never the raw exception text (DESIGN.md §3 egress rule).
     */
    private void quarantine(String boundedReason) {
        String quarantineName = Player2NpcPersistencePaths.MEMORY_GRAPH_FILE_NAME
                + ".corrupt-" + System.currentTimeMillis();
        try {
            Path target = graphFile.getParent().resolve(quarantineName);
            Files.move(graphFile, target, StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.warn("Memory: quarantined corrupt graph.json as {} for {} (reason: {}); starting empty.",
                    quarantineName, scope, boundedReason);
        } catch (IOException ex) {
            PlayerEngine.LOGGER.warn("Memory: could not quarantine graph.json for {} (reason: {}); starting empty.",
                    scope, boundedReason);
        }
        resetInMemory();
        startedFromQuarantine = true;
    }

    private void resetInMemory() {
        for (MemoryNode n : new ArrayList<>(graph.nodes())) graph.removeNode(n.id());
        relationshipSummary = "";
        cumulativeImportanceSinceLastReflection = 0L;
        importanceRubricVersion = 1;
        summaryVersion = 0;
        rootExtras = null;
    }

    // -------------------------------------------------------------------------
    // Mutation entry points (server thread)
    // -------------------------------------------------------------------------

    /**
     * Applies a resolved {@link MergePlan} of entity + relation candidates as graph mutations
     * (W3/W4 entry point), marks the store dirty, and republishes the snapshot (which bumps the
     * version token). Does NOT persist synchronously — {@link #flushIfDirty()} does that at
     * tick-end.
     */
    public void mergeCandidates(MergePlan plan) {
        if (plan == null || plan.isEmpty()) return;
        // Accumulate the reflection-trigger counter from each NEWLY-minted episodic (EVENT) vertex's
        // importance BEFORE applying the plan, so we can tell a fresh episode from a re-merge of an
        // existing one (episodic ids are batch-unique; a node already present means it was counted on a
        // prior merge — skip it to avoid double-counting). Entity nodes are excluded: only the episodic
        // provenance vertex carries the batch importance the reflection cadence is meant to track.
        long episodicImportance = newlyMintedEpisodicImportance(plan);
        graph.apply(plan);
        if (episodicImportance > 0L) {
            // Inline the accumulation (addCumulativeImportance would publish a second snapshot).
            this.cumulativeImportanceSinceLastReflection += episodicImportance;
        }
        markDirtyAndPublish();
    }

    /**
     * Sums the importance of every EVENT-typed upsert in the plan whose id is NOT already a resident
     * node — i.e. the freshly-minted episodic provenance vertices from this batch. A re-merge of an
     * already-present episode contributes 0 (guards against double-counting the reflection-trigger
     * counter). Reflection-written REFLECTION nodes are unscored (importance 0) and never count.
     */
    private long newlyMintedEpisodicImportance(MergePlan plan) {
        long sum = 0L;
        // Guard against two upserts in the same plan sharing an id: the graph isn't updated until
        // graph.apply(plan), so a duplicate id would not be seen as resident on the second pass and
        // would be scored twice. Count each fresh id at most once.
        java.util.Set<String> countedThisBatch = null;
        for (MergePlan.NodeUpsert u : plan.upserts()) {
            if (u == null || u.importance <= 0) continue;
            if (!MemoryNodeType.EVENT.wire().equalsIgnoreCase(u.type)) continue;
            if (graph.node(u.id) != null) continue; // already present → counted on a prior merge
            if (countedThisBatch == null) countedThisBatch = new java.util.HashSet<>();
            if (!countedThisBatch.add(u.id)) continue; // duplicate id within this same plan
            sum += u.importance;
        }
        return sum;
    }

    /** Records a mention on an existing node id (recency + mentionCount). Marks dirty. */
    public void recordMention(String nodeId, long tick) {
        if (graph.recordMention(nodeId, tick) != null) {
            markDirtyAndPublish();
        }
    }

    /** Removes a node and its incident edges. Marks dirty if something was removed. */
    public boolean removeNode(String nodeId) {
        if (graph.removeNode(nodeId) != null) {
            markDirtyAndPublish();
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Reflection-field accessors / mutators (W6 calls these)
    // -------------------------------------------------------------------------

    /** The current relationship summary (byte-stable between reflections). */
    public String relationshipSummary() {
        return relationshipSummary;
    }

    /**
     * Sets the relationship summary (capped to the effective summary char cap), resets the
     * cumulative importance counter, bumps the summary version, and marks dirty. Called by W6 on
     * reflection. The effective cap is the operator-configured value clamped to the
     * {@link #RELATIONSHIP_SUMMARY_CHAR_CAP} hard ceiling, so the stored value can never exceed the
     * read-side egress bound that {@code RelationshipSummary.suffixFor} re-applies at the system-block
     * combine point (prefix-cache / egress invariant: the stored value is always already within bound).
     */
    public void setRelationshipSummary(String summary) {
        this.relationshipSummary = MemoryCaps.cap(summary, effectiveSummaryCharCap());
        this.cumulativeImportanceSinceLastReflection = 0L;
        this.summaryVersion++;
        markDirtyAndPublish();
    }

    /**
     * The operator-configured relationship-summary char cap, clamped to the hard ceiling
     * {@link #RELATIONSHIP_SUMMARY_CHAR_CAP}. Config can only TIGHTEN the cap; it can never raise the
     * stored summary above the read-side egress bound applied by {@code RelationshipSummary}.
     */
    public static int effectiveSummaryCharCap() {
        int configured = Player2ServerConfigHolder.get().getRelationshipSummaryCharCapClamped();
        return Math.min(RELATIONSHIP_SUMMARY_CHAR_CAP, Math.max(0, configured));
    }

    public long cumulativeImportanceSinceLastReflection() {
        return cumulativeImportanceSinceLastReflection;
    }

    /** Adds to the cumulative importance reflection-trigger counter. Marks dirty. */
    public void addCumulativeImportance(long delta) {
        if (delta == 0) return;
        this.cumulativeImportanceSinceLastReflection += delta;
        markDirtyAndPublish();
    }

    /**
     * Resets ONLY the reflection-trigger counter to 0 WITHOUT bumping {@code summaryVersion} or
     * touching the relationship summary. Used by W6 when a reflection produced insight nodes but the
     * relationship-summary step was budget-capped: the summary (and therefore the prefix-cache /
     * system block) is unchanged, so {@code summaryVersion} must NOT bump, but the counter must still
     * reset so the next reflection fires at the configured cadence instead of immediately re-triggering
     * and recursing into the same budget cap. No-op if already 0.
     */
    public void resetReflectionCounterOnly() {
        if (this.cumulativeImportanceSinceLastReflection == 0L) return;
        this.cumulativeImportanceSinceLastReflection = 0L;
        markDirtyAndPublish();
    }

    public int importanceRubricVersion() {
        return importanceRubricVersion;
    }

    public void setImportanceRubricVersion(int v) {
        this.importanceRubricVersion = v;
        markDirtyAndPublish();
    }

    public int summaryVersion() {
        return summaryVersion;
    }

    // -------------------------------------------------------------------------
    // Snapshot / token
    // -------------------------------------------------------------------------

    /** The latest published immutable snapshot (lock-free, any thread). */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** The current deterministic version token of the live graph. */
    public String versionToken() {
        return computeVersionToken();
    }

    /** True iff this store started empty due to a quarantine (accessor; does not clear). */
    public boolean startedFromQuarantine() {
        return startedFromQuarantine;
    }

    /** One-time quarantine note: true exactly once after a quarantine, then cleared. */
    public boolean consumeQuarantineNote() {
        if (startedFromQuarantine) {
            startedFromQuarantine = false;
            return true;
        }
        return false;
    }

    public MemoryScope scope() {
        return scope;
    }

    // -------------------------------------------------------------------------
    // Flush / clear lifecycle
    // -------------------------------------------------------------------------

    /**
     * If dirty, runs {@link MemoryCompactor} (to bring the corpus within ceilings) then persists
     * atomically, and clears the dirty flag. Called at tick-end by the lifecycle workstream.
     * No-op when clean. Never throws.
     *
     * @param nowTick the current server game time (compaction recency basis)
     */
    public void flushIfDirty(long nowTick) {
        if (!dirty) return;
        dirty = false; // clear before writing; a concurrent mutation re-sets it for the next pass
        try {
            MemoryCompactor.compact(graph, nowTick);
            publishSnapshot();
            persist();
        } catch (Exception e) {
            dirty = true; // retry next flush
            PlayerEngine.LOGGER.warn("Memory: flush failed for {} (reason: {}); will retry.",
                    scope, safeReason(e));
        }
    }

    /**
     * Final flush + in-memory clear. Called at {@code SERVER_STOPPING} by the lifecycle workstream.
     */
    public void clear(long nowTick) {
        try {
            if (dirty) {
                MemoryCompactor.compact(graph, nowTick);
                persist();
            }
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("Memory: final flush failed for {} (reason: {}).",
                    scope, safeReason(e));
        }
        resetInMemory();
        snapshot = new Snapshot(new MemoryGraph(), computeVersionToken(), "");
        dirty = false;
    }

    // -------------------------------------------------------------------------
    // Internal: persist
    // -------------------------------------------------------------------------

    private void persist() {
        try {
            Files.createDirectories(graphFile.getParent());
            JsonObject root = (rootExtras != null) ? rootExtras.deepCopy() : new JsonObject();
            root.addProperty("schemaVersion", SUPPORTED_SCHEMA_VERSION);
            if (scope.hasOwner()) root.addProperty("ownerUuid", scope.ownerUuid().toString());
            root.addProperty("companionId", scope.companionId());
            root.addProperty("relationshipSummary", relationshipSummary);
            root.addProperty("cumulativeImportanceSinceLastReflection",
                    cumulativeImportanceSinceLastReflection);
            root.addProperty("importanceRubricVersion", importanceRubricVersion);
            root.addProperty("summaryVersion", summaryVersion);

            JsonArray nodeArr = new JsonArray();
            for (MemoryNode n : graph.nodes()) nodeArr.add(MemoryNodeCodec.toJson(n));
            root.add("nodes", nodeArr);

            JsonArray edgeArr = new JsonArray();
            for (MemoryEdge e : graph.edges()) edgeArr.add(edgeToJson(e));
            root.add("edges", edgeArr);

            String json = GSON.toJson(root);
            Path tmp = graphFile.resolveSibling(Player2NpcPersistencePaths.MEMORY_GRAPH_FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            atomicReplace(tmp, graphFile);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("Memory: failed to persist graph.json for {} (reason: {}).",
                    scope, safeReason(e));
        }
    }

    private static JsonObject edgeToJson(MemoryEdge e) {
        JsonObject o = new JsonObject();
        o.addProperty("fromId", e.fromId());
        o.addProperty("toId", e.toId());
        o.addProperty("relation", MemoryCaps.capRelation(e.relation()));
        o.addProperty("weight", e.weight());
        o.addProperty("lastSeenTick", e.lastSeenTick());
        o.addProperty("mentionCount", e.mentionCount());
        return o;
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // -------------------------------------------------------------------------
    // Internal: snapshot + version token
    // -------------------------------------------------------------------------

    private void markDirtyAndPublish() {
        dirty = true;
        publishSnapshot();
    }

    /** Publishes an immutable snapshot (a defensive copy of the live graph) for lock-free reads. */
    private void publishSnapshot() {
        MemoryGraph copy = copyGraph();
        snapshot = new Snapshot(copy, computeVersionToken(copy), relationshipSummary);
    }

    /** Builds an independent copy of the live graph so the snapshot never aliases mutable state. */
    private MemoryGraph copyGraph() {
        MemoryGraph copy = new MemoryGraph();
        for (MemoryNode n : graph.nodes()) copy.mergeNode(n);
        for (MemoryEdge e : graph.edges()) {
            copy.mergeEdge(e.fromId(), e.toId(), e.relation(), e.weight(), e.lastSeenTick());
            for (int i = 1; i < e.mentionCount(); i++) {
                copy.mergeEdge(e.fromId(), e.toId(), e.relation(), 0.0, e.lastSeenTick());
            }
        }
        return copy;
    }

    private String computeVersionToken() {
        return computeVersionToken(graph);
    }

    /**
     * Deterministic version token: {@code "1:" + SHA-256} over sorted node lines + sorted edge
     * lines (mirrors {@code EllieGPSStore.versionToken}). W5 keys its in-memory index rebuild on
     * this token. Tie-breaks (sorting) make the token reproducible across compaction.
     */
    private static String computeVersionToken(MemoryGraph g) {
        List<String> nodeLines = new ArrayList<>(g.nodeCount());
        for (MemoryNode n : g.nodes()) {
            nodeLines.add(n.id() + '|' + safe(n.canonicalName()) + '|' + safe(n.type()) + '|'
                    + n.importance() + '|' + n.mentionCount() + '|' + n.lastSeenTick() + '|'
                    + String.join(",", n.aliases()) + '|' + safe(n.content()));
        }
        Collections.sort(nodeLines);

        List<String> edgeLines = new ArrayList<>(g.edgeCount());
        for (MemoryEdge e : g.edges()) {
            edgeLines.add(e.fromId() + '|' + e.relation() + '|' + e.toId() + '|'
                    + e.weight() + '|' + e.mentionCount() + '|' + e.lastSeenTick());
        }
        Collections.sort(edgeLines);

        StringBuilder sb = new StringBuilder();
        for (String l : nodeLines) sb.append(l).append('\n');
        sb.append("--edges--\n");
        for (String l : edgeLines) sb.append(l).append('\n');

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return "1:" + hex;
        } catch (Exception e) {
            return "1:unknown";
        }
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    /**
     * The reflection-summary character cap (prefix-size budget; plan {@code relationshipSummaryCharCap}).
     * Playtest-tunable placeholder.
     */
    static final int RELATIONSHIP_SUMMARY_CHAR_CAP = 280;

    private static final List<String> KNOWN_ROOT_KEYS = List.of(
            "schemaVersion", "ownerUuid", "companionId", "relationshipSummary",
            "cumulativeImportanceSinceLastReflection", "importanceRubricVersion",
            "summaryVersion", "nodes", "edges");

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Bounded, templated reason for a caught exception — never the message/stack (egress rule). */
    private static String safeReason(Exception e) {
        return e.getClass().getSimpleName();
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
}
