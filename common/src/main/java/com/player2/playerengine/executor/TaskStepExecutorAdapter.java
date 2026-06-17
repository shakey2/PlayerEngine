package com.player2.playerengine.executor;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.Debug;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Minimal implementation of IStepExecutorAdapter wired to UserTaskChain.
 *
 * Lifecycle per submitted step:
 *   1. PENDING  — execution created; preconditions checked.
 *   2. RUNNING  — task submitted to UserTaskChain via runTask().
 *   3. Terminal — determined inside the onFinish callback:
 *        task.isFinished() == true  -> SUCCEEDED
 *        task.isFinished() == false -> FAILED (stopped/cancelled/errored)
 *        FAILED + non-NONE policy   -> ROLLED_BACK (stub in Phase A2)
 *
 * Why task.isFinished() works without modifying UserTaskChain:
 *   SingleTaskChain.onTick() reaches onTaskFinish() via two paths —
 *   the normal-finish path (isFinished() was true before stop) and the
 *   forced-stop path (stopped() == true, isFinished() still false).
 *   The captured task reference retains its isFinished() value after stop(),
 *   so the onFinish callback can distinguish success from failure.
 *
 * onComplete is always called at terminal state so CommandExecutor's
 * sequential chain callbacks are never silently dropped.
 *
 * Phase A2 rollback: logs the policy and transitions to ROLLED_BACK.
 * Actual compensating tasks are deferred to Phase A3+.
 */
public class TaskStepExecutorAdapter implements IStepExecutorAdapter {

    private final PlayerEngineController mod;
    private StepExecution activeExecution;
    /** Most recently completed (terminal) execution; never null after the first step finishes. */
    private StepExecution lastCompletedExecution;
    /** Consumed when the UserTaskChain onFinish callback runs after a cancel/stop. */
    private volatile String pendingFinishReason;

    public TaskStepExecutorAdapter(PlayerEngineController mod) {
        this.mod = mod;
    }

    /**
     * Arms {@link #pendingFinishReason} when a tracked step is RUNNING and the user task chain
     * still holds an active task — used by {@link PlayerEngineController#stop(StopReason)} so
     * {@code stop()} / disconnect produce deterministic {@link StopReason} labels.
     *
     * <p>First-writer-wins: if a reason has already been armed (e.g. an operator
     * {@code stop(CANCELLED_OPERATOR)} that races a follow self-stop), it is kept and not
     * overwritten, so the originally-intended label is recorded rather than the later one.
     */
    public void armPendingChainCancel(StopReason reason) {
        if (reason == null) {
            return;
        }
        if (this.pendingFinishReason != null) {
            return;
        }
        if (activeExecution != null && activeExecution.getState() == StepState.RUNNING
                && mod.getUserTaskChain().isActive()) {
            this.pendingFinishReason = reason.name();
        }
    }

    @Override
    public StepExecution submit(String stepId, String stepKind,
                                Task task, RollbackPolicy rollbackPolicy,
                                Runnable onComplete) {
        pendingFinishReason = null;

        if (activeExecution != null && !activeExecution.isTerminal()) {
            Debug.logInternal(
                "[StepExecutorAdapter] Overwriting non-terminal execution "
                + activeExecution.getStepId() + " (state=" + activeExecution.getState()
                + ") with new step " + stepId + ". Marking previous as FAILED."
            );
            activeExecution.transitionTo(StepState.FAILED,
                    StopReason.CANCELLED_SUPERSEDED.name() + ":superseded_by_step=" + stepId);
            this.lastCompletedExecution = activeExecution;
        }

        StepExecution exec = new StepExecution(stepId, stepKind);
        this.activeExecution = exec;

        if (!PlayerEngineController.inGame()) {
            exec.transitionTo(StepState.BLOCKED,
                    StopReason.USER_ACTION_REQUIRED.name() + ":not_in_game");
            this.lastCompletedExecution = exec;
            onComplete.run();
            return exec;
        }

        exec.transitionTo(StepState.RUNNING, "submitted_to_chain");

        mod.getUserTaskChain().runTask(mod, task, () -> {
            if (exec.isTerminal()) {
                return;
            }
            String pending = pendingFinishReason;
            pendingFinishReason = null;
            if (pending != null) {
                exec.transitionTo(StepState.FAILED, pending);
            } else if (task.isFinished()) {
                exec.transitionTo(StepState.SUCCEEDED, "task_finished_normally");
            } else {
                exec.transitionTo(StepState.FAILED,
                        StopReason.FATAL.name() + ":task_stopped_without_finish");
                if (rollbackPolicy != RollbackPolicy.NONE) {
                    handleRollbackStub(exec, rollbackPolicy);
                }
            }
            this.lastCompletedExecution = exec;
            if (exec.getState() == StepState.FAILED) {
                String lastEntry = exec.getLastLogEntry();
                if (lastEntry.contains(StopReason.FOLLOWED_TARGET_GONE.name())) {
                    // Expected, graceful termination of a follow: the followed player died/left.
                    // Never leak the raw state-machine string to the player \u2014 send a concise human line.
                    Player owner = mod.getOwner();
                    if (owner instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(Component.literal(
                                "I lost you \u2014 looks like you died or left. "
                                + "I'll wait here; tell me to follow again when you're ready."));
                    }
                } else if (!lastEntry.contains("CANCELLED_")) {
                    String msg = "[PlayerEngine] Step '" + exec.getStepKind()
                            + "' stopped unexpectedly \u2014 " + lastEntry;
                    mod.log(msg);
                    Player owner = mod.getOwner();
                    if (owner instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(
                                Component.literal(msg).withStyle(ChatFormatting.RED));
                    }
                }
            }
            onComplete.run();
        });

        return exec;
    }

    @Override
    public void cancel(String reason) {
        if (activeExecution == null || activeExecution.getState() != StepState.RUNNING) {
            return;
        }
        String detail = (reason == null || reason.isBlank()) ? "" : ":" + reason;
        pendingFinishReason = StopReason.CANCELLED_OPERATOR.name() + detail;
        // userTaskChain.cancel() calls onTaskFinish() synchronously, which fires
        // the wrapped onFinish callback above, which transitions RUNNING -> FAILED
        // and invokes onComplete.
        mod.getUserTaskChain().cancel(mod);
    }

    @Override
    public Optional<StepExecution> getActiveExecution() {
        return Optional.ofNullable(activeExecution);
    }

    /**
     * Returns the most recently completed (terminal) execution, or empty if no step has
     * reached a terminal state yet. Unlike {@link #getActiveExecution()}, this always
     * refers to a finished step even when a new step is currently running.
     */
    public Optional<StepExecution> getLastCompletedExecution() {
        return Optional.ofNullable(lastCompletedExecution);
    }

    private void handleRollbackStub(StepExecution exec, RollbackPolicy policy) {
        Debug.logInternal(
            "[StepExecutorAdapter] Rollback stub: policy=" + policy
            + " for step " + exec.getStepId() + " [" + exec.getStepKind()
            + "]. Full rollback deferred to Phase A3+."
        );
        exec.transitionTo(StepState.ROLLED_BACK,
                StopReason.COMPENSATABLE.name() + ":rollback_stub_" + policy.name().toLowerCase());
    }
}
