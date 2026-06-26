package com.player2.playerengine.retrieval;

import java.util.List;

/**
 * A single retriever in the hybrid retrieval stack: it ranks documents for a goal string
 * and declares the stable {@link RetrieverRegistry} slot its ranks occupy.
 *
 * <p>This interface exists so additional retrievers (W5's graph retriever, a future dense
 * retriever) drop into {@link RrfFusion#fuse(List, int, double)} uniformly. The existing
 * tool / EllieGPS / capability retrieval paths are <em>not</em> rewired through it in W1 —
 * they continue to call the indexes directly and fuse via the legacy 2-list overload.
 */
public interface Retriever {

    /**
     * Returns up to {@code k} ranked hits for {@code goal} (index 0 = highest-ranked).
     * Each hit's rank for this retriever should land in slot {@link #slotIndex()}.
     */
    List<RetrievalHit> query(String goal, int k);

    /** The {@link RetrieverRegistry} slot this retriever's ranks occupy. */
    int slotIndex();
}
