package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryNodeType;
import com.player2.playerengine.memory.retrieval.MemoryRetrievalConfidence.BoundaryVerdict;
import com.player2.playerengine.retrieval.RetrievalHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Serializes a retrieval verdict into the bounded, hard-capped tail block injected into the
 * throwaway per-turn status copy (Phase D, W5 step 8).
 *
 * <p>Three verdict shapes:
 * <ul>
 *   <li>{@link BoundaryVerdict#HAS_MEMORY} → a plain-text {@code "[Memory] …"} block, one line per
 *       ranked hit (canonical name + capped content). Total length is hard-capped at
 *       {@link #DEFAULT_BLOCK_CHAR_CAP} (config {@code memoryBlockCharCap}); over-budget blocks
 *       truncate by <b>dropping the lowest-ranked lines</b> (the fused order is highest-first), with
 *       a defensive per-line re-clamp. The primary cap is the W2/W4 write-time cap; this is the
 *       belt-and-suspenders egress re-clamp.</li>
 *   <li>{@link BoundaryVerdict#NO_MEMORY} → a short <b>templated decline note</b> — the truthfulness
 *       lever that tells the model to say it does not recall rather than fabricate facts.</li>
 *   <li>{@link BoundaryVerdict#STORE_ABSENT} → emits {@link Optional#empty()}; the caller injects
 *       <b>nothing</b>, so the request is byte-identical to a pre-W5 build (prefix-cache safe).</li>
 * </ul>
 *
 * <p>Every string written here originates from the capped graph snapshot (W2/W4 write-time caps) and
 * is re-clamped; no log, stack trace, or unbounded external string can enter this block (DESIGN.md §3).
 * Zero-LLM, pure. No Minecraft / loader / I/O dependency.
 */
public final class MemoryBlockSerializer {

    private MemoryBlockSerializer() {}

    /** Default total block char cap (~400 tokens); config {@code memoryBlockCharCap}. */
    public static final int DEFAULT_BLOCK_CHAR_CAP = 1600;

    /** Defensive per-line cap (canonical name + content); keeps any single line bounded. */
    static final int LINE_CHAR_CAP = MemoryCaps.NAME_MAX + MemoryCaps.CONTENT_MAX + 8;

    private static final String HEADER = "[Memory]";

    /**
     * Closed-world anti-fabrication footer appended after the bulleted memories in a
     * {@link BoundaryVerdict#HAS_MEMORY} block. Static, bounded, author-controlled.
     *
     * <p><b>Scope (Phase D fixation fix):</b> the HAS_MEMORY body now lists ONLY episodic
     * {@link MemoryNodeType#EVENT} recall — stable profile facts (favorite colour, who the player is,
     * etc.) are deliberately excluded from per-turn recall and surface naturally via the relationship
     * summary in the system block instead. So this footer is scoped to <em>shared-history events</em>:
     * it frames the listed events as the COMPLETE set of remembered shared history so the model cannot
     * treat a partial seed match (e.g. the always-present self/owner nodes, or a profile entity that
     * merely seeded retrieval) as license to agree to a fabricated "remember when…" event. It no longer
     * claims the list is everything the companion knows about the player — only everything it remembers
     * <em>happening together</em>. Kept byte-stable (prefix-cache) and counted within the bounded block:
     * {@link #buildHasMemory} reserves cap room for it so the whole block never exceeds the char cap
     * (DESIGN.md §3 egress bound).
     */
    public static final String HAS_MEMORY_FOOTER =
            "These are the ONLY shared experiences and events you remember happening with this player. "
            + "If the player refers to a specific event or moment you shared that is NOT listed above, "
            + "you do NOT remember it — say so honestly and do not invent, agree to, or play along with "
            + "a shared memory that is not here.";

    /**
     * Templated decline note for {@link BoundaryVerdict#NO_MEMORY}. Static, bounded, author-controlled
     * — instructs the model to decline rather than invent, with the same closed-world anti-fabrication
     * framing as {@link #HAS_MEMORY_FOOTER}. Kept byte-stable so it never busts the cache with churn.
     */
    public static final String NO_MEMORY_NOTE =
            "[Memory] You have no recollection of what was just mentioned. You do NOT remember the "
            + "specific event, person, place, or fact the player referred to. Say plainly that you do "
            + "not remember rather than inventing details, and do not agree to or play along with a "
            + "memory you do not actually have.";

    /**
     * Builds the tail block for the given verdict.
     *
     * @param verdict       the knowledge-boundary verdict
     * @param hits          the fused ranked hits (highest-first); only used for HAS_MEMORY
     * @param graph         the snapshot graph the hit ids resolve against
     * @param blockCharCap  total char cap (≤0 → {@link #DEFAULT_BLOCK_CHAR_CAP})
     * @return the block to inject, or {@link Optional#empty()} to inject nothing (STORE_ABSENT)
     */
    public static Optional<String> serialize(BoundaryVerdict verdict,
                                             List<RetrievalHit> hits,
                                             MemoryGraph graph,
                                             int blockCharCap) {
        switch (verdict) {
            case STORE_ABSENT:
                return Optional.empty();
            case NO_MEMORY:
                return Optional.of(NO_MEMORY_NOTE);
            case HAS_MEMORY:
            default:
                String block = buildHasMemory(hits, graph, blockCharCap);
                // An empty body here means the confident hits were ALL stable profile/entity nodes and
                // carried NO episodic EVENT recall (Phase D fixation fix filters profile nodes out of the
                // recall body). In that case inject NOTHING — do NOT emit the closed-world decline note.
                //
                // Why not NO_MEMORY_NOTE: the turn DID link to real graph knowledge (a profile fact such
                // as the player's favourite colour), and that fact already surfaces via the relationship
                // summary in the SYSTEM block. Emitting "you have no recollection of what was just
                // mentioned" would directly CONTRADICT the summary the model can already see — a
                // truthfulness regression. The genuine "linked to nothing specific" decline is the
                // separate NO_MEMORY verdict above (driven by the seedMatches / specificSeedMatches
                // gate), which is unchanged. Returning empty keeps the request byte-identical to a
                // STORE_ABSENT turn (prefix-cache safe).
                return block.isEmpty() ? Optional.empty() : Optional.of(block);
        }
    }

    private static String buildHasMemory(List<RetrievalHit> hits, MemoryGraph graph, int blockCharCap) {
        int cap = blockCharCap > 0 ? blockCharCap : DEFAULT_BLOCK_CHAR_CAP;

        // Render one line per hit, in fused (highest-first) order — but ONLY for episodic EVENT nodes.
        //
        // Phase D fixation fix (SEPARATE PROFILE FROM RECALL): stable profile/entity nodes
        // (CHARACTER/PLACE/FACTION/ITEM/REFLECTION — e.g. "Green = favourite colour", "who the player
        // is") still seed retrieval, feed the ego BFS, count toward specificSeedMatches, and surface
        // naturally via the relationship summary in the SYSTEM block. They are deliberately NOT recited
        // here, because dumping the whole tiny graph neighbourhood every turn made the companion fixate
        // on a handful of profile facts and bring them up unnaturally. The per-turn recall block carries
        // ONLY relevant episodic shared-history events. A turn whose hits are all profile nodes yields an
        // empty body; serialize() then injects NOTHING for that turn (the profile fact already shows via
        // the relationship summary) — never a dumped dossier and never a contradicting decline note.
        // Unknown/blank node types are treated as non-episodic and excluded (fail-closed to
        // "not episodic recall").
        List<String> lines = new ArrayList<>();
        if (hits != null && graph != null) {
            for (RetrievalHit hit : hits) {
                MemoryNode node = graph.node(hit.id());
                if (node == null) continue;
                if (!isEpisodic(node)) continue; // profile/entity nodes never recited as per-turn recall
                String line = renderLine(node);
                if (!line.isEmpty()) lines.add(line);
            }
        }
        if (lines.isEmpty()) return "";

        // Reserve cap room for the closed-world footer (footer + its leading blank line) so the
        // anti-fabrication framing is ALWAYS present, never truncated away by a long memory list.
        // The footer is static and author-controlled, so this reservation is byte-stable.
        int footerReserve = HAS_MEMORY_FOOTER.length() + 2; // +2 for the "\n\n" separator before it
        int bodyCap = Math.max(HEADER.length(), cap - footerReserve);

        // Greedily add lines highest-first until the next line would overflow the BODY cap; drop the
        // rest (the lowest-ranked lines). Header + a newline per line are charged against the body cap.
        StringBuilder sb = new StringBuilder(Math.min(cap, 2048));
        sb.append(HEADER);
        for (String line : lines) {
            int projected = sb.length() + 1 + line.length(); // +1 for the newline
            if (projected > bodyCap) break;
            sb.append('\n').append(line);
        }
        // Append the closed-world anti-fabrication footer after a blank line. Whether or not the body
        // hit its reserved cap, the total stays within `cap` because bodyCap = cap - footerReserve.
        sb.append("\n\n").append(HAS_MEMORY_FOOTER);
        // Final defensive clamp (belt-and-suspenders egress re-clamp).
        return MemoryCaps.cap(sb.toString(), cap);
    }

    /**
     * True iff this node is an episodic {@link MemoryNodeType#EVENT} — the only node type recited as
     * per-turn recall (Phase D fixation fix). All other recognized types (CHARACTER/PLACE/FACTION/ITEM/
     * REFLECTION) are stable profile/entity knowledge that surfaces via the relationship summary, not
     * here. An unknown/blank stored {@code type} maps to no recognized type and is treated as
     * non-episodic (fail-closed: never recited as "shared event" recall).
     */
    private static boolean isEpisodic(MemoryNode node) {
        return MemoryNodeType.EVENT == MemoryNodeType.fromWire(node.type());
    }

    /** {@code "- <CanonicalName>: <content>"}, per-line re-clamped. */
    private static String renderLine(MemoryNode node) {
        String name = MemoryCaps.capName(safe(node.canonicalName()));
        String content = MemoryCaps.capContent(safe(node.content()));
        StringBuilder line = new StringBuilder();
        line.append("- ");
        if (!name.isEmpty()) {
            line.append(name);
            if (!content.isEmpty()) line.append(": ");
        }
        line.append(content);
        String rendered = line.toString().trim();
        if (rendered.equals("-")) return "";
        return MemoryCaps.cap(rendered, LINE_CHAR_CAP);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
