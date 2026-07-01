package com.player2.playerengine.memory.dense;

/**
 * The result of an {@link EmbeddingProvider#embed} call (W8a).
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@link #ok(float[][], String, int)} — success: {@code vectors[i]} corresponds to
 *       {@code inputs.get(i)} (input order, {@code data[i].index} authoritative), plus the produced
 *       model token and {@code usage.total_tokens} for budget-window metering.</li>
 *   <li>{@link #degraded(String)} — failure: <b>no vectors</b> and a <b>short, bounded degradation
 *       token</b> (never a raw error body / message / stack — DESIGN.md §3 egress hard rule). Callers
 *       degrade to lexical / MinHash / graph fusion (byte-identical to the pre-W8 path).</li>
 * </ul>
 *
 * <p>The degradation token is one of the fixed strings the provider's status map emits (see A.8):
 * {@code embedding_request_invalid} / {@code embedding_auth_required} /
 * {@code embedding_insufficient_joules} / {@code embedding_rate_limited} / {@code embedding_server_error}.
 * It is safe to surface to a model-facing feedback surface because it is a curated, bounded phrase.
 */
public final class EmbeddingResult {

    private final float[][] vectors;
    private final String vectorModel;
    private final int totalTokens;
    private final String degradedToken;

    private EmbeddingResult(float[][] vectors, String vectorModel, int totalTokens, String degradedToken) {
        this.vectors = vectors;
        this.vectorModel = vectorModel;
        this.totalTokens = totalTokens;
        this.degradedToken = degradedToken;
    }

    /**
     * Success result. {@code vectors[i]} is the embedding for {@code inputs.get(i)} (input order).
     *
     * @param vectors     one row per input, in input order
     * @param vectorModel the produced model-identity token ({@link EmbeddingModel#vectorModelToken()})
     * @param totalTokens {@code usage.total_tokens} (telemetry / budget-window metering)
     */
    public static EmbeddingResult ok(float[][] vectors, String vectorModel, int totalTokens) {
        return new EmbeddingResult(vectors, vectorModel, Math.max(0, totalTokens), null);
    }

    /**
     * Degraded result carrying a short bounded token and NO vectors. The token is the ONLY thing that
     * may reach a model-facing surface; the raw cause is logged to console only by the provider.
     */
    public static EmbeddingResult degraded(String boundedToken) {
        return new EmbeddingResult(null, null, 0, boundedToken);
    }

    /** True iff this result carries vectors (a successful call). */
    public boolean ok() {
        return vectors != null && degradedToken == null;
    }

    /** The embeddings in input order (never null on {@link #ok()}); null on a degraded result. */
    public float[][] vectors() {
        return vectors;
    }

    /** The produced model-identity token on success; null on a degraded result. */
    public String vectorModel() {
        return vectorModel;
    }

    /** {@code usage.total_tokens} on success (0 on a degraded result). */
    public int totalTokens() {
        return totalTokens;
    }

    /** Joules this call was billed (telemetry / budget-window only); 0 on a degraded result. */
    public double joules() {
        return EmbeddingModel.joulesFor(totalTokens);
    }

    /** The short bounded degradation token on failure; null on success. Safe for model-facing feedback. */
    public String degradedToken() {
        return degradedToken;
    }
}
