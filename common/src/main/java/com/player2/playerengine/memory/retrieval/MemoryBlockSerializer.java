package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;
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
     * {@link BoundaryVerdict#HAS_MEMORY} block. Static, bounded, author-controlled — it frames the
     * listed facts as the COMPLETE set so the model cannot treat a partial match (e.g. the
     * always-present self/owner nodes) as license to agree to a fabricated event. Kept byte-stable
     * (prefix-cache) and counted within the bounded block: {@link #buildHasMemory} reserves cap room
     * for it so the whole block never exceeds the char cap (DESIGN.md §3 egress bound).
     */
    public static final String HAS_MEMORY_FOOTER =
            "These are the ONLY things you remember about this player and your shared history. "
            + "If the player refers to a specific event, person, place, or fact that is NOT listed "
            + "above, you do NOT remember it — say so honestly and do not invent, agree to, or play "
            + "along with a memory that is not here.";

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
                // An empty body (no resolvable hits) degrades to the decline note rather than an
                // empty header — never claim memory the block does not actually carry.
                return Optional.of(block.isEmpty() ? NO_MEMORY_NOTE : block);
        }
    }

    private static String buildHasMemory(List<RetrievalHit> hits, MemoryGraph graph, int blockCharCap) {
        int cap = blockCharCap > 0 ? blockCharCap : DEFAULT_BLOCK_CHAR_CAP;

        // Render one line per hit, in fused (highest-first) order.
        List<String> lines = new ArrayList<>();
        if (hits != null && graph != null) {
            for (RetrievalHit hit : hits) {
                MemoryNode node = graph.node(hit.id());
                if (node == null) continue;
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
