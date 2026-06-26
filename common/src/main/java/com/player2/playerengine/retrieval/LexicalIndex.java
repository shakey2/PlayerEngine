package com.player2.playerengine.retrieval;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure-Java BM25 inverted index over {@link ToolDocument#indexedText()}.
 *
 * <p>BM25 parameters: k1 = 1.2, b = 0.75.<br>
 * Tokenizer: lowercase → split on {@code [^a-z0-9]+} → discard tokens shorter than 2 chars.
 *
 * <p>No external dependencies. Index state is serializable for persistence via
 * {@link ToolRetriever}.
 */
public final class LexicalIndex implements Retriever {

    private static final double K1 = 1.2;
    private static final double B  = 0.75;
    private static final Pattern SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int MIN_TOKEN_LEN = 2;

    // -------------------------------------------------------------------------
    // Serializable state — held separately so ToolRetriever can persist it
    // -------------------------------------------------------------------------

    /** All index state needed to rebuild the index on load or persist it. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final String[] docIds;
        final int[] docLengths;
        final double avgDocLength;
        /** token → list of int[2] = [docIndex, termFrequency] */
        final HashMap<String, List<int[]>> postings;

        State(String[] docIds, int[] docLengths, double avgDocLength,
              HashMap<String, List<int[]>> postings) {
            this.docIds       = docIds;
            this.docLengths   = docLengths;
            this.avgDocLength = avgDocLength;
            this.postings     = postings;
        }
    }

    private final State state;
    /** docId → array index, for fast lookup. Not persisted; rebuilt from docIds. */
    private final Map<String, Integer> docIndexMap;

    private LexicalIndex(State state) {
        this.state = state;
        this.docIndexMap = new HashMap<>(state.docIds.length * 2);
        for (int i = 0; i < state.docIds.length; i++) {
            docIndexMap.put(state.docIds[i], i);
        }
    }

    /** Builds a new {@link LexicalIndex} from the given documents. */
    public static LexicalIndex build(Collection<ToolDocument> documents) {
        List<ToolDocument> docs = new ArrayList<>(documents);
        int n = docs.size();
        String[] docIds     = new String[n];
        int[]    docLengths = new int[n];
        HashMap<String, List<int[]>> postings = new HashMap<>();

        for (int i = 0; i < n; i++) {
            ToolDocument doc = docs.get(i);
            docIds[i] = doc.id();
            String[] tokens = tokenize(doc.indexedText());
            docLengths[i]   = tokens.length;

            // Count term frequencies per document
            Map<String, Integer> tf = new LinkedHashMap<>();
            for (String t : tokens) tf.merge(t, 1, Integer::sum);

            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new int[]{i, e.getValue()});
            }
        }

        double totalLen = 0;
        for (int l : docLengths) totalLen += l;
        double avgDocLength = n == 0 ? 0.0 : totalLen / n;

        return new LexicalIndex(new State(docIds, docLengths, avgDocLength, postings));
    }

    /** Reconstructs a {@link LexicalIndex} from previously persisted {@link State}. */
    static LexicalIndex fromState(State state) {
        return new LexicalIndex(state);
    }

    State getState() { return state; }

    /**
     * Queries the index and returns up to {@code topK} hits sorted by BM25 score (descending).
     * Query tokens not present in any document contribute a score of zero and are skipped.
     */
    public List<RetrievalHit> query(String text, int topK) {
        String[] queryTokens = tokenize(text);
        if (queryTokens.length == 0 || state.docIds.length == 0) {
            return Collections.emptyList();
        }

        int n = state.docIds.length;
        double[] scores = new double[n];

        for (String token : queryTokens) {
            List<int[]> posting = state.postings.get(token);
            if (posting == null) continue;
            int df = posting.size();
            // Lucene-style IDF: always positive
            double idf = Math.log(1.0 + (double)(n - df + 0.5) / (df + 0.5));
            for (int[] p : posting) {
                int docIdx = p[0];
                int tf     = p[1];
                double normFactor = (double) state.docLengths[docIdx] / state.avgDocLength;
                double tfNorm = (double) tf * (K1 + 1.0)
                        / (tf + K1 * (1.0 - B + B * normFactor));
                scores[docIdx] += idf * tfNorm;
            }
        }

        // Collect non-zero scoring documents and sort descending
        List<IndexedScore> sortable = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0.0) sortable.add(new IndexedScore(i, scores[i]));
        }
        sortable.sort((a, b) -> Double.compare(b.score, a.score));

        List<RetrievalHit> hits = new ArrayList<>();
        int limit = Math.min(topK, sortable.size());
        for (int r = 0; r < limit; r++) {
            IndexedScore is = sortable.get(r);
            hits.add(new RetrievalHit(state.docIds[is.idx], is.score, r + 1, Integer.MAX_VALUE));
        }
        return hits;
    }

    /** {@link Retriever} slot: lexical/BM25 = {@link RetrieverRegistry#SLOT_LEXICAL}. */
    @Override
    public int slotIndex() {
        return RetrieverRegistry.SLOT_LEXICAL;
    }

    /** Tokenizes text: lowercase → split on non-alphanumeric → discard short tokens. */
    static String[] tokenize(String text) {
        String lower = text.toLowerCase();
        String[] parts = SPLIT.split(lower);
        List<String> tokens = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.length() >= MIN_TOKEN_LEN) tokens.add(p);
        }
        return tokens.toArray(new String[0]);
    }

    private static final class IndexedScore {
        final int idx;
        final double score;
        IndexedScore(int idx, double score) { this.idx = idx; this.score = score; }
    }
}
