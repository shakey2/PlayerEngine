package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.elliegps.WaypointAutoRegistrar;
import com.player2.playerengine.agentic.steps.SmeltStepFactory;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.farming.FarmFeedback;
import com.player2.playerengine.tasks.farming.FarmTaskOutcome;
import com.player2.playerengine.tasks.farming.SetupFarmTask;
import com.player2.playerengine.tasks.farming.HarvestFarmOutcome;
import com.player2.playerengine.tasks.farming.HarvestFarmTask;
import com.player2.playerengine.tasks.farming.PlantFarmFeedback;
import com.player2.playerengine.tasks.farming.PlantFarmOutcome;
import com.player2.playerengine.tasks.farming.PlantFarmTask;
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
        // WS5 plan-time pre-seed (the load-bearing forward-reservation step): BEFORE any step runs,
        // reserve every not-yet-run step's resolvable inputs into the chain ledger so an earlier
        // step cannot cannibalize a later step's input (e.g. a smelt step's FuelPlanner burning an
        // item a later step needs). Forward reservation is what keeps the reservation alive long
        // enough for the earlier step to be blocked. Each step releases its OWN reservation at
        // step-start (see runStep). Skipped-pre-seed steps degrade to "no forward protection for
        // that one step", never crash.
        preSeedReservations(plan, controller, context);
        runStep(0, plan, controller, context, onTerminal);
        return new AgenticRunHandle(runId, state);
    }

    /**
     * Reserves every step's plan-time-resolvable inputs into the chain ledger, keyed by item.
     * Per-kind derivation (resolves OQ#9):
     * <ul>
     *   <li><b>smelt</b> — the {@code smelt_items} input item + count, derived via
     *       {@link SmeltStepFactory#forwardReservation} so the pre-seed and the actual draw never
     *       drift. Reserves the INPUT only; fuel is chosen at load time by {@code FuelPlanner} and is
     *       protected at consult time, never pre-seeded.</li>
     *   <li><b>smith</b> — the template/base/addition roles are NOT enumerable at plan time (they
     *       require a live recipe resolution that only happens inside {@code SmithDeferredTask}); only
     *       the output item is known here. Pre-seed is SKIPPED for smith (documented residual bound,
     *       OQ#10); WS3's reserve-at-removal belt-and-suspenders still protects the same-tick window.</li>
     *   <li><b>craft</b> — there is no first-class {@code craft_*} agentic step kind; craft macros run
     *       embedded inside resolve_storage_chest / label_chest steps and the resolver's own
     *       {@code freeCount} ledger floor + per-pass enumeration self-correct at run time. Nothing to
     *       pre-seed here.</li>
     * </ul>
     * The grant is clamped by {@code MaterialReservationService.reserve} to genuinely-free stock, so a
     * pre-seed can only ever lower perceived free — never over-reserve beyond what is held.
     */
    private static void preSeedReservations(
            AgenticPlan plan, PlayerEngineController controller, AgenticExecutionContext context) {
        MaterialReservationService ledger = context.reservations();
        for (AgenticStepSpec step : plan.steps()) {
            if (step == null || step.kind() == null) {
                continue;
            }
            if (AgenticSchemas.STEP_SMELT_ITEMS.equals(step.kind())) {
                SmeltStepFactory.forwardReservation(step, context).ifPresent(
                        res -> ledger.reserve(controller, res.input(), res.count()));
            }
            // smith / craft / non-material steps: no plan-time-enumerable input — skip (see Javadoc).
        }
    }

    /**
     * Releases step {@code index}'s OWN forward reservation just before it runs, so its consult sees
     * its own inputs as free while steps {@code index+1..} stay reserved (and protected from this
     * step's incidental draws). Mirrors the per-kind derivation in {@link #preSeedReservations};
     * only smelt was pre-seeded, so only smelt has anything to release here.
     */
    private static void releaseOwnForwardReservation(
            int index, AgenticPlan plan, AgenticExecutionContext context) {
        AgenticStepSpec step = plan.steps().get(index);
        if (AgenticSchemas.STEP_SMELT_ITEMS.equals(step.kind())) {
            SmeltStepFactory.forwardReservation(step, context).ifPresent(
                    res -> context.reservations().release(res.input(), res.count()));
        }
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
            // WS5 teardown: drop all chain reservations on the success terminal so none leaks into a
            // later run. (finishOnServer takes mod, not context, so the clear is done here where
            // context is in scope.)
            context.memory().clearMaterialReservations();
            finishOnServer(mod, onTerminal, true, summary);
            return;
        }
        AgenticStepSpec step = plan.steps().get(index);
        context.runState().setActiveStep(index, step.kind());
        // WS5 per-step self-release: release THIS step's own forward reservation before it runs, so
        // its consult sees its own inputs as free; steps index+1.. stay reserved and protected from
        // this step's incidental draws (the create-vs-release boundary that closes the cross-step bug).
        releaseOwnForwardReservation(index, plan, context);
        Optional<Task> taskOpt = REGISTRY.createTask(step, context);
        if (taskOpt.isEmpty()) {
            String message = "Unknown or unsupported step: " + step.kind();
            context.runState().terminal("failed", message);
            LOGGER.warn("[Agentic] unknown step kind {}", step.kind());
            // WS5 teardown: drop all chain reservations on the unknown-step terminal.
            context.memory().clearMaterialReservations();
            finishOnServer(mod, onTerminal, false, message);
            return;
        }
        Task task = taskOpt.get();
        mod.runUserTaskTracked(step.id(), step.kind(), task, RollbackPolicy.NONE, () -> {
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
                String message;
                if (task instanceof SetupFarmTask farmTask) {
                    FarmTaskOutcome outcome = farmTask.outcome();
                    message = FarmFeedback.setupModel(outcome);
                    mod.reportAgenticProgress(FarmFeedback.setupPlayer(outcome), true);
                } else if (task instanceof HarvestFarmTask harvestTask) {
                    HarvestFarmOutcome outcome = harvestTask.outcome();
                    message = FarmFeedback.harvestModel(outcome);
                    mod.reportAgenticProgress(FarmFeedback.harvestPlayer(outcome), true);
                } else if (task instanceof PlantFarmTask plantTask) {
                    PlantFarmOutcome outcome = plantTask.outcome();
                    message = PlantFarmFeedback.model(outcome);
                    mod.reportAgenticProgress(PlantFarmFeedback.player(outcome), true);
                } else {
                    message = (reason != null && !reason.isBlank())
                            ? step.kind() + ": " + reason
                            : "Step failed: " + step.kind();
                    mod.reportAgenticProgress(message, true);
                }
                // Player-facing terminal failure broadcast (C4 WS2): emit the SAME reason string the
                // model receives (via the onTerminal -> finishWithError InfoMessage path), as a
                // milestone (bypasses the throttle), BEFORE terminal("failed", ...) sets the run-state
                // terminal flag — otherwise the post-terminal guard in reportAgenticProgress's callers
                // would suppress this line. Reporting only; termination/loop semantics are unchanged.
                context.runState().terminal("failed", message);
                LOGGER.warn("[Agentic] step {} failed: {}", step.kind(), message);
                // WS5 teardown: drop all chain reservations on the step-failure terminal so none
                // leaks into a later run (covers mid-chain abort/fail, not just clean success).
                context.memory().clearMaterialReservations();
                finishOnServer(mod, onTerminal, false, message);
                return;
            }
            if (task instanceof SetupFarmTask farmTask) {
                FarmTaskOutcome outcome = farmTask.outcome();
                String modelFeedback = FarmFeedback.setupModel(outcome);
                context.runState().setFarmProgress(modelFeedback);
                mod.reportAgenticProgress(FarmFeedback.setupPlayer(outcome), true);
            } else if (task instanceof HarvestFarmTask harvestTask) {
                HarvestFarmOutcome outcome = harvestTask.outcome();
                String modelFeedback = FarmFeedback.harvestModel(outcome);
                context.runState().setFarmProgress(modelFeedback);
                mod.reportAgenticProgress(FarmFeedback.harvestPlayer(outcome), true);
            } else if (task instanceof PlantFarmTask plantTask) {
                PlantFarmOutcome outcome = plantTask.outcome();
                String modelFeedback = PlantFarmFeedback.model(outcome);
                context.runState().setFarmProgress(modelFeedback);
                mod.reportAgenticProgress(PlantFarmFeedback.player(outcome), true);
            }
            // Post-deposit auto-registration hook (C5 / Decision 9): best-effort, synchronous-cheap,
            // never fails or delays the run. Wrapped in WaypointAutoRegistrar's own catch-all.
            if (AgenticSchemas.STEP_DEPOSIT_ITEMS.equals(step.kind())) {
                WaypointAutoRegistrar.afterDeposit(context, mod);
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
