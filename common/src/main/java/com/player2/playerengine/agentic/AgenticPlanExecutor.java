package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.tasks.base.Task;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Sequential agentic plan executor using {@code runUserTaskTracked} (C1). */
public final class AgenticPlanExecutor {

    private static final Logger LOGGER = LogManager.getLogger(AgenticPlanExecutor.class);
    private static final AgenticStepFactoryRegistry REGISTRY = new AgenticStepFactoryRegistry();

    private AgenticPlanExecutor() {}

    public static AgenticRunHandle start(
            AgenticGoalRequest request,
            AgenticPlan plan,
            PlayerEngineController controller,
            String planningSource,
            BiConsumer<Boolean, String> onTerminal) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        AgenticRunState state = new AgenticRunState(runId, plan.goalSummary(), planningSource);
        UUID botUuid = controller.getEntity().getUUID();
        AgenticRunRegistry.register(botUuid, state);
        AgenticExecutionContext context = new AgenticExecutionContext(controller, state);
        runStep(0, plan, controller, context, onTerminal);
        return new AgenticRunHandle(runId, state);
    }

    private static void runStep(
            int index,
            AgenticPlan plan,
            PlayerEngineController mod,
            AgenticExecutionContext context,
            BiConsumer<Boolean, String> onTerminal) {
        if (index >= plan.steps().size()) {
            String summary = AgenticDegradationSummary.forModel(context.runState());
            context.runState().terminal("succeeded", "Plan complete.");
            LOGGER.info("[Agentic] run {} succeeded", context.runState().toSnapshot().runId());
            finishOnServer(mod, onTerminal, true, summary);
            return;
        }
        AgenticStepSpec step = plan.steps().get(index);
        context.runState().setActiveStep(index, step.kind());
        Optional<Task> taskOpt = REGISTRY.createTask(step, context);
        if (taskOpt.isEmpty()) {
            String message = "Unknown or unsupported step: " + step.kind();
            context.runState().terminal("failed", message);
            LOGGER.warn("[Agentic] unknown step kind {}", step.kind());
            finishOnServer(mod, onTerminal, false, message);
            return;
        }
        mod.runUserTaskTracked(step.id(), step.kind(), taskOpt.get(), RollbackPolicy.NONE, () -> {
            boolean succeeded = false;
            if (mod.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
                succeeded = adapter.getLastCompletedExecution()
                        .map(e -> e.getState() == StepState.SUCCEEDED)
                        .orElse(false);
            }
            if (!succeeded) {
                // Surface the step kind AND the specific reason the failed step recorded into run
                // state (e.g. resolve_storage_chest -> "could_not_obtain_chest_materials"), so the
                // NPC model sees an actionable cause rather than a bare "step failed".
                String reason = context.runState().progressForKind(step.kind());
                String message = (reason != null && !reason.isBlank())
                        ? step.kind() + ": " + reason
                        : "Step failed: " + step.kind();
                // Player-facing terminal failure broadcast (C4 WS2): emit the SAME reason string the
                // model receives (via the onTerminal -> finishWithError InfoMessage path), as a
                // milestone (bypasses the throttle), BEFORE terminal("failed", ...) sets the run-state
                // terminal flag — otherwise the post-terminal guard in reportAgenticProgress's callers
                // would suppress this line. Reporting only; termination/loop semantics are unchanged.
                mod.reportAgenticProgress(message, true);
                context.runState().terminal("failed", message);
                LOGGER.warn("[Agentic] step {} failed: {}", step.kind(), message);
                finishOnServer(mod, onTerminal, false, message);
                return;
            }
            runStep(index + 1, plan, mod, context, onTerminal);
        });
    }

    private static void finishOnServer(
            PlayerEngineController mod,
            BiConsumer<Boolean, String> onTerminal,
            boolean success,
            String message) {
        Runnable wrapped = () -> {
            AgenticRunRegistry.clear(mod.getEntity().getUUID());
            if (onTerminal != null) {
                onTerminal.accept(success, message);
            }
        };
        if (mod.getPlayer().getServer() != null) {
            mod.getPlayer().getServer().execute(wrapped);
        } else {
            wrapped.run();
        }
    }
}
