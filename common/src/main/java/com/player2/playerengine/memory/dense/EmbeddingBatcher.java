package com.player2.playerengine.memory.dense;

import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.PlayerEngineController;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch chunking for {@link EmbeddingProvider#embed} (W8f).
 *
 * <p>The {@code /v1/embeddings} endpoint caps a request at <b>100 inputs</b> and <b>8192
 * tokens/input</b>. This batcher:
 * <ul>
 *   <li><b>Splits</b> any input set into {@code <= }{@link #MAX_BATCH} chunks, issues one
 *       {@link EmbeddingProvider#embed} call per chunk, and <b>stitches results back in the original
 *       input order</b> ({@code out[i]} corresponds to {@code inputs.get(i)}).</li>
 *   <li><b>Char-length pre-guards</b> each input (no tokenizer library): an input longer than
 *       {@link #MAX_EMBED_CHARS} is <b>truncated</b> (never split across vectors). Node embeddable
 *       text (name + type + a bounded attribute/summary line) is short, so this backstop effectively
 *       never trips; the 400 path in {@link EmbeddingProvider} is the real safety net.</li>
 * </ul>
 *
 * <p>If <b>any</b> chunk degrades, the whole batch degrades (returns the first chunk's bounded token
 * with no vectors) — a partially-embedded batch is not stitched, so callers see an all-or-nothing
 * result and simply leave nodes vectorless until the next pass. Off-tick: this must run on
 * {@code MemoryLlmClient.MEMORY_EXECUTOR} (each underlying {@link EmbeddingProvider#embed} hard-guards
 * off-tick anyway).
 */
public final class EmbeddingBatcher {

    private EmbeddingBatcher() {}

    /** Hard cap on inputs per {@code /v1/embeddings} request (endpoint limit). */
    public static final int MAX_BATCH = 100;

    /**
     * Conservative per-input char cap (~4 chars/token → ~6000 tokens, a deliberate margin under the
     * 8192-token/input limit). Truncate, never split, on overflow.
     */
    public static final int MAX_EMBED_CHARS = 24_000;

    /**
     * Embeds all {@code inputs} in {@code <=}{@link #MAX_BATCH}-item chunks and returns one vector per
     * input in <b>input order</b>. Char-guards each input first. All-or-nothing: any chunk degradation
     * degrades the whole result.
     *
     * @param inputs     the texts to embed (order preserved in the result)
     * @param controller the companion controller (billing/route context)
     * @param billing    the OWNER's resolved billing context (never the prompter's)
     * @return an {@link EmbeddingResult}: {@code ok} with {@code vectors[i]} for {@code inputs.get(i)},
     *         or {@code degraded} carrying a bounded token when any chunk fails
     */
    public static EmbeddingResult embedAll(List<String> inputs,
                                           PlayerEngineController controller,
                                           Player2PayerResolution.ApiBillingContext billing) {
        if (inputs == null || inputs.isEmpty()) {
            return EmbeddingResult.ok(new float[0][], EmbeddingModel.vectorModelToken(), 0);
        }

        // Char-guard once, preserving order.
        List<String> guarded = new ArrayList<>(inputs.size());
        for (String s : inputs) {
            guarded.add(guard(s));
        }

        float[][] out = new float[guarded.size()][];
        int totalTokens = 0;

        for (int start = 0; start < guarded.size(); start += MAX_BATCH) {
            int end = Math.min(start + MAX_BATCH, guarded.size());
            List<String> chunk = guarded.subList(start, end);
            EmbeddingResult r = EmbeddingProvider.embed(chunk, controller, billing);
            if (!r.ok()) {
                // All-or-nothing: propagate the bounded degradation token, drop any partial vectors.
                return r;
            }
            float[][] chunkVecs = r.vectors();
            // Stitch back in input order: chunk row j maps to global index start + j.
            for (int j = 0; j < chunkVecs.length && (start + j) < out.length; j++) {
                out[start + j] = chunkVecs[j];
            }
            totalTokens += r.totalTokens();
        }
        return EmbeddingResult.ok(out, EmbeddingModel.vectorModelToken(), totalTokens);
    }

    /** Truncates an input to {@link #MAX_EMBED_CHARS} (never splits); null → empty string. */
    static String guard(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_EMBED_CHARS) return s;
        return s.substring(0, MAX_EMBED_CHARS);
    }
}
