package com.player2.playerengine.retrieval;

/**
 * Shared query tokenization for retrieval confidence (same rules as {@link LexicalIndex}).
 */
public final class RetrievalTokenizer {

    private RetrievalTokenizer() {}

    public static String[] tokenize(String text) {
        return LexicalIndex.tokenize(text);
    }
}
