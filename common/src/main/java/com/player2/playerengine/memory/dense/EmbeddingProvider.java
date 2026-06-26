package com.player2.playerengine.memory.dense;

import java.util.Optional;

/**
 * W8 dense-embedding seam — <b>no-op in v1</b>. The dense retrieval stack
 * ({@code DenseVectorRetriever}, {@code DenseIndex}) is designed but not built; this provider
 * exists so the {@code MemoryNode.vector}/{@code vectorModel} slot and the
 * {@link com.player2.playerengine.retrieval.RetrieverRegistry#SLOT_DENSE} slot have a typed
 * home to bind against.
 *
 * <p>v1 hard-returns "no embeddings endpoint" from {@link #unavailable()} — there is no
 * embeddings endpoint wired, so no node ever receives a vector and the dense slot stays empty.
 * When dense lands, an implementation produces vectors and a model token; until then every call
 * site must treat embeddings as unavailable and degrade to the lexical/MinHash/graph retrievers.
 */
public final class EmbeddingProvider {

    /** Stable reason token surfaced when dense embeddings are not available. */
    public static final String UNAVAILABLE_REASON = "no embeddings endpoint";

    private EmbeddingProvider() {}

    /**
     * Always reports dense embeddings as unavailable in v1, carrying the stable
     * {@link #UNAVAILABLE_REASON} token. Callers branch on this to skip the dense path entirely.
     *
     * @return the (always-present in v1) unavailability reason
     */
    public static Optional<String> unavailable() {
        return Optional.of(UNAVAILABLE_REASON);
    }

    /** Convenience predicate: dense embeddings are never available in v1. */
    public static boolean isAvailable() {
        return false;
    }
}
