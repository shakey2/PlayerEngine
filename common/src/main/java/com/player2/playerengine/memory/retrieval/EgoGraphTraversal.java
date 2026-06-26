package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryEdge;
import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded ego-graph BFS over a published {@link MemoryGraph} snapshot (Phase D, W5 step 4).
 *
 * <p>Starting from the entity-linked seed node ids, this expands outward at most {@code maxHops}
 * (default {@value #DEFAULT_MAX_HOPS}) hops and collects at most {@code maxEgoNodes}
 * (default {@value #DEFAULT_MAX_EGO_NODES}) nodes. Each visited node accumulates a relevance score:
 * <pre>
 *   seed node          → SEED_SCORE
 *   neighbor via edge  → parentScore × (edge.weight × recencyDecay(Δ))
 * </pre>
 * where {@code recencyDecay(Δ) = decayBase ^ (Δ / gameTimeUnit)} and {@code Δ} is ticks since the
 * edge's {@code lastSeenTick} (clamped non-negative). Reinforced, recent edges therefore pull their
 * endpoints higher; stale weak edges contribute little.
 *
 * <p><b>Hot-path discipline:</b> the traversal is deadline-aware. The caller passes a
 * {@code deadlineNanos} (absolute {@link System#nanoTime()} budget); the BFS checks it each time it
 * dequeues a frontier node and returns the partial result rather than overrunning the per-tick
 * budget. Visiting is breadth-first so a truncated walk still favors the closest, strongest nodes.
 *
 * <p>Zero-LLM. Reads only the snapshot graph (any thread). No Minecraft / loader / I/O dependency.
 */
public final class EgoGraphTraversal {

    private EgoGraphTraversal() {}

    /** Default BFS hop ceiling (config {@code memoryMaxHops}). */
    public static final int DEFAULT_MAX_HOPS = 2;
    /** Default BFS node ceiling (config {@code memoryMaxEgoNodes}). */
    public static final int DEFAULT_MAX_EGO_NODES = 64;

    /** Relevance score assigned to a seed node (the BFS root). */
    public static final double SEED_SCORE = 1.0;

    /** A node reached by the ego-graph BFS, with its accumulated relevance score and hop depth. */
    public static final class EgoNode {
        private final MemoryNode node;
        private final double relevance;
        private final int hop;
        private final boolean seed;

        EgoNode(MemoryNode node, double relevance, int hop, boolean seed) {
            this.node = node;
            this.relevance = relevance;
            this.hop = hop;
            this.seed = seed;
        }

        public MemoryNode node()     { return node; }
        public double relevance()    { return relevance; }
        public int hop()             { return hop; }
        public boolean isSeed()      { return seed; }
    }

    /**
     * Runs the bounded BFS.
     *
     * @param graph         the published snapshot graph (read-only)
     * @param seedNodeIds   the entity-linked seed node ids (BFS roots)
     * @param nowTick       current server game time (recency basis)
     * @param maxHops       hop ceiling (≤0 → {@link #DEFAULT_MAX_HOPS})
     * @param maxEgoNodes   node ceiling (≤0 → {@link #DEFAULT_MAX_EGO_NODES})
     * @param decayBase     per-unit recency decay base (≤0 → {@link MemoryCaps#DECAY_BASE})
     * @param gameTimeUnit  ticks per decay unit (≤0 → 1)
     * @param deadlineNanos absolute {@link System#nanoTime()} budget; the walk stops (partial) past it
     * @return the reached ego nodes keyed by id, best-relevance kept; never null
     */
    public static List<EgoNode> traverse(MemoryGraph graph,
                                         List<String> seedNodeIds,
                                         long nowTick,
                                         int maxHops,
                                         int maxEgoNodes,
                                         double decayBase,
                                         long gameTimeUnit,
                                         long deadlineNanos) {
        if (graph == null || seedNodeIds == null || seedNodeIds.isEmpty()) {
            return List.of();
        }
        int hops = maxHops > 0 ? maxHops : DEFAULT_MAX_HOPS;
        int cap = maxEgoNodes > 0 ? maxEgoNodes : DEFAULT_MAX_EGO_NODES;
        double base = decayBase > 0.0 ? decayBase : MemoryCaps.DECAY_BASE;
        long unit = gameTimeUnit > 0 ? gameTimeUnit : 1L;

        // Best-relevance-per-node accumulator (insertion-ordered for deterministic output).
        Map<String, EgoNode> reached = new LinkedHashMap<>();
        Set<String> enqueued = new HashSet<>();
        Deque<Frontier> queue = new ArrayDeque<>();

        for (String seedId : seedNodeIds) {
            if (seedId == null) continue;
            MemoryNode n = graph.node(seedId);
            if (n == null || enqueued.contains(seedId)) continue;
            enqueued.add(seedId);
            queue.add(new Frontier(seedId, SEED_SCORE, 0));
            reached.put(seedId, new EgoNode(n, SEED_SCORE, 0, true));
        }

        while (!queue.isEmpty()) {
            // Deadline guard — return the partial frontier rather than overrun the tick budget.
            if (System.nanoTime() >= deadlineNanos) break;
            if (reached.size() >= cap) break;

            Frontier cur = queue.poll();
            if (cur.hop >= hops) continue;

            for (MemoryEdge edge : graph.neighbors(cur.id)) {
                String toId = edge.toId();
                if (toId == null) continue;
                MemoryNode neighbor = graph.node(toId);
                if (neighbor == null) continue;

                double step = edge.weight() * recencyDecay(nowTick - edge.lastSeenTick(), base, unit);
                double childScore = cur.score * step;

                EgoNode existing = reached.get(toId);
                if (existing == null) {
                    if (reached.size() >= cap) break;
                    // A node first reached as a neighbor is not a seed (seeds are pre-seeded above).
                    reached.put(toId, new EgoNode(neighbor, childScore, cur.hop + 1, false));
                } else if (childScore > existing.relevance()) {
                    reached.put(toId, new EgoNode(neighbor, childScore,
                            Math.min(existing.hop(), cur.hop + 1), existing.isSeed()));
                }

                if (!enqueued.contains(toId) && (cur.hop + 1) < hops) {
                    enqueued.add(toId);
                    queue.add(new Frontier(toId, childScore, cur.hop + 1));
                }
            }
        }

        return new ArrayList<>(reached.values());
    }

    /** {@code decayBase ^ (Δ / unit)}, with {@code Δ} clamped non-negative. */
    static double recencyDecay(long deltaTicks, double decayBase, long unit) {
        long delta = Math.max(0L, deltaTicks);
        double exponent = (double) delta / (double) (unit > 0 ? unit : 1L);
        return Math.pow(decayBase, exponent);
    }

    /** Internal BFS frontier entry. */
    private static final class Frontier {
        final String id;
        final double score;
        final int hop;
        Frontier(String id, double score, int hop) {
            this.id = id;
            this.score = score;
            this.hop = hop;
        }
    }

    /** Convenience: index ego nodes by id for the reranker's relevance lookup. */
    public static Map<String, EgoNode> byId(List<EgoNode> egoNodes) {
        Map<String, EgoNode> map = new HashMap<>(Math.max(16, egoNodes.size() * 2));
        for (EgoNode e : egoNodes) {
            map.put(e.node().id(), e);
        }
        return map;
    }
}
