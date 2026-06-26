package com.player2.playerengine.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-memory property graph for one companion's memory (Phase D, W2).
 *
 * <p>Adjacency-map container over {@link MemoryNode}s and {@link MemoryEdge}s. Pure data substrate:
 * <b>no</b> ingestion, retrieval, LLM, patron, Minecraft, loader, or I/O dependency. The store
 * ({@code MemoryStore}) owns persistence and publishes immutable snapshots of this graph for
 * lock-free reads.
 *
 * <h3>Threading contract</h3>
 * <ul>
 *   <li><b>Mutation API</b> ({@link #mergeNode}, {@link #mergeEdge}, {@link #recordMention},
 *       {@link #removeNode}, {@link #apply}, {@link #removeEdge}) is <b>server-thread only</b>.</li>
 *   <li><b>Read API</b> ({@link #neighbors}, {@link #node}, {@link #findByCanonicalName},
 *       {@link #nodeCount}, {@link #edgeCount}, {@link #nodes}, {@link #edges}) is safe on any
 *       thread <em>when called on a published, no-longer-mutated snapshot</em>.</li>
 * </ul>
 *
 * <h3>Merge semantics</h3>
 * {@link #mergeNode} is name-keyed set-union (case-insensitive canonical name): union aliases/tags,
 * max importance, sum mentionCount, refresh lastSeen, <b>keep the existing id</b>. {@link #mergeEdge}
 * reinforces on the {@code (fromId, relation, toId)} identity (weight +=, mentionCount++, recency
 * refresh). All strings are capped at merge time via {@link MemoryCaps} — the store can never hold
 * an unbounded string (the egress boundary).
 */
public final class MemoryGraph {

    /** Nodes keyed by id, insertion-ordered for deterministic iteration/token. */
    private final Map<String, MemoryNode> nodes = new LinkedHashMap<>();

    /** Edges keyed by {@code (fromId, relation, toId)} identity. */
    private final Map<String, MemoryEdge> edges = new LinkedHashMap<>();

    /** Outgoing adjacency: fromId → list of edge identity keys. */
    private final Map<String, List<String>> outgoing = new LinkedHashMap<>();

    /** Incoming adjacency: toId → list of edge identity keys. */
    private final Map<String, List<String>> incoming = new LinkedHashMap<>();

    /** Lowercased canonical-name → node id, for dedupe/merge lookup. */
    private final Map<String, String> canonicalNameToId = new LinkedHashMap<>();

    public MemoryGraph() {}

    // =========================================================================
    // Mutation API (server thread only)
    // =========================================================================

    /**
     * Inserts a fresh node, or set-union-merges into the existing node sharing this node's
     * canonical name (case-insensitive). On merge the existing id is kept; aliases/tags are
     * unioned, importance is maxed, mentionCount summed, lastSeen refreshed to the larger tick,
     * and content/timestamp taken from the more recent of the two. All strings are capped.
     *
     * @return the resident node after the merge (its id is the stable canonical id)
     */
    public MemoryNode mergeNode(MemoryNode incoming) {
        if (incoming == null) return null;
        String canon = MemoryCaps.capName(incoming.canonicalName());
        String key = canonicalKey(canon);

        String existingId = (key != null) ? canonicalNameToId.get(key) : null;
        if (existingId == null && nodes.containsKey(incoming.id())) {
            existingId = incoming.id();
        }

        if (existingId == null) {
            // Fresh node — cap and install.
            MemoryNode capped = capNode(rewriteCanonical(incoming, canon));
            nodes.put(capped.id(), capped);
            if (key != null) canonicalNameToId.put(key, capped.id());
            return capped;
        }

        MemoryNode existing = nodes.get(existingId);
        if (existing == null) {
            // Stale index entry — treat as fresh under the existing id.
            MemoryNode capped = capNode(rewriteId(rewriteCanonical(incoming, canon), existingId));
            nodes.put(existingId, capped);
            if (key != null) canonicalNameToId.put(key, existingId);
            return capped;
        }

        MemoryNode merged = unionMerge(existing, incoming, canon);
        nodes.put(existingId, merged);
        if (key != null) canonicalNameToId.put(key, existingId);
        return merged;
    }

    /**
     * Reinforces (or creates) the edge identified by {@code (fromId, relation, toId)}: weight +=
     * {@code addWeight}, mentionCount++, lastSeen refreshed. The relation is capped. No-op-safe if
     * either endpoint is absent? — endpoints are NOT required to exist (W4 may upsert nodes and
     * edges in the same plan; ordering guarantees nodes first). The edge is recorded regardless.
     *
     * @return the resident edge after reinforcement
     */
    public MemoryEdge mergeEdge(String fromId, String toId, String relation,
                                double addWeight, long tick) {
        if (fromId == null || toId == null) return null;
        String rel = MemoryCaps.capRelation(relation);
        String key = MemoryEdge.identityKey(fromId, rel, toId);
        MemoryEdge existing = edges.get(key);
        if (existing == null) {
            MemoryEdge fresh = new MemoryEdge(fromId, toId, rel, addWeight, tick, 1);
            edges.put(key, fresh);
            outgoing.computeIfAbsent(fromId, k -> new ArrayList<>()).add(key);
            incoming.computeIfAbsent(toId, k -> new ArrayList<>()).add(key);
            return fresh;
        }
        MemoryEdge reinforced = existing.reinforced(addWeight, Math.max(existing.lastSeenTick(), tick));
        edges.put(key, reinforced);
        return reinforced;
    }

    /**
     * Records that the node with the given id was mentioned: mentionCount++ and lastSeen refreshed.
     * No-op if the node is absent.
     *
     * @return the updated node, or {@code null} if absent
     */
    public MemoryNode recordMention(String nodeId, long tick) {
        MemoryNode n = nodes.get(nodeId);
        if (n == null) return null;
        MemoryNode updated = new MemoryNode(n.id(), n.content(), n.type(), n.canonicalName(),
                n.aliases().toArray(new String[0]), n.tags().toArray(new String[0]),
                n.importance(), n.createdTick(), n.timestampMs(),
                Math.max(n.lastSeenTick(), tick), n.lastRetrievedTick(),
                n.mentionCount() + 1, n.vector(), n.vectorModel(), n.schemaVersion());
        nodes.put(nodeId, updated);
        return updated;
    }

    /** Removes a node and all its incident edges. No-op if absent. Returns the removed node or null. */
    public MemoryNode removeNode(String nodeId) {
        MemoryNode removed = nodes.remove(nodeId);
        if (removed == null) return null;
        if (removed.canonicalName() != null) {
            String key = canonicalKey(removed.canonicalName());
            if (key != null && nodeId.equals(canonicalNameToId.get(key))) {
                canonicalNameToId.remove(key);
            }
        }
        // Drop incident edges (both directions).
        List<String> out = outgoing.remove(nodeId);
        if (out != null) for (String ek : out) dropEdgeKey(ek);
        List<String> in = incoming.remove(nodeId);
        if (in != null) for (String ek : in) dropEdgeKey(ek);
        return removed;
    }

    /** Removes a single edge by its identity key. Used by {@link MemoryCompactor}. No-op if absent. */
    public boolean removeEdge(String identityKey) {
        MemoryEdge e = edges.remove(identityKey);
        if (e == null) return false;
        List<String> out = outgoing.get(e.fromId());
        if (out != null) out.remove(identityKey);
        List<String> in = incoming.get(e.toId());
        if (in != null) in.remove(identityKey);
        return true;
    }

    /**
     * Applies a {@link MergePlan} atomically in plan order: node upserts first (so edges can
     * reference freshly-minted nodes), then edge upserts, then removals. Server thread only.
     */
    public void apply(MergePlan plan) {
        if (plan == null || plan.isEmpty()) return;
        for (MergePlan.NodeUpsert u : plan.upserts()) {
            MemoryNode incoming = new MemoryNode(
                    u.id, u.content, u.type, u.canonicalName,
                    u.aliases.toArray(new String[0]), u.tags.toArray(new String[0]),
                    u.importance, u.tick, u.timestampMs, u.tick, 0L,
                    1, null, null, MemoryNode.CURRENT_SCHEMA_VERSION);
            mergeNode(incoming);
        }
        for (MergePlan.EdgeUpsert e : plan.edges()) {
            mergeEdge(e.fromId, e.toId, e.relation, e.addWeight, e.tick);
        }
        for (String id : plan.removals()) {
            removeNode(id);
        }
    }

    // =========================================================================
    // Read API (any thread on a published snapshot)
    // =========================================================================

    /** The node with the given id, or {@code null}. */
    public MemoryNode node(String id) {
        return nodes.get(id);
    }

    /**
     * The node whose canonical name equals {@code name} (case-insensitive), or {@code null}.
     */
    public MemoryNode findByCanonicalName(String name) {
        String key = canonicalKey(name);
        if (key == null) return null;
        String id = canonicalNameToId.get(key);
        return id == null ? null : nodes.get(id);
    }

    /**
     * The outgoing edges from the given node id (empty list if none). Read-only view.
     */
    public List<MemoryEdge> neighbors(String nodeId) {
        List<String> keys = outgoing.get(nodeId);
        if (keys == null || keys.isEmpty()) return List.of();
        List<MemoryEdge> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            MemoryEdge e = edges.get(k);
            if (e != null) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    /** The incoming edges into the given node id (empty list if none). Read-only view. */
    public List<MemoryEdge> incomingEdges(String nodeId) {
        List<String> keys = incoming.get(nodeId);
        if (keys == null || keys.isEmpty()) return List.of();
        List<MemoryEdge> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            MemoryEdge e = edges.get(k);
            if (e != null) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }

    /** All nodes in insertion order (read-only view). */
    public Collection<MemoryNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /** All edges in insertion order (read-only view). */
    public Collection<MemoryEdge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static String canonicalKey(String name) {
        if (name == null) return null;
        String t = name.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private void dropEdgeKey(String edgeKey) {
        MemoryEdge e = edges.remove(edgeKey);
        if (e == null) return;
        // The opposite-direction adjacency list still holds this key — scrub it.
        List<String> out = outgoing.get(e.fromId());
        if (out != null) out.remove(edgeKey);
        List<String> in = incoming.get(e.toId());
        if (in != null) in.remove(edgeKey);
    }

    /** Caps all persisted strings on a node (content/name/aliases/tags). */
    private static MemoryNode capNode(MemoryNode n) {
        String content = MemoryCaps.capContent(n.content());
        String canon = MemoryCaps.capName(n.canonicalName());
        String[] aliases = capList(n.aliases(), MemoryCaps.ALIASES_MAX, MemoryCaps.NAME_MAX);
        String[] tags = capList(n.tags(), MemoryCaps.TAGS_MAX, MemoryCaps.NAME_MAX);
        return new MemoryNode(n.id(), content, n.type(), canon, aliases, tags,
                n.importance(), n.createdTick(), n.timestampMs(), n.lastSeenTick(),
                n.lastRetrievedTick(), n.mentionCount(), n.vector(), n.vectorModel(),
                n.schemaVersion());
    }

    private static MemoryNode rewriteCanonical(MemoryNode n, String canon) {
        if (java.util.Objects.equals(n.canonicalName(), canon)) return n;
        return new MemoryNode(n.id(), n.content(), n.type(), canon,
                n.aliases().toArray(new String[0]), n.tags().toArray(new String[0]),
                n.importance(), n.createdTick(), n.timestampMs(), n.lastSeenTick(),
                n.lastRetrievedTick(), n.mentionCount(), n.vector(), n.vectorModel(),
                n.schemaVersion());
    }

    private static MemoryNode rewriteId(MemoryNode n, String id) {
        return new MemoryNode(id, n.content(), n.type(), n.canonicalName(),
                n.aliases().toArray(new String[0]), n.tags().toArray(new String[0]),
                n.importance(), n.createdTick(), n.timestampMs(), n.lastSeenTick(),
                n.lastRetrievedTick(), n.mentionCount(), n.vector(), n.vectorModel(),
                n.schemaVersion());
    }

    /** Set-union merge of {@code incoming} into {@code existing}, keeping existing's id. */
    private static MemoryNode unionMerge(MemoryNode existing, MemoryNode incoming, String canon) {
        // Union aliases (and fold each node's own canonical name into the other's aliases is NOT
        // done — canonical name stays the existing one; only alias/tag sets union).
        LinkedHashSet<String> aliasSet = new LinkedHashSet<>(existing.aliases());
        aliasSet.addAll(incoming.aliases());
        // Preserve a differing incoming name as an alias so the alias index can still match it.
        if (incoming.canonicalName() != null
                && !incoming.canonicalName().equalsIgnoreCase(existing.canonicalName())) {
            aliasSet.add(incoming.canonicalName());
        }
        String[] aliases = capList(new ArrayList<>(aliasSet), MemoryCaps.ALIASES_MAX, MemoryCaps.NAME_MAX);

        LinkedHashSet<String> tagSet = new LinkedHashSet<>(existing.tags());
        tagSet.addAll(incoming.tags());
        String[] tags = capList(new ArrayList<>(tagSet), MemoryCaps.TAGS_MAX, MemoryCaps.NAME_MAX);

        int importance = Math.max(existing.importance(), incoming.importance());
        int mentionCount = existing.mentionCount() + incoming.mentionCount();
        long lastSeen = Math.max(existing.lastSeenTick(), incoming.lastSeenTick());
        long lastRetrieved = Math.max(existing.lastRetrievedTick(), incoming.lastRetrievedTick());

        // Content/type/timestamp from the more recent node (by timestampMs).
        boolean incNewer = incoming.timestampMs() >= existing.timestampMs();
        String content = MemoryCaps.capContent(incNewer ? incoming.content() : existing.content());
        String type = incNewer && incoming.type() != null ? incoming.type() : existing.type();
        long timestampMs = Math.max(existing.timestampMs(), incoming.timestampMs());
        long createdTick = Math.min(
                existing.createdTick() == 0 ? Long.MAX_VALUE : existing.createdTick(),
                incoming.createdTick() == 0 ? Long.MAX_VALUE : incoming.createdTick());
        if (createdTick == Long.MAX_VALUE) createdTick = existing.createdTick();

        // Vector: keep existing if present, else incoming.
        float[] vector = existing.hasVector() ? existing.vector() : incoming.vector();
        String vectorModel = existing.hasVector() ? existing.vectorModel() : incoming.vectorModel();

        return new MemoryNode(existing.id(), content, type, MemoryCaps.capName(canon),
                aliases, tags, importance, createdTick, timestampMs, lastSeen, lastRetrieved,
                mentionCount, vector, vectorModel, existing.schemaVersion());
    }

    private static String[] capList(List<String> values, int maxCount, int maxLen) {
        List<String> out = new ArrayList<>(Math.min(values.size(), maxCount));
        for (String v : values) {
            if (out.size() >= maxCount) break;
            if (v == null) continue;
            String c = MemoryCaps.cap(v, maxLen);
            if (!c.isEmpty()) out.add(c);
        }
        return out.toArray(new String[0]);
    }
}
