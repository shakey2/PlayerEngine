package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.retrieval.MemoryRetrievalConfidence.BoundaryVerdict;
import com.player2.playerengine.retrieval.RetrievalHit;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Immutable result of one zero-LLM memory-retrieval pass (Phase D, W5).
 *
 * <p>Carries the ranked {@code hits} (the episodic-reranked subgraph), the deterministic
 * {@code confidence}, the knowledge-boundary {@link BoundaryVerdict}, and the already-serialized,
 * hard-capped {@code memoryBlock} that the turn-assembly caller injects at the message <b>tail</b>:
 * <ul>
 *   <li>{@link BoundaryVerdict#HAS_MEMORY} → {@code memoryBlock} present, a {@code "[Memory] …"} block.</li>
 *   <li>{@link BoundaryVerdict#NO_MEMORY} → {@code memoryBlock} present, a templated decline note
 *       (truthfulness lever: instructs the model to decline, not fabricate).</li>
 *   <li>{@link BoundaryVerdict#STORE_ABSENT} → {@code memoryBlock} <b>empty</b>; the caller injects
 *       nothing, keeping the request byte-identical to a pre-W5 build (prefix-cache + zero-cost).</li>
 * </ul>
 *
 * <p>No Minecraft / loader / I/O dependency.
 */
public final class MemoryRetrievalResult {

    private final List<RetrievalHit> hits;
    private final double confidence;
    private final BoundaryVerdict verdict;
    private final Optional<String> memoryBlock;

    public MemoryRetrievalResult(List<RetrievalHit> hits,
                                 double confidence,
                                 BoundaryVerdict verdict,
                                 Optional<String> memoryBlock) {
        this.hits = hits == null ? List.of() : Collections.unmodifiableList(List.copyOf(hits));
        this.confidence = confidence;
        this.verdict = verdict;
        this.memoryBlock = memoryBlock == null ? Optional.empty() : memoryBlock;
    }

    /** The store-absent / disabled result: empty hits, zero confidence, no block. */
    public static MemoryRetrievalResult storeAbsent() {
        return new MemoryRetrievalResult(List.of(), 0.0, BoundaryVerdict.STORE_ABSENT, Optional.empty());
    }

    /** The ranked subgraph hits (empty for NO_MEMORY/STORE_ABSENT). Read-only. */
    public List<RetrievalHit> hits() { return hits; }

    /** The deterministic confidence in {@code [0,1]}. */
    public double confidence() { return confidence; }

    /** The knowledge-boundary verdict. */
    public BoundaryVerdict verdict() { return verdict; }

    /**
     * The serialized, hard-capped tail block to inject (empty when {@link BoundaryVerdict#STORE_ABSENT}).
     * The turn-assembly caller threads this straight into
     * {@code ConversationHistory.copyThenWrapLatestWithStatus(..., memoryBlock)}.
     */
    public Optional<String> memoryBlock() { return memoryBlock; }

    @Override
    public String toString() {
        return "MemoryRetrievalResult{verdict=" + verdict + ", confidence="
                + String.format("%.3f", confidence) + ", hits=" + hits.size()
                + ", hasBlock=" + memoryBlock.isPresent() + "}";
    }
}
