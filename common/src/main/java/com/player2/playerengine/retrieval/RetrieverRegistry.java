package com.player2.playerengine.retrieval;

import java.util.Arrays;

/**
 * Single source of truth for the stable retriever-slot registry used by {@link RrfFusion}
 * and {@link RetrievalHit#retrieverRanks()}.
 *
 * <p>Slots are fixed and additive — a new retriever takes a new slot rather than renumbering
 * the existing ones:
 * <ul>
 *   <li>{@link #SLOT_LEXICAL} = 0 — lexical / BM25 ({@link LexicalIndex} via {@link LexicalRetriever})</li>
 *   <li>{@link #SLOT_MINHASH} = 1 — MinHash ({@link MinHashIndex} via {@link MinHashRetriever})</li>
 *   <li>{@link #SLOT_GRAPH}   = 2 — graph retriever (W5's {@code MemoryRetriever})</li>
 *   <li>{@link #SLOT_DENSE}   = 3 — dense / embeddings retriever (W8, future)</li>
 * </ul>
 *
 * <p>{@link #WIDTH} is the fixed width of every {@link RetrievalHit#retrieverRanks()} array;
 * unused slots carry the absent sentinel {@link Integer#MAX_VALUE} (see {@link #emptyRanks()}).
 */
public final class RetrieverRegistry {

    private RetrieverRegistry() {}

    public static final int SLOT_LEXICAL = 0;
    public static final int SLOT_MINHASH = 1;
    public static final int SLOT_GRAPH   = 2;
    public static final int SLOT_DENSE   = 3;

    /** Fixed number of retriever slots; width of every {@link RetrievalHit#retrieverRanks()}. */
    public static final int WIDTH = 4;

    /** The "absent" rank sentinel — a document not returned by a retriever ranks at this. */
    public static final int ABSENT_RANK = Integer.MAX_VALUE;

    /** A fresh rank array of {@link #WIDTH} slots, all set to {@link #ABSENT_RANK}. */
    public static int[] emptyRanks() {
        int[] r = new int[WIDTH];
        Arrays.fill(r, ABSENT_RANK);
        return r;
    }
}
