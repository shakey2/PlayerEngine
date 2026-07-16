package com.player2.playerengine.memory.budget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.LogEgressGuard;
import com.player2.playerengine.player2api.ModelTierRouter;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.RoutingDecision;
import com.player2.playerengine.player2api.BudgetThresholdsResolver;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.utils.HTTPUtils;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The SOLE sanctioned {@code /v1/chat/completions} path for the Phase D memory pipeline (W3/W4/W5/W6).
 * No memory prompt may call {@code /chat/completions} directly — all memory completions flow through
 * this one client so the per-message egress cap and the cheapest-profile routing are applied uniformly.
 *
 * <p>Mirrors {@code modintelligence.enrich.ModIntelligenceEnrichmentClient.complete}, with three
 * Phase-D-specific invariants:
 * <ul>
 *   <li><strong>Owner-scoped billing.</strong> {@code ownerBilling} is the COMPANION OWNER's resolved
 *       context (resolved by the caller via
 *       {@code Player2PayerResolution.resolve(controller, ownerUsername, clientId)}), never the
 *       prompter's. A null {@code billingKey} throws (callers gate via {@link MemoryGate} first and
 *       schedule nothing on {@code !allowed}).</li>
 *   <li><strong>Enforced egress cap.</strong> {@link LogEgressGuard#cappedMessage} is applied to EVERY
 *       element of {@code history.getListJSON()} UNCONDITIONALLY here at the egress edge — callers are
 *       never trusted to have capped (defense-in-depth re-cap; DESIGN.md §3 data-egress hard rule).</li>
 *   <li><strong>Cheapest routing.</strong> Always routes {@link AiTaskClass#SUMMARIZATION} → Default
 *       profile via {@link ModelTierRouter}, NEVER the patron's named profile.</li>
 * </ul>
 *
 * <p>Runs on a dedicated daemon executor (never the server tick), mirroring
 * {@code WaypointIngestionService.INGESTION_EXECUTOR}. Store writes from the result must marshal back
 * onto the server thread via {@code server.execute(...)} (the caller's responsibility).
 */
public final class MemoryLlmClient {

    /** Daemon executor for all memory completions — never the server tick (mirrors INGESTION_EXECUTOR). */
    public static final ExecutorService MEMORY_EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "memory-llm");
                    t.setDaemon(true);
                    return t;
                }
            });

    private MemoryLlmClient() {}

    /**
     * The canonical memory completion. Synchronous (intended to be invoked on {@link #MEMORY_EXECUTOR},
     * never the tick): billing-key guard → A4 record → per-message egress cap →
     * {@code response_format:{json_object}} → SUMMARIZATION/Default routing → dispatch.
     *
     * @param server       the server (threshold resolution)
     * @param ownerBilling the OWNER's resolved billing context (never the prompter's)
     * @param history      the prompt history (capped per-message here regardless of how it was built)
     * @return the assistant message content string
     * @throws Exception on billing-unavailable, hard-budget, or invalid response
     */
    public static String complete(MinecraftServer server,
            Player2PayerResolution.ApiBillingContext ownerBilling,
            ConversationHistory history) throws Exception {
        if (ownerBilling == null || ownerBilling.billingKey() == null) {
            throw new IllegalStateException("billing_unavailable");
        }

        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        String clientId = cfg.getHeartbeatClientId();
        BudgetThresholds thresholds = BudgetThresholdsResolver.resolve(server, ownerBilling);

        // A4 record (call-count) + Joules peek; hard-limit fails closed.
        BudgetTracker.BudgetCheckResult callResult =
                BudgetTracker.checkAndRecord(ownerBilling.billingKey(), thresholds);
        JoulesCache.JoulesSnapshot joulesSnap = JoulesCache.get(ownerBilling.billingKey()).orElse(null);
        BudgetTracker.BudgetCheckResult joulesResult =
                JoulesCache.checkJoulesThreshold(joulesSnap, thresholds);
        if (BudgetTracker.stricter(callResult, joulesResult) == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            throw new IllegalStateException("budget_hard_limit");
        }

        JsonObject requestBody = new JsonObject();
        // ENFORCED egress cap (DESIGN.md §3): apply the whole-request cap at the memory-pipeline edge;
        // callers are never trusted to have capped. (Mirror ModIntelligenceEnrichmentClient.java.)
        JsonArray messages = LogEgressGuard.cappedMessages(history.getListJSON(), "MemoryLlmClient.complete");
        requestBody.add("messages", messages);
        LogEgressGuard.applyChatCompletionRequestCaps(requestBody, "MemoryLlmClient.complete");
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        requestBody.add("response_format", responseFormat);

        // Always SUMMARIZATION → Default/cheapest. apiService=null so the router can never pick a
        // named profile (rule 5 requires a sole named profile lookup, which needs apiService).
        RoutingDecision routing = ModelTierRouter.resolve(
                AiTaskClass.SUMMARIZATION, null, joulesSnap, thresholds, cfg);

        Map<String, JsonElement> response;
        if (routing.profileBaseUrlOverride().isPresent()) {
            Player2HTTPUtils.setProfileBaseUrlOverride(routing.profileBaseUrlOverride().get());
            try {
                response = dispatchCompletion(ownerBilling, clientId, requestBody);
            } finally {
                Player2HTTPUtils.clearProfileBaseUrlOverride();
            }
        } else {
            response = dispatchCompletion(ownerBilling, clientId, requestBody);
        }

        if (response.containsKey("choices")) {
            JsonElement choicesEl = response.get("choices");
            if (choicesEl.isJsonArray()) {
                JsonArray choices = choicesEl.getAsJsonArray();
                if (!choices.isEmpty()) {
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null && message.has("content")) {
                        return message.get("content").getAsString();
                    }
                }
            }
        }
        throw new IllegalStateException("invalid_response keys=" + response.keySet());
    }

    /**
     * Convenience for W3/W6 prompt callers, mirroring
     * {@code Player2APIService.completeConversationToString(history, taskClass)}. The {@code taskClass}
     * argument is accepted for call-site symmetry but is IGNORED for routing: every memory completion
     * is forced to {@link AiTaskClass#SUMMARIZATION} / Default inside {@link #complete}. (There is no
     * history-only overload because the OWNER billing context cannot be derived from the history; the
     * caller resolves it once and threads it here.)
     */
    public static String completeConversationToString(MinecraftServer server,
            Player2PayerResolution.ApiBillingContext ownerBilling,
            ConversationHistory history,
            AiTaskClass taskClass) throws Exception {
        return complete(server, ownerBilling, history);
    }

    private static Map<String, JsonElement> dispatchCompletion(
            Player2PayerResolution.ApiBillingContext billing,
            String clientId,
            JsonObject requestBody) throws Exception {
        // Background memory ingestion: extended read timeout so slow local models can finish.
        if (billing.useStoredToken() && billing.storedTokenUsername() != null) {
            return Player2HTTPUtils.sendRequestWithStoredToken(
                    billing.storedTokenUsername(), clientId, "/v1/chat/completions", "POST", requestBody,
                    HTTPUtils.INGESTION_READ_TIMEOUT_MS);
        }
        ServerPlayer payer = billing.onlinePayer();
        if (payer != null) {
            return Player2HTTPUtils.sendRequest(
                    payer, clientId, "/v1/chat/completions", "POST", requestBody,
                    HTTPUtils.INGESTION_READ_TIMEOUT_MS);
        }
        throw new IllegalStateException("billing_unavailable");
    }
}
