package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticToolContextBuilder.AgenticToolContext;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2APIService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Player2 PLANNING-backed planner with deterministic C2 fallbacks. */
public final class AgenticPlannerService {

    private static final Logger LOGGER = LogManager.getLogger(AgenticPlannerService.class);

    /**
     * Dedicated single-thread executor for the BLOCKING Player2 PLANNING HTTP round-trip. The
     * planning call (api.completeConversationToString(.., PLANNING)) takes 8-17s on the local
     * Player2 app and MUST NOT run on the server thread: doing so froze the integrated server for
     * the whole round-trip ("Can't keep up! Running 10869ms behind"). Mirrors the off-thread pattern
     * the conversational LLMCompleter already uses (pool-11). Daemon so it never blocks shutdown.
     */
    private static final ExecutorService PLANNER_EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "agentic-planner");
                    t.setDaemon(true);
                    return t;
                }
            });

    private AgenticPlannerService() {}

    /**
     * NON-BLOCKING entry point (issue C fix). The world-touching context build (RAG retrieval + the
     * storage/placement scans that read world state) runs SYNCHRONOUSLY on the caller's thread, which is
     * the server thread (no off-thread world access — see EntityTracker.java MINECRAFT_LOCK hazard). Only
     * the BLOCKING Player2 PLANNING HTTP round-trip + JSON parse/validate (which touch no world state)
     * run on {@link #PLANNER_EXECUTOR}. The resulting {@link AgenticPlannerOutcome} is marshalled back
     * onto the server thread before {@code onOutcome} runs, so plan acceptance and the
     * finishWithNote/finishWithError path execute on the server thread exactly as before. The
     * deterministic fallbacks, budget-denial handling, and outcome shape are identical to {@link #plan};
     * only the threading of the HTTP call changes, so failure/degradation reporting to player + model
     * (DESIGN.md §3) is preserved. The callback always fires exactly once.
     */
    public static void planAsync(PlayerEngineController mod, AgenticGoalRequest request,
                                 Consumer<AgenticPlannerOutcome> onOutcome) {
        final MinecraftServer server = mod.getPlayer() != null ? mod.getPlayer().getServer() : null;
        final PlayerEngineSettings settings = mod.getModSettings();
        final String goalText = request.goalText();
        if (goalText == null || goalText.isBlank()) {
            onOutcome.accept(new AgenticPlannerOutcome(
                    AgenticPlanValidationResult.invalid(List.of("empty_goal")),
                    "rejected",
                    List.of(),
                    "Goal text is required."));
            return;
        }

        // Build the (world-reading) context here, on the server thread, before going async.
        final AgenticToolContext context = AgenticToolContextBuilder.build(mod, goalText, settings);
        final List<String> toolIds = context.retrievedToolIds();

        if (!settings.isEnableAgenticPlanner()) {
            LOGGER.info("[Agentic] planner disabled; checking deterministic fallback");
            onOutcome.accept(tryDeterministicFallback(settings, goalText, toolIds, "planner_disabled"));
            return;
        }

        // Only the blocking HTTP round-trip + parse/validate (no world access) run off-thread.
        final Player2APIService api = mod.getPlayer2APIService();
        PLANNER_EXECUTOR.submit(() -> {
            AgenticPlannerOutcome outcome;
            try {
                outcome = planWithContext(settings, api, goalText, context, toolIds);
            } catch (Throwable t) {
                // Defensive: planWithContext already catches Exception and falls back, but never let a
                // thrown error strand the command without a callback (would leak a run with no terminal).
                LOGGER.warn("[Agentic] async planning failed unexpectedly: {}",
                        t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                outcome = tryDeterministicFallback(settings, goalText, toolIds, "planning_failed");
            }
            final AgenticPlannerOutcome result = outcome;
            if (server != null) {
                server.execute(() -> onOutcome.accept(result));
            } else {
                onOutcome.accept(result);
            }
        });
    }

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

        return planWithContext(settings, mod.getPlayer2APIService(), goalText, context, toolIds);
    }

    /**
     * The HTTP round-trip + JSON parse/validate stage, with NO world access (safe to call off the server
     * thread). Shared by the synchronous {@link #plan} and the async {@link #planAsync}. The context must
     * already be built (on the server thread) by the caller.
     */
    private static AgenticPlannerOutcome planWithContext(
            PlayerEngineSettings settings,
            Player2APIService api,
            String goalText,
            AgenticToolContext context,
            List<String> toolIds) {
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
            AgenticPlannerOutcome augmented = maybeAugmentWithGather(validation, context, settings, toolIds);
            if (augmented != null) {
                return augmented;
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

    /**
     * Deterministic-over-model guard: a valid model plan that deposits items while the inventory
     * holds nothing depositable and drops are sitting on the ground is a guaranteed no-op (the
     * chat layer can rewrite "gather X and store it" into a lossy pure-deposit goal, so the model
     * never sees the gather intent). When the {@link AgenticWorldSnapshot} — built on the server
     * thread before planning, immutable, so safe to read here on the planner thread — shows
     * drops nearby and {@code hasDepositableItems=false}, prepend the standard settings-driven
     * gather step and revalidate. Returns the augmented outcome (source
     * {@code model_augmented_gather}), or {@code null} to accept the model plan unchanged.
     * Plans that already gather, plans without deposit_items, and runs where the bot already
     * holds depositable items are never touched; revalidation failure never degrades the
     * original valid plan.
     */
    private static AgenticPlannerOutcome maybeAugmentWithGather(
            AgenticPlanValidationResult validation,
            AgenticToolContext context,
            PlayerEngineSettings settings,
            List<String> toolIds) {
        AgenticPlan plan = validation.sanitizedPlan();
        AgenticWorldSnapshot world = context != null ? context.world() : null;
        if (plan == null || world == null) {
            return null;
        }
        boolean hasDeposit = false;
        boolean hasGather = false;
        for (AgenticStepSpec step : plan.steps()) {
            if (AgenticSchemas.STEP_DEPOSIT_ITEMS.equals(step.kind())) {
                hasDeposit = true;
            } else if (AgenticSchemas.STEP_GATHER_LOOSE_ITEMS.equals(step.kind())) {
                hasGather = true;
            }
        }
        if (!hasDeposit || hasGather
                || world.nearbyDropCount() <= 0
                || world.hasDepositableItems()) {
            return null;
        }
        List<AgenticStepSpec> steps = new java.util.ArrayList<>(plan.steps().size() + 1);
        steps.add(AgenticFallbackPlans.gatherStep(settings));
        steps.addAll(plan.steps());
        AgenticPlan augmentedPlan = new AgenticPlan(
                plan.schemaVersion(),
                plan.goalSummary(),
                List.copyOf(steps),
                plan.plannerNote());
        AgenticPlanValidationResult revalidation = AgenticPlanValidator.validate(augmentedPlan, settings);
        if (!revalidation.valid()) {
            // Never degrade a working plan: keep the original model plan if augmentation fails.
            LOGGER.warn("[Agentic] gather augmentation revalidation failed ({}); keeping original model plan",
                    revalidation.errors());
            return null;
        }
        LOGGER.info("[Agentic] plan augmented: prepended gather_loose_items (drops={}, has_depositable=false)",
                world.nearbyDropCount());
        return new AgenticPlannerOutcome(revalidation, "model_augmented_gather", toolIds, null);
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
