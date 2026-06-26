package com.player2.playerengine.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pure, ordered description of graph mutations to apply atomically (Phase D, W2 contract;
 * produced by W4's {@code NodeMerger}, applied by {@link MemoryGraph#apply(MergePlan)} /
 * {@code MemoryStore.mergeCandidates}).
 *
 * <p>The plan is a value object: it carries <em>what to do</em>, never touches the graph itself,
 * and has no Minecraft/loader/I/O dependency. The graph applies it on the server thread in list
 * order, set-union/reinforcement semantics per op (see {@link MemoryGraph}).
 *
 * <p><b>Order contract:</b> {@code upserts} are applied before {@code edges} so an edge can
 * reference a node minted in the same plan. {@code removals} run last (used by W4's
 * canonical-collapse where a duplicate node id is folded into the surviving canonical id).
 *
 * <p><b>NodeMerger output shape (W4 must produce):</b> for each resolved entity, one
 * {@link NodeUpsert} carrying the canonical node id, canonical name, type hint, the union of
 * aliases and tags, the summed mentionCount, the max importance, and the latest content/timestamp;
 * for each resolved relation, one {@link EdgeUpsert} with {@code (fromCanonicalId, relation,
 * toCanonicalId)} keyed for reinforcement; and, when W4 collapses a duplicate id into a canonical
 * one, a {@link #removals()} entry for the loser id (its edges should already have been re-pointed
 * to the canonical id as {@link EdgeUpsert}s).
 */
public final class MergePlan {

    /** Upsert/merge a node (set-union into any existing node with the same canonical name/id). */
    public static final class NodeUpsert {
        public final String id;
        public final String content;
        public final String type;            // lenient wire string (MemoryNodeType.wire()) or raw
        public final String canonicalName;
        public final List<String> aliases;   // never null
        public final List<String> tags;      // never null
        public final int importance;         // 0 = unscored
        public final long timestampMs;
        public final long tick;              // server game tick for createdTick/lastSeen on mint/refresh

        public NodeUpsert(String id, String content, String type, String canonicalName,
                          List<String> aliases, List<String> tags, int importance,
                          long timestampMs, long tick) {
            this.id = id;
            this.content = content;
            this.type = type;
            this.canonicalName = canonicalName;
            this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
            this.tags = tags == null ? List.of() : List.copyOf(tags);
            this.importance = importance;
            this.timestampMs = timestampMs;
            this.tick = tick;
        }
    }

    /** Upsert/reinforce an edge keyed on {@code (fromId, relation, toId)}. */
    public static final class EdgeUpsert {
        public final String fromId;
        public final String toId;
        public final String relation;
        public final double addWeight;       // reinforcement increment (default 1.0)
        public final long tick;

        public EdgeUpsert(String fromId, String toId, String relation, double addWeight, long tick) {
            this.fromId = fromId;
            this.toId = toId;
            this.relation = relation;
            this.addWeight = addWeight;
            this.tick = tick;
        }
    }

    private final List<NodeUpsert> upserts;
    private final List<EdgeUpsert> edges;
    private final List<String> removals;

    private MergePlan(List<NodeUpsert> upserts, List<EdgeUpsert> edges, List<String> removals) {
        this.upserts = Collections.unmodifiableList(upserts);
        this.edges = Collections.unmodifiableList(edges);
        this.removals = Collections.unmodifiableList(removals);
    }

    public List<NodeUpsert> upserts()  { return upserts; }
    public List<EdgeUpsert> edges()    { return edges; }
    public List<String> removals()     { return removals; }

    public boolean isEmpty() {
        return upserts.isEmpty() && edges.isEmpty() && removals.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable assembler for a {@link MergePlan} (used by W4's NodeMerger). */
    public static final class Builder {
        private final List<NodeUpsert> upserts = new ArrayList<>();
        private final List<EdgeUpsert> edges = new ArrayList<>();
        private final List<String> removals = new ArrayList<>();

        public Builder upsert(NodeUpsert n) { if (n != null) upserts.add(n); return this; }
        public Builder edge(EdgeUpsert e)   { if (e != null) edges.add(e); return this; }
        public Builder remove(String id)    { if (id != null) removals.add(id); return this; }

        public MergePlan build() {
            return new MergePlan(upserts, edges, removals);
        }
    }
}
