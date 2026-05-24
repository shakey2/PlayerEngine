package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.ModelTierRouter;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.RoutingDecision;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.PlayerBudgetConfigHolder;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import com.player2.playerengine.player2api.ConversationHistory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Server-side enrichment HTTP client (Default profile / SUMMARIZATION routing).
 */
public final class ModIntelligenceEnrichmentClient {
    private ModIntelligenceEnrichmentClient() {}

    public static boolean isBillingAvailable(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return Player2PayerResolution.canBillServer(server, cfg.getHeartbeatClientId());
    }

    public static BudgetThresholds getBudgetThresholds(MinecraftServer server) {
        Player2PayerResolution.ApiBillingContext billing = resolveBilling(server);
        if (billing == null || billing.billingKey() == null) {
            return null;
        }
        return thresholdsFor(billing);
    }

    public static boolean noBudgetLimitsConfigured(BudgetThresholds thresholds) {
        return thresholds.getSoftBudgetCallsPerWindow() == 0
                && thresholds.getHardBudgetCallsPerWindow() == 0
                && thresholds.getSoftJoulesThreshold() == 0
                && thresholds.getHardJoulesThreshold() == 0;
    }

    public static boolean wouldExceedHardBudget(MinecraftServer server) {
        Player2PayerResolution.ApiBillingContext billing = resolveBilling(server);
        if (billing.billingKey() == null) {
            return true;
        }
        BudgetThresholds thresholds = thresholdsFor(billing);
        BudgetTracker.BudgetCheckResult call = BudgetTracker.peek(billing.billingKey(), thresholds);
        JoulesCache.JoulesSnapshot snap = JoulesCache.get(billing.billingKey()).orElse(null);
        BudgetTracker.BudgetCheckResult joules = JoulesCache.checkJoulesThreshold(snap, thresholds);
        return BudgetTracker.stricter(call, joules) == BudgetTracker.BudgetCheckResult.HARD_LIMIT;
    }

    public static String complete(MinecraftServer server, ConversationHistory history) throws Exception {
        Player2PayerResolution.ApiBillingContext billing = resolveBilling(server);
        if (billing.billingKey() == null) {
            throw new IllegalStateException("billing_unavailable");
        }

        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        String clientId = cfg.getHeartbeatClientId();
        BudgetThresholds thresholds = thresholdsFor(billing);

        BudgetTracker.BudgetCheckResult callResult =
                BudgetTracker.checkAndRecord(billing.billingKey(), thresholds);
        JoulesCache.maybeRefresh(null, billing.billingKey(), thresholds);
        JoulesCache.JoulesSnapshot joulesSnap = JoulesCache.get(billing.billingKey()).orElse(null);
        BudgetTracker.BudgetCheckResult joulesResult =
                JoulesCache.checkJoulesThreshold(joulesSnap, thresholds);
        if (BudgetTracker.stricter(callResult, joulesResult) == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            throw new IllegalStateException("budget_hard_limit");
        }

        JsonObject requestBody = new JsonObject();
        JsonArray messages = new JsonArray();
        for (JsonObject msg : history.getListJSON()) {
            messages.add(msg);
        }
        requestBody.add("messages", messages);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        requestBody.add("response_format", responseFormat);

        RoutingDecision routing = ModelTierRouter.resolve(
                AiTaskClass.SUMMARIZATION, null, joulesSnap, thresholds, cfg);

        Map<String, JsonElement> response;
        if (routing.profileBaseUrlOverride().isPresent()) {
            Player2HTTPUtils.setProfileBaseUrlOverride(routing.profileBaseUrlOverride().get());
            try {
                response = dispatchCompletion(billing, clientId, requestBody);
            } finally {
                Player2HTTPUtils.clearProfileBaseUrlOverride();
            }
        } else {
            response = dispatchCompletion(billing, clientId, requestBody);
        }

        validateCompletionModel(response);

        if (response.containsKey("choices")) {
            var choices = response.get("choices").getAsJsonArray();
            if (!choices.isEmpty()) {
                var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString();
                }
            }
        }
        throw new IllegalStateException("invalid_response keys=" + response.keySet());
    }

    private static void validateCompletionModel(Map<String, JsonElement> response) {
        JsonElement modelElement = response.get("model");
        if (modelElement != null && modelElement.isJsonPrimitive()) {
            String modelName = modelElement.getAsString();
            if (ModelBlacklist.isBlacklisted(modelName)) {
                throw new IllegalStateException("model_blacklisted");
            }
            return;
        }
        if (ModelBlacklist.requireModelInResponse()) {
            throw new IllegalStateException("model_missing");
        }
        ModelBlacklist.logMissingModelOnce();
    }

    private static Player2PayerResolution.ApiBillingContext resolveBilling(MinecraftServer server) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        return Player2PayerResolution.resolveForServer(server, cfg.getHeartbeatClientId());
    }

    private static BudgetThresholds thresholdsFor(Player2PayerResolution.ApiBillingContext billing) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        ServerPlayer payer = billing.onlinePayer();
        if (cfg.getPayerMode() == Player2PayerMode.PROMPTER_PAYS && payer != null) {
            return PlayerBudgetConfigHolder.load(payer.getServer(), payer.getUUID());
        }
        return cfg;
    }

    private static Map<String, JsonElement> dispatchCompletion(
            Player2PayerResolution.ApiBillingContext billing,
            String clientId,
            JsonObject requestBody) throws Exception {
        if (billing.useStoredToken() && billing.storedTokenUsername() != null) {
            return Player2HTTPUtils.sendRequestWithStoredToken(
                    billing.storedTokenUsername(), clientId, "/v1/chat/completions", "POST", requestBody);
        }
        ServerPlayer payer = billing.onlinePayer();
        if (payer != null) {
            return Player2HTTPUtils.sendRequest(
                    payer, clientId, "/v1/chat/completions", "POST", requestBody);
        }
        throw new IllegalStateException("billing_unavailable");
    }
}
