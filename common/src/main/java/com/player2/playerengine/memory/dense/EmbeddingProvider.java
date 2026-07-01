package com.player2.playerengine.memory.dense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.Player2ApiDispatcher;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.utils.HttpApiException;
import com.player2.playerengine.util.Debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * W8 dense-embedding provider — the real {@code POST /v1/embeddings} caller (W8a).
 *
 * <p>Exposes {@link #embed(List, PlayerEngineController, Player2PayerResolution.ApiBillingContext)},
 * which returns one embedding per input <b>in input order</b> ({@code out[i]} ↔ {@code inputs.get(i)},
 * {@code data[i].index} authoritative), or an {@link EmbeddingResult#degraded(String) degraded} result
 * carrying a short bounded token and NO vectors so callers degrade to lexical / MinHash / graph fusion.
 *
 * <h3>Egress hard rule (release blocker)</h3>
 * The {@code catch} block emits <b>ONLY</b> the fixed bounded token strings; the raw
 * {@code e.getMessage()} / body / stack trace goes to {@link Debug#logWarning} <b>console only</b> and
 * NEVER into any model-facing surface (InfoMessage, command feedback, conversation history, status JSON,
 * any Player2 request). See DESIGN.md §3 and plan A.8.
 *
 * <h3>Off-tick hard guard (structural, not test-only)</h3>
 * {@link #embed} throws {@link IllegalStateException} if invoked on the MC server thread
 * ({@code "Server thread"}). No embed call / base64 decode / cosine scan / {@code .bin} write may run on
 * the tick — compute runs on {@code MemoryLlmClient.MEMORY_EXECUTOR}.
 *
 * <h3>Auth / energy retry is NOT re-implemented here</h3>
 * The shared {@code Player2HTTPUtils.sendRequest} underneath {@code route(...)} already fires
 * {@code AuthenticationManager.invalidateToken} on 401 and the one-shot 402 reauth-retry +
 * {@code JoulesCache.invalidate}. This provider maps the resulting typed status to a bounded token
 * <b>only</b> — it must NOT re-run those side effects, and must NEVER call
 * {@code setProfileBaseUrlOverride} / {@code sendChatCompletionRequest} (that path is profile-routed +
 * chat-billing shaped). The call uses the ABSOLUTE {@code "/v1/embeddings"} path at the default
 * discovery URL (explicit-model form, never profile-routed — ties the vector space to a user profile).
 */
public final class EmbeddingProvider {

    /** The MC server-thread name in both 1.20.1 and 1.21.1 (MinecraftServer.spin(...)). */
    private static final String SERVER_THREAD_NAME = "Server thread";

    /** Bounded degradation tokens (model-facing safe; see plan A.8). */
    static final String TOKEN_REQUEST_INVALID    = "embedding_request_invalid";
    static final String TOKEN_AUTH_REQUIRED       = "embedding_auth_required";
    static final String TOKEN_INSUFFICIENT_JOULES = "embedding_insufficient_joules";
    static final String TOKEN_RATE_LIMITED        = "embedding_rate_limited";
    static final String TOKEN_SERVER_ERROR        = "embedding_server_error";

    private EmbeddingProvider() {}

    /**
     * Dense embeddings are available in this build (a real endpoint is wired). NOTE: flipping this true
     * enables NOTHING on its own — every consumer branch (the retriever seed list, the reranker cosine
     * swap, on-ingest embed, backfill) is separately written and gates on a successful {@link #embed}.
     */
    public static boolean isAvailable() {
        return true;
    }

    /** Optional unavailability reason — empty when {@link #isAvailable()} (the real path). */
    public static Optional<String> unavailable() {
        return Optional.empty();
    }

    /**
     * Embeds {@code inputs} via {@code POST /v1/embeddings}, in input order. On any failure returns an
     * {@link EmbeddingResult#degraded} carrying only a short bounded token (see class doc).
     *
     * @param inputs     texts to embed (order preserved; callers should pre-chunk via {@link EmbeddingBatcher})
     * @param controller the companion controller (route + billing context)
     * @param billing    the OWNER's resolved billing context (never the prompter's)
     */
    public static EmbeddingResult embed(List<String> inputs,
                                        PlayerEngineController controller,
                                        Player2PayerResolution.ApiBillingContext billing) {
        assertOffTick();

        if (inputs == null || inputs.isEmpty()) {
            return EmbeddingResult.ok(new float[0][], EmbeddingModel.vectorModelToken(), 0);
        }
        if (controller == null || billing == null || billing.billingKey() == null) {
            // No route/billing context → degrade silently (console only); callers fall back to lexical/graph.
            Debug.logWarning("Embedding call skipped: no route/billing context.");
            return EmbeddingResult.degraded(TOKEN_AUTH_REQUIRED);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", EmbeddingModel.FROZEN);          // EXPLICIT — never omit, never profile
        body.addProperty("encoding_format", "base64");             // LE f32, decode w/o library
        // FROZEN_DIMS is native => do NOT send "dimensions" (native = no truncation/renorm).
        JsonArray in = new JsonArray();
        inputs.forEach(in::add);
        body.add("input", in);

        String clientId = Player2ServerConfigHolder.get().getHeartbeatClientId();

        try {
            // route(...) takes the ABSOLUTE path incl. "/v1" (no double-prefix). The shared
            // Player2HTTPUtils.sendRequest underneath ALREADY handles 401 invalidateToken +
            // 402 reauth-retry + JoulesCache.invalidate — do NOT re-implement any of that here.
            Map<String, JsonElement> resp = Player2ApiDispatcher.route(
                    controller, clientId, "POST", "/v1/embeddings", body, billing);

            // Defensive: a spec-conformant 200 always carries a "data" array — guard explicitly rather
            // than relying on the generic catch for a malformed 200 (makes the degrade intent clear).
            JsonElement dataEl = resp.get("data");
            if (dataEl == null || !dataEl.isJsonArray()) {
                Debug.logWarning("Embedding response missing 'data' array.");
                return EmbeddingResult.degraded(TOKEN_SERVER_ERROR);
            }
            JsonArray data = dataEl.getAsJsonArray();               // in input order
            float[][] vecs = new float[data.size()][];
            for (JsonElement e : data) {
                JsonObject o = e.getAsJsonObject();
                int idx = o.get("index").getAsInt();               // 0-based, authoritative ordering
                if (idx < 0 || idx >= vecs.length) {
                    // Defensive: an out-of-range index is a schema violation — degrade, never index-crash.
                    Debug.logWarning("Embedding response index out of range: %s (size %s).", idx, vecs.length);
                    return EmbeddingResult.degraded(TOKEN_SERVER_ERROR);
                }
                vecs[idx] = decodeBase64LEf32(o.get("embedding").getAsString());
            }
            // Defensive: "usage" is telemetry only; a missing/malformed usage block degrades the token
            // count to 0 (metering-only) rather than NPE-ing into the generic catch and dropping vectors.
            int totalTokens = 0;
            JsonElement usageEl = resp.get("usage");
            if (usageEl != null && usageEl.isJsonObject()) {
                JsonElement totEl = usageEl.getAsJsonObject().get("total_tokens");
                if (totEl != null && totEl.isJsonPrimitive()) {
                    totalTokens = totEl.getAsInt();
                }
            }
            return EmbeddingResult.ok(vecs, EmbeddingModel.vectorModelToken(), totalTokens);

        } catch (HttpApiException e) {
            // EGRESS HARD RULE: emit ONLY the fixed bounded token; log the raw message to CONSOLE ONLY.
            String token;
            switch (e.getStatusCode()) {
                case 400 -> token = TOKEN_REQUEST_INVALID;
                case 401 -> token = TOKEN_AUTH_REQUIRED;        // shared layer already invalidated the token
                case 402 -> token = TOKEN_INSUFFICIENT_JOULES;  // shared layer already did the reauth-retry
                case 429 -> token = TOKEN_RATE_LIMITED;
                default  -> token = TOKEN_SERVER_ERROR;         // 500 + anything unmapped
            }
            Debug.logWarning("Embedding call failed (status %s): %s", e.getStatusCode(), e.getMessage());
            return EmbeddingResult.degraded(token);             // NO vectors -> callers degrade

        } catch (Exception e) {
            // Non-HTTP failure (network, malformed JSON, missing keys). Bounded token; raw to console only.
            Debug.logWarning("Embedding call failed (%s).", e.getClass().getSimpleName());
            return EmbeddingResult.degraded(TOKEN_SERVER_ERROR);
        }
    }

    /**
     * Decodes a base64 string of little-endian f32 bytes into a {@code float[]} — no third-party
     * dependency (JDK {@link Base64} + {@link ByteBuffer}).
     */
    static float[] decodeBase64LEf32(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /**
     * Structural off-tick guard: throws if called on the MC server thread. No embed / decode / cosine /
     * {@code .bin} write may run on the tick (must be on {@code MemoryLlmClient.MEMORY_EXECUTOR}).
     */
    static void assertOffTick() {
        if (SERVER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            throw new IllegalStateException(
                    "EmbeddingProvider.embed must not run on the server tick thread (off-tick only).");
        }
    }
}
