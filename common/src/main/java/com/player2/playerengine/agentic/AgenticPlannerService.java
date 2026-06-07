package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticToolContextBuilder.AgenticToolContext;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2APIService;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Player2 PLANNING-backed planner with deterministic C2 fallbacks. */
public final class AgenticPlannerService {

    private static final Logger LOGGER = LogManager.getLogger(AgenticPlannerService.class);

    private AgenticPlannerService() {}

    public static AgenticPlannerOutcome plan(PlayerEngineController mod, AgenticGoalRequest request) {
        PlayerEngineSettings settings = mod.getModSettings();
        String goalText = request.goalText();
        if (goalText == null || goalText.isBlank()) {
            return new AgenticPlannerOutcome(
                    AgenticPlanValidationResult.invalid(List.of("empty_goal")),
                    "rejected",
                    List.of(),
                    "Goal text is required.");
        }

        AgenticToolContext context = AgenticToolContextBuilder.build(mod, goalText, settings);
        List<String> toolIds = context.retrievedToolIds();

        if (!settings.isEnableAgenticPlanner()) {
            LOGGER.info("[Agentic] planner disabled; checking deterministic fallback");
            return tryDeterministicFallback(settings, goalText, toolIds, "planner_disabled");
        }

        Player2APIService api = mod.getPlayer2APIService();
        ConversationHistory history = new ConversationHistory(AgenticPlannerPrompt.systemPrompt(), null);
        history.addUserMessage(AgenticPlannerPrompt.userPrompt(goalText, context), api);

        try {
            String content = api.completeConversationToString(history, AiTaskClass.PLANNING);
            AgenticPlan raw = AgenticPlanParser.parseOrNull(content);
            if (raw == null) {
                LOGGER.warn("[Agentic] malformed planner JSON");
                return tryDeterministicFallback(settings, goalText, toolIds, "malformed_json");
            }
            AgenticPlanValidationResult validation = AgenticPlanValidator.validate(raw, settings);
            if (!validation.valid()) {
                LOGGER.warn("[Agentic] invalid plan: {}", validation.errors());
                AgenticPlannerOutcome fallback = tryDeterministicFallback(settings, goalText, toolIds, "invalid_plan");
                if (fallback.hasExecutablePlan()) {
                    return fallback;
                }
                return new AgenticPlannerOutcome(
                        validation,
                        "model_invalid",
                        toolIds,
                        safePlanFailureMessage(goalText));
            }
            LOGGER.info("[Agentic] model plan accepted: steps={}", validation.sanitizedPlan().steps().size());
            return new AgenticPlannerOutcome(validation, "model", toolIds, null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOGGER.warn("[Agentic] planning call failed: {}", msg);
            if (msg.contains(StopReason.BUDGET_HARD_LIMIT.name())) {
                AgenticPlannerOutcome fallback = tryDeterministicFallback(settings, goalText, toolIds, "budget_blocked");
                if (fallback.hasExecutablePlan()) {
                    return fallback;
                }
                return new AgenticPlannerOutcome(
                        AgenticPlanValidationResult.invalid(List.of("budget_blocked")),
                        "budget_blocked",
                        toolIds,
                        "Planning is blocked by budget limits.");
            }
            return tryDeterministicFallback(settings, goalText, toolIds, "planning_failed");
        }
    }

    private static AgenticPlannerOutcome tryDeterministicFallback(
            PlayerEngineSettings settings,
            String goalText,
            List<String> toolIds,
            String reason) {
        if (!settings.isAgenticPlannerFallbackGather()) {
            return new AgenticPlannerOutcome(
                    AgenticPlanValidationResult.invalid(List.of(reason)),
                    reason,
                    toolIds,
                    safePlanFailureMessage(goalText));
        }
        boolean wantsLabel = settings.isAgenticEnableLabelChest()
                && AgenticStorageIntentDetector.looksLikeLabelGoal(goalText);
        AgenticPlan fallbackPlan = null;
        String source = reason;
        boolean depositGoal = AgenticStorageIntentDetector.looksLikeDepositOnlyGoal(goalText);
        if (depositGoal && AgenticGatherIntentDetector.looksLikeGatherDropGoal(goalText)) {
            fallbackPlan = AgenticFallbackPlans.gatherResolveDepositPlan(goalText, settings, wantsLabel);
            source = wantsLabel ? "fallback_gather_resolve_deposit_label" : "fallback_gather_resolve_deposit";
        } else if (depositGoal) {
            fallbackPlan = AgenticFallbackPlans.resolveThenDepositPlan(goalText, settings, wantsLabel);
            source = wantsLabel ? "fallback_resolve_deposit_label" : "fallback_resolve_deposit";
        } else if (AgenticStorageIntentDetector.looksLikeGatherAndStorageGoal(goalText)) {
            fallbackPlan = AgenticFallbackPlans.gatherThenResolvePlan(goalText, settings);
            source = "fallback_gather_and_storage";
        } else if (AgenticStorageIntentDetector.looksLikeStoragePrepGoal(goalText)) {
            fallbackPlan = AgenticFallbackPlans.resolveStorageChestPlan(goalText, settings);
            source = "fallback_storage";
        } else if (AgenticGatherIntentDetector.looksLikeGatherDropGoal(goalText)) {
            fallbackPlan = AgenticFallbackPlans.gatherLooseItemsPlan(goalText, settings);
            source = "fallback_gather";
        }
        if (fallbackPlan == null) {
            return new AgenticPlannerOutcome(
                    AgenticPlanValidationResult.invalid(List.of(reason)),
                    reason,
                    toolIds,
                    safePlanFailureMessage(goalText));
        }
        AgenticPlanValidationResult validation = AgenticPlanValidator.validate(fallbackPlan, settings);
        if (!validation.valid()) {
            return new AgenticPlannerOutcome(validation, "fallback_invalid", toolIds,
                    safePlanFailureMessage(goalText));
        }
        LOGGER.info("[Agentic] deterministic fallback (source={})", source);
        return new AgenticPlannerOutcome(validation, source, toolIds, null);
    }

    private static String safePlanFailureMessage(String goalText) {
        return "I could not make a safe plan for that yet.";
    }
}
