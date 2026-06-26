package com.player2.playerengine.retrieval;

import java.util.List;

/**
 * Thin pure-delegation {@link Retriever} adapter over a {@link MinHashIndex} (slot
 * {@link RetrieverRegistry#SLOT_MINHASH}). Adds no behavior — it forwards
 * {@link #query(String, int)} to the wrapped index so a MinHash retriever can be fused
 * uniformly alongside other {@link Retriever}s.
 */
public final class MinHashRetriever implements Retriever {

    private final MinHashIndex index;

    public MinHashRetriever(MinHashIndex index) {
        this.index = index;
    }

    @Override
    public List<RetrievalHit> query(String goal, int k) {
        return index.query(goal, k);
    }

    @Override
    public int slotIndex() {
        return RetrieverRegistry.SLOT_MINHASH;
    }
}
