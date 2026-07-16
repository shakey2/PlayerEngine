package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * Pure-function model-tier router (B3).
 *
 * <p>Decides which Player2 profile base URL to use for a given {@link AiTaskClass}.
 * No side effects, no HTTP calls, no state mutation.
 *
 * <h3>Decision rules (first match wins)</h3>
 * <ol>
 *   <li>{@code RETRIEVAL} → on-device; never reaches HTTP.
 *   <li>{@code dedicatedClientProxy == true} → Default (bridge mode cannot rewrite URLs).
 *   <li>{@code RERANKING} or {@code SUMMARIZATION} → Default (always cheapest).
 *   <li>{@code PLANNING} or {@code DECISION} + Joules snapshot present + soft threshold enabled
 *       + balance below threshold → Default (Joules-soft demotion).
 *   <li>{@code PLANNING} or {@code DECISION} + a fresh patron Joules snapshot + exactly one
 *       named profile in {@code GET /v1/ai_profiles} → that profile's base URL.
 *   <li>Default.
 * </ol>
 *
 * <p><strong>Precedence vs A4:</strong> When A4 soft-budget fires and {@code SWITCH_PROFILE}
 * is configured, {@code sendChatCompletionRequest} returns early before calling this router,
 * so A4 naturally supersedes B3 for that call. This router only runs when A4 budget is OK.
 *
 * <p>See {@code masterplan/model-tier-routing.md} for the full decision matrix.
 */
public final class ModelTierRouter {

    private static final Logger LOGGER = LogManager.getLogger(ModelTierRouter.class);

    private ModelTierRouter() {}

    /**
     * Resolves which profile to use for {@code taskClass}.
     */
    public static RoutingDecision resolve(
            AiTaskClass taskClass,
            Player2APIService apiService,
            JoulesCache.JoulesSnapshot snapshot,
            BudgetThresholds thresholds,
            Player2ServerRuntimeConfig serverConfig) {
        boolean mayUseNamed = isPlanningOrDecision(taskClass)
                && JoulesCache.isFreshPatronSnapshot(snapshot, thresholds)
                && (serverConfig == null || !serverConfig.isDedicatedClientProxy());
        Optional<String> soleNamed = mayUseNamed && apiService != null
                ? ProfileUrlResolver.getSoleNamedProfileBaseUrl(apiService)
                : Optional.empty();
        return resolveWithRule(taskClass, soleNamed, snapshot, thresholds, serverConfig).decision();
    }

    /**
     * Same as {@link #resolve} but also returns the matched rule number (1–6) for probe output and tests.
     */
    public static RoutingResult resolveWithRule(
            AiTaskClass taskClass,
            Optional<String> soleNamedProfileBaseUrl,
            JoulesCache.JoulesSnapshot snapshot,
            BudgetThresholds thresholds,
            Player2ServerRuntimeConfig serverConfig) {

        if (taskClass == AiTaskClass.RETRIEVAL) {
            return new RoutingResult(RoutingDecision.onDevice(), 1);
        }

        if (serverConfig != null && serverConfig.isDedicatedClientProxy()) {
            LOGGER.debug("ModelTierRouter: rule 2 — dedicatedClientProxy=true → Default");
            return new RoutingResult(RoutingDecision.defaultProfile(), 2);
        }

        if (taskClass == AiTaskClass.RERANKING || taskClass == AiTaskClass.SUMMARIZATION) {
            return new RoutingResult(RoutingDecision.defaultProfile(), 3);
        }

        if (taskClass == AiTaskClass.PLANNING || taskClass == AiTaskClass.DECISION) {

            if (snapshot != null
                    && thresholds != null
                    && thresholds.getSoftJoulesThreshold() > 0
                    && snapshot.joulesDisplay() < thresholds.getSoftJoulesThreshold()) {
                LOGGER.debug("ModelTierRouter: rule 4 — PLANNING/DECISION demoted to Default "
                        + "(joules {} < soft {})", snapshot.joulesDisplay(), thresholds.getSoftJoulesThreshold());
                return new RoutingResult(RoutingDecision.defaultProfile(), 4);
            }

            if (soleNamedProfileBaseUrl != null && soleNamedProfileBaseUrl.isPresent()
                    && JoulesCache.isFreshPatronSnapshot(snapshot, thresholds)) {
                LOGGER.debug("ModelTierRouter: rule 5 — {} → named profile", taskClass);
                return new RoutingResult(RoutingDecision.namedProfile(soleNamedProfileBaseUrl.get()), 5);
            }
        }

        return new RoutingResult(RoutingDecision.defaultProfile(), 6);
    }

    private static boolean isPlanningOrDecision(AiTaskClass taskClass) {
        return taskClass == AiTaskClass.PLANNING || taskClass == AiTaskClass.DECISION;
    }

    /** Human-readable outcome for {@code /playerengine routing probe}. */
    public static String describeOutcome(RoutingDecision decision) {
        if (decision.isOnDevice()) {
            return "on-device (no HTTP)";
        }
        if (decision.profileBaseUrlOverride().isPresent()) {
            return "named profile: " + decision.profileBaseUrlOverride().get();
        }
        return "Default (no URL override)";
    }
}
