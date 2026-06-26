package com.player2.playerengine.memory.resolution;

/**
 * The outcome of resolving a raw entity mention against a companion's {@code MemoryGraph}
 * (Phase D, W4). Immutable value type: no Minecraft, loader, I/O, or LLM dependency.
 *
 * <p>{@link #resolvedNodeId()} is the canonical existing node id when a layer matched, or
 * {@code null} when the resolver decided the mention is a NEW entity (mint a fresh node) or was
 * skipped. {@link #layer()} records which decision path produced the result (provenance for tests,
 * the debug command, and {@code NodeMerger}); {@link #confidence()} is the layer's confidence in
 * {@code [0,1]} (deterministic layers report {@code 1.0} on an exact hit, the edit-distance ratio
 * for fuzzy, the model's reported confidence for a layer-3 confirm); {@link #matchedAlias()} is the
 * graph alias/name the mention matched (or {@code null}).
 */
public final class ResolutionResult {

    /** Which resolution layer produced this result (provenance). */
    public enum ResolutionLayer {
        /** Layer 1 — deterministic exact match on the normalized name/alias key. */
        EXACT_NORMALIZED,
        /** Layer 2 — MinHash LSH candidate accepted by the edit-distance ratio gate (long names). */
        MINHASH_FUZZY,
        /** Layer 3 — bounded patron-gated LLM confirmed an existing node as the referent. */
        LLM_CONFIRMED,
        /** No existing node matched — caller mints a fresh node for this mention. */
        NEW_NODE,
        /** Layer 3 was applicable but skipped (budget cap / gate not enabled) — mint a fresh node. */
        SKIPPED_BUDGET
    }

    private final String resolvedNodeId;   // null = mint new
    private final ResolutionLayer layer;
    private final double confidence;
    private final String matchedAlias;     // nullable

    public ResolutionResult(String resolvedNodeId, ResolutionLayer layer,
                            double confidence, String matchedAlias) {
        this.resolvedNodeId = resolvedNodeId;
        this.layer = layer;
        this.confidence = confidence;
        this.matchedAlias = matchedAlias;
    }

    /** The matched canonical node id, or {@code null} when the caller should mint a new node. */
    public String resolvedNodeId() { return resolvedNodeId; }

    public ResolutionLayer layer() { return layer; }

    public double confidence() { return confidence; }

    /** The graph alias/name the mention matched, or {@code null}. */
    public String matchedAlias() { return matchedAlias; }

    /** True iff an existing node was resolved (vs. a mint-new / skipped decision). */
    public boolean matched() { return resolvedNodeId != null; }

    // ---- Factory helpers (readability at call sites) ------------------------

    static ResolutionResult exact(String nodeId, String matchedAlias) {
        return new ResolutionResult(nodeId, ResolutionLayer.EXACT_NORMALIZED, 1.0, matchedAlias);
    }

    static ResolutionResult fuzzy(String nodeId, double ratio, String matchedAlias) {
        return new ResolutionResult(nodeId, ResolutionLayer.MINHASH_FUZZY, ratio, matchedAlias);
    }

    static ResolutionResult llmConfirmed(String nodeId, double confidence, String matchedAlias) {
        return new ResolutionResult(nodeId, ResolutionLayer.LLM_CONFIRMED, confidence, matchedAlias);
    }

    static ResolutionResult newNode() {
        return new ResolutionResult(null, ResolutionLayer.NEW_NODE, 0.0, null);
    }

    static ResolutionResult skippedBudget() {
        return new ResolutionResult(null, ResolutionLayer.SKIPPED_BUDGET, 0.0, null);
    }

    @Override
    public String toString() {
        return "ResolutionResult{nodeId=" + resolvedNodeId + ", layer=" + layer
                + ", confidence=" + String.format(java.util.Locale.ROOT, "%.3f", confidence)
                + ", matchedAlias=" + matchedAlias + "}";
    }
}
