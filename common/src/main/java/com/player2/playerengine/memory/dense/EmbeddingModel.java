package com.player2.playerengine.memory.dense;

/**
 * The <b>frozen</b> embedding-model identity for the Phase D dense-retrieval store (W8b).
 *
 * <p>This is a <b>FREEZE, not a default.</b> The {@link #FROZEN} model and {@link #FROZEN_DIMS}
 * dimension count are baked into the {@code vectorModel} token stored on every {@code MemoryNode}
 * and folded into the {@code dense-index.bin} version header
 * ({@link DenseIndexState#modelIdentityToken()}). <b>Changing either value invalidates the entire
 * stored corpus</b> — every persisted vector was produced in a different vector space and is not
 * comparable — and therefore forces a full re-embed (see {@code MemoryStore} backfill/re-embed).
 *
 * <h3>Migration recipe (design-only, not wired to auto-run)</h3>
 * If a future recall-quality playtest proves {@code text-embedding-3-large} is warranted, change
 * {@link #FROZEN} / {@link #FROZEN_DIMS} (and, for large, its 0.15&nbsp;J/1K-token rate into
 * {@link #JOULES_PER_TOKEN}) here. On next load the persisted {@code .bin} version token and every
 * node's {@code vectorModel} no longer match {@link #vectorModelToken()}, so the store discards the
 * {@code .bin} and re-embeds the whole graph into the new space. No other code changes.
 *
 * <p>Freeze reasoning: {@code text-embedding-3-small} @ native 1536 dims is 7.5x cheaper per token
 * and half the storage of {@code large} @ 3072; the dense signal is one <em>fused</em> input among
 * lexical + MinHash + graph, so {@code small} closes the paraphrase gap without justifying large's
 * cost for an entity-linking / episodic-recall workload.
 */
public final class EmbeddingModel {

    private EmbeddingModel() {}

    /** The single frozen embedding model id sent to {@code POST /v1/embeddings}. NEVER omit, NEVER profile-route. */
    public static final String FROZEN = "text-embedding-3-small";

    /**
     * The frozen native dimension count. Because this is the model's native size, the request
     * <b>omits</b> the {@code dimensions} param (no truncation / renormalization).
     */
    public static final int FROZEN_DIMS = 1536;

    /**
     * Per-input-token Joules rate for the frozen model — a <b>single named constant</b> (never a
     * hard-coded literal at a call site). OWNER-ASSERTED (0.02&nbsp;J / 1K tokens for
     * {@code text-embedding-3-small}), <b>not</b> spec-derived; a Player2 pricing change is a
     * one-line edit here. The server deducts Joules itself — the mod reads {@code usage.total_tokens}
     * for telemetry / budget-window tracking only and never branches spend on a per-call estimate.
     */
    public static final double JOULES_PER_TOKEN = 0.00002;

    /**
     * The stable model-identity token stored on each node's {@code vectorModel} and folded into the
     * {@code .bin} version header: {@code "player2:" + FROZEN + ":" + FROZEN_DIMS}
     * (e.g. {@code "player2:text-embedding-3-small:1536"}). This folds <b>model identity only</b> —
     * NOT the graph node set (folding the node set would force a rebuild every load).
     */
    public static String vectorModelToken() {
        return "player2:" + FROZEN + ":" + FROZEN_DIMS;
    }

    /** Joules a call of {@code totalTokens} input tokens is billed (telemetry / budget-window only). */
    public static double joulesFor(int totalTokens) {
        return Math.max(0, totalTokens) * JOULES_PER_TOKEN;
    }
}
