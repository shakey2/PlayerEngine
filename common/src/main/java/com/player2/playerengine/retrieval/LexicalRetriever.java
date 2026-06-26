package com.player2.playerengine.retrieval;

import java.util.List;

/**
 * Thin pure-delegation {@link Retriever} adapter over a {@link LexicalIndex} (slot
 * {@link RetrieverRegistry#SLOT_LEXICAL}). Adds no behavior — it simply forwards
 * {@link #query(String, int)} to the wrapped index so a lexical retriever can be fused
 * uniformly alongside other {@link Retriever}s.
 */
public final class LexicalRetriever implements Retriever {

    private final LexicalIndex index;

    public LexicalRetriever(LexicalIndex index) {
        this.index = index;
    }

    @Override
    public List<RetrievalHit> query(String goal, int k) {
        return index.query(goal, k);
    }

    @Override
    public int slotIndex() {
        return RetrieverRegistry.SLOT_LEXICAL;
    }
}
