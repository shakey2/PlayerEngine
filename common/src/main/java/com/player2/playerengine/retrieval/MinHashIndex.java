package com.player2.playerengine.retrieval;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Character 4-gram MinHash index with LSH bucketing for approximate Jaccard similarity
 * retrieval.
 *
 * <p>Parameters: 128 hash functions, 32 bands × 4 rows per band.
 * Hash coefficients are generated from a fixed seed ({@code 0xDEADBEEFL}) and are
 * therefore deterministic — they do not need to be persisted.
 *
 * <p>The {@link State} that <em>is</em> persisted holds only the MinHash signatures and
 * document IDs. Band tables are rebuilt in-memory on construction from the signatures.
 *
 * <p>LSH threshold (probability ≈ 0.5 at Jaccard ≈ (1/32)^(1/4) ≈ 0.42).
 */
public final class MinHashIndex implements Retriever {

    private static final int NUM_HASHES     = 128;
    private static final int NUM_BANDS      = 32;
    private static final int ROWS_PER_BAND  = NUM_HASHES / NUM_BANDS;  // 4
    private static final int NGRAM_SIZE     = 4;
    private static final int MERSENNE_P     = 0x7FFFFFFF; // 2^31 - 1

    // Deterministic universal hash function coefficients generated once from a fixed seed.
    private static final int[] HASH_A = new int[NUM_HASHES];
    private static final int[] HASH_B = new int[NUM_HASHES];

    static {
        Random rng = new Random(0xDEADBEEFL);
        for (int i = 0; i < NUM_HASHES; i++) {
            // Ensure a_i >= 1 and fits in a non-negative int
            HASH_A[i] = (rng.nextInt(MERSENNE_P - 1) + 1) & 0x7FFFFFFF;
            HASH_B[i] = rng.nextInt(MERSENNE_P) & 0x7FFFFFFF;
        }
    }

    // -------------------------------------------------------------------------
    // Serializable state
    // -------------------------------------------------------------------------

    /** Signatures and document IDs — sufficient to reconstruct band tables on load. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final String[] docIds;
        final int[][] signatures; // [docIndex][NUM_HASHES]

        State(String[] docIds, int[][] signatures) {
            this.docIds     = docIds;
            this.signatures = signatures;
        }
    }

    private final State state;
    /** LSH band tables: [band] → (bucketKey → list of docIndices). Rebuilt from signatures. */
    private final List<HashMap<Long, List<Integer>>> bandTables;

    private MinHashIndex(State state) {
        this.state      = state;
        this.bandTables = buildBandTables(state.signatures);
    }

    /** Builds a new {@link MinHashIndex} from the given documents. */
    public static MinHashIndex build(Collection<ToolDocument> documents) {
        List<ToolDocument> docs = new ArrayList<>(documents);
        int n = docs.size();
        String[] docIds    = new String[n];
        int[][] signatures = new int[n][NUM_HASHES];

        for (int i = 0; i < n; i++) {
            docIds[i]     = docs.get(i).id();
            Set<Integer> ngrams = extractNgrams(docs.get(i).indexedText());
            signatures[i] = computeSignature(ngrams);
        }
        return new MinHashIndex(new State(docIds, signatures));
    }

    /** Reconstructs a {@link MinHashIndex} from previously persisted {@link State}. */
    static MinHashIndex fromState(State state) {
        return new MinHashIndex(state);
    }

    State getState() { return state; }

    /**
     * Returns up to {@code topK} hits ranked by approximate Jaccard similarity.
     * Candidates are found via LSH bands; ranking uses the fraction of matching
     * signature positions as an estimator of Jaccard.
     */
    public List<RetrievalHit> query(String text, int topK) {
        Set<Integer> queryNgrams = extractNgrams(text);
        if (queryNgrams.isEmpty() || state.docIds.length == 0) {
            return new ArrayList<>();
        }
        int[] querySig = computeSignature(queryNgrams);

        // Gather candidates from LSH bands
        Set<Integer> candidates = new HashSet<>();
        for (int band = 0; band < NUM_BANDS; band++) {
            long key = bandHash(querySig, band * ROWS_PER_BAND);
            List<Integer> bucket = bandTables.get(band).get(key);
            if (bucket != null) candidates.addAll(bucket);
        }

        // Rank candidates by signature agreement (estimated Jaccard)
        List<int[]> ranked = new ArrayList<>(candidates.size());
        for (int docIdx : candidates) {
            int matches = 0;
            for (int h = 0; h < NUM_HASHES; h++) {
                if (state.signatures[docIdx][h] == querySig[h]) matches++;
            }
            ranked.add(new int[]{docIdx, matches});
        }
        ranked.sort((a, b) -> Integer.compare(b[1], a[1]));

        List<RetrievalHit> hits = new ArrayList<>();
        int limit = Math.min(topK, ranked.size());
        for (int r = 0; r < limit; r++) {
            int docIdx          = ranked.get(r)[0];
            double approxJaccard = (double) ranked.get(r)[1] / NUM_HASHES;
            hits.add(new RetrievalHit(state.docIds[docIdx], approxJaccard,
                    Integer.MAX_VALUE, r + 1));
        }
        return hits;
    }

    /** {@link Retriever} slot: MinHash = {@link RetrieverRegistry#SLOT_MINHASH}. */
    @Override
    public int slotIndex() {
        return RetrieverRegistry.SLOT_MINHASH;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static Set<Integer> extractNgrams(String text) {
        String lower  = text.toLowerCase();
        Set<Integer> ngrams = new HashSet<>();
        int len = lower.length();
        for (int i = 0; i <= len - NGRAM_SIZE; i++) {
            ngrams.add(lower.substring(i, i + NGRAM_SIZE).hashCode());
        }
        return ngrams;
    }

    private static int[] computeSignature(Set<Integer> ngrams) {
        int[] sig = new int[NUM_HASHES];
        for (int h = 0; h < NUM_HASHES; h++) sig[h] = Integer.MAX_VALUE;

        for (int ngram : ngrams) {
            // Treat ngram as unsigned 32-bit value for the universal hash
            long x = ngram & 0xFFFFFFFFL;
            for (int h = 0; h < NUM_HASHES; h++) {
                int hashVal = (int) (((long) HASH_A[h] * x + HASH_B[h]) % MERSENNE_P);
                if (hashVal < sig[h]) sig[h] = hashVal;
            }
        }
        return sig;
    }

    private static List<HashMap<Long, List<Integer>>> buildBandTables(int[][] signatures) {
        List<HashMap<Long, List<Integer>>> bands = new ArrayList<>(NUM_BANDS);
        for (int b = 0; b < NUM_BANDS; b++) bands.add(new HashMap<>());

        for (int docIdx = 0; docIdx < signatures.length; docIdx++) {
            for (int band = 0; band < NUM_BANDS; band++) {
                long key = bandHash(signatures[docIdx], band * ROWS_PER_BAND);
                bands.get(band).computeIfAbsent(key, k -> new ArrayList<>()).add(docIdx);
            }
        }
        return bands;
    }

    /** Polynomial hash of {@code ROWS_PER_BAND} consecutive signature elements. */
    private static long bandHash(int[] sig, int start) {
        long h = 1L;
        for (int i = 0; i < ROWS_PER_BAND; i++) {
            h = h * 31L + sig[start + i];
        }
        return h;
    }
}
