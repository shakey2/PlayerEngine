package com.player2.playerengine.executor;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TrackedTaskOutcomeProvider;

import java.util.function.BooleanSupplier;

/** Deterministic tests for the tracked-task terminal outcome adapter. */
public final class TaskStepExecutorAdapterSelfTest {
    private TaskStepExecutorAdapterSelfTest() {
    }

    public static void runAll() {
        onceCompletionIsExact();
        runningViewExcludesPendingAndTerminalExecutions();
        supersededCompletionLifecycle();
        staleReentrantSubmissionCannotSupersedeAuthoritativeExecution();
        configuredIdlePreflightPreservesAuthoritativeExecution();
        unreapedTerminalOutcomeIsPreservedBeforeReplacement();
        forcedSubmissionOwnsTaskAndCallback();
        assertDecision(new FakeTrackedTask(true, true, "none"),
                StepState.SUCCEEDED, "task_finished_successfully");
        assertDecision(new FakeTrackedTask(true, false, "water_source_not_found"),
                StepState.FAILED, StopReason.FATAL.name() + ":water_source_not_found");
        assertDecision(new FakeTrackedTask(true, false, null),
                StepState.FAILED, StopReason.FATAL.name() + ":internal_contract_failure");
        assertDecision(new FakeTrackedTask(false, false, "none"),
                StepState.FAILED, StopReason.FATAL.name() + ":task_stopped_before_terminal_outcome");
        assertDecision(new FakeLegacyTask(true),
                StepState.SUCCEEDED, "task_finished_normally");
        assertDecision(new FakeLegacyTask(false),
                StepState.FAILED, StopReason.FATAL.name() + ":task_stopped_without_finish");
    }

    private static void onceCompletionIsExact() {
        int[] calls = {0};
        Runnable completion = TaskStepExecutorAdapter.once(() -> calls[0]++);
        completion.run();
        completion.run();
        if (calls[0] != 1) {
            throw new AssertionError("superseded completion callback was not exactly-once");
        }
    }

    private static void runningViewExcludesPendingAndTerminalExecutions() {
        StepExecution execution = new StepExecution("status-test", "setup_farm");
        if (TaskStepExecutorAdapter.runningExecution(execution).isPresent()) {
            throw new AssertionError("PENDING execution was exposed as active");
        }
        execution.transitionTo(StepState.RUNNING, "self_test");
        if (TaskStepExecutorAdapter.runningExecution(execution).orElse(null) != execution) {
            throw new AssertionError("RUNNING execution was hidden from active diagnostics");
        }
        execution.transitionTo(StepState.SUCCEEDED, "self_test_complete");
        if (TaskStepExecutorAdapter.runningExecution(execution).isPresent()) {
            throw new AssertionError("terminal execution was exposed as active");
        }
        if (TaskStepExecutorAdapter.runningExecution(null).isPresent()) {
            throw new AssertionError("null execution was exposed as active");
        }
    }

    private static void supersededCompletionLifecycle() {
        TaskStepExecutorAdapter.CompletionTracker tracker =
                new TaskStepExecutorAdapter.CompletionTracker();
        Object oldToken = new Object();
        Object newToken = new Object();
        int[] oldCalls = {0};
        int[] newCalls = {0};
        Runnable oldCompletion = TaskStepExecutorAdapter.once(() -> oldCalls[0]++);
        Runnable newCompletion = TaskStepExecutorAdapter.once(() -> newCalls[0]++);

        tracker.install(oldToken, oldCompletion);
        Runnable capturedOld = tracker.capture(oldToken);
        tracker.install(newToken, newCompletion);
        tracker.finish(oldToken, capturedOld); // active-chain synchronous replacement callback
        capturedOld.run(); // inactive-chain fallback after runTask returns
        if (oldCalls[0] != 1 || !tracker.isActive(newToken)) {
            throw new AssertionError("replacement did not complete old exactly once while preserving new");
        }

        tracker.finish(newToken, newCompletion); // also models a blocked new execution
        newCompletion.run();
        if (newCalls[0] != 1 || tracker.isActive(newToken)) {
            throw new AssertionError("blocked/new completion was not exactly-once and cleared");
        }

        Object fallbackOld = new Object();
        Object fallbackNew = new Object();
        int[] fallbackCalls = {0};
        Runnable fallbackCompletion = TaskStepExecutorAdapter.once(() -> fallbackCalls[0]++);
        tracker.install(fallbackOld, fallbackCompletion);
        Runnable capturedFallback = tracker.capture(fallbackOld);
        tracker.install(fallbackNew, TaskStepExecutorAdapter.once(() -> { }));
        capturedFallback.run(); // no old task remained in the user chain
        if (fallbackCalls[0] != 1 || !tracker.isActive(fallbackNew)) {
            throw new AssertionError("inactive-chain fallback disturbed the newer completion");
        }

        Object reentrantOld = new Object();
        Object provisionalNew = new Object();
        Object reentrantNew = new Object();
        int[] reentrantCalls = {0};
        Runnable reentrantCompletion = TaskStepExecutorAdapter.once(() -> {
            reentrantCalls[0]++;
            tracker.install(reentrantNew, TaskStepExecutorAdapter.once(() -> { }));
        });
        tracker.install(reentrantOld, reentrantCompletion);
        Runnable capturedReentrant = tracker.capture(reentrantOld);
        tracker.install(provisionalNew, TaskStepExecutorAdapter.once(() -> { }));
        tracker.finish(reentrantOld, capturedReentrant);
        capturedReentrant.run();
        if (reentrantCalls[0] != 1 || !tracker.isActive(reentrantNew)) {
            throw new AssertionError("reentrant old completion cleared or duplicated the newest completion");
        }
        if (tracker.capture(new Object()) != null) {
            throw new AssertionError("completion tracker captured a non-active token");
        }
    }

    private static void staleReentrantSubmissionCannotSupersedeAuthoritativeExecution() {
        StepExecution authoritativeB = new StepExecution("B", "setup_farm");
        authoritativeB.transitionTo(StepState.RUNNING, "installed_authoritatively");
        TaskStepExecutorAdapter.CompletionTracker tracker =
                new TaskStepExecutorAdapter.CompletionTracker();
        Runnable bCompletion = TaskStepExecutorAdapter.once(() -> { });
        tracker.install(authoritativeB, bCompletion);

        FakeTrackedTask staleC = new FakeTrackedTask(false, false, "none", "stale-C");
        int[] staleCallbacks = {0};
        StepExecution rejectedC = TaskStepExecutorAdapter.sealStaleReentrantSubmission(
                "C", "mine_block", staleC, null);
        Runnable staleCompletion = TaskStepExecutorAdapter.once(() -> staleCallbacks[0]++);
        staleCompletion.run();
        staleCompletion.run();

        if (!staleC.stopped() || rejectedC.getState() != StepState.BLOCKED
                || !rejectedC.getLastLogEntry().contains("stale_reentrant_submission")) {
            throw new AssertionError("stale C was not sealed as a pre-install terminal");
        }
        if (authoritativeB.getState() != StepState.RUNNING
                || !tracker.isActive(authoritativeB)) {
            throw new AssertionError("stale C superseded authoritative B ownership");
        }
        if (staleCallbacks[0] != 1) {
            throw new AssertionError("stale C completion was not exactly once");
        }
    }

    private static void configuredIdlePreflightPreservesAuthoritativeExecution() {
        StepExecution authoritativeA = new StepExecution("A", "setup_farm");
        authoritativeA.transitionTo(StepState.RUNNING, "installed_authoritatively");
        FakeTrackedTask taskA = new FakeTrackedTask(false, false, "none", "task-A");
        taskA.reset();
        int[] aCallbacks = {0};
        int[] bCallbacks = {0};

        TaskStepExecutorAdapter adapter = new TaskStepExecutorAdapter(null) {
            @Override
            boolean rejectsReentrantTrackedSubmission() {
                return false;
            }

            @Override
            boolean rejectsConfiguredIdleTrackedSubmission() {
                return true;
            }
        };
        adapter.seedActiveExecutionForSelfTest(
                authoritativeA, taskA, () -> aCallbacks[0]++);

        FakeTrackedTask incomingIdleB =
                new FakeTrackedTask(false, false, "none", "idle-B");
        StepExecution rejectedB = adapter.submit(
                "B", "setup_farm", incomingIdleB, RollbackPolicy.NONE,
                () -> bCallbacks[0]++);

        if (adapter.getRunningExecution().orElse(null) != authoritativeA
                || authoritativeA.getState() != StepState.RUNNING
                || !adapter.ownsActiveSlotForSelfTest(authoritativeA, taskA)
                || taskA.stopped() || aCallbacks[0] != 0) {
            throw new AssertionError(
                    "configured idle B mutated authoritative task/execution/callback A");
        }
        if (!incomingIdleB.stopped() || rejectedB.getState() != StepState.BLOCKED
                || !rejectedB.getLastLogEntry().contains(
                        "configured_idle_preserved_active_task")
                || bCallbacks[0] != 1) {
            throw new AssertionError(
                    "configured idle B did not terminalize only its own submission exactly once");
        }

        adapter.finishActiveCompletionForSelfTest(authoritativeA);
        if (aCallbacks[0] != 1
                || adapter.ownsActiveSlotForSelfTest(authoritativeA, taskA)) {
            throw new AssertionError("authoritative A completion was not retained and usable");
        }
    }

    private static void unreapedTerminalOutcomeIsPreservedBeforeReplacement() {
        assertUnreapedDecision(new FakeTrackedTask(true, true, "none"),
                StepState.SUCCEEDED, "task_finished_successfully");
        assertUnreapedDecision(new FakeTrackedTask(true, false, "repair_blocked"),
                StepState.FAILED, StopReason.FATAL.name() + ":repair_blocked");
        assertUnreapedDecision(new FakeLegacyTask(true),
                StepState.SUCCEEDED, "task_finished_normally");
        if (TaskStepExecutorAdapter.terminalDecisionBeforeReplacement(
                new FakeTrackedTask(false, false, "none")).isPresent()) {
            throw new AssertionError("live typed task was falsely terminalized before replacement");
        }
        if (TaskStepExecutorAdapter.terminalDecisionBeforeReplacement(
                new FakeLegacyTask(false)).isPresent()) {
            throw new AssertionError("live legacy task was falsely terminalized before replacement");
        }
        if (TaskStepExecutorAdapter.terminalDecisionBeforeReplacement(null).isPresent()) {
            throw new AssertionError("missing active task produced a terminal replacement outcome");
        }
    }

    private static void assertUnreapedDecision(
            Task task,
            StepState expectedState,
            String expectedDetail) {
        TaskStepExecutorAdapter.TerminalDecision decision =
                TaskStepExecutorAdapter.terminalDecisionBeforeReplacement(task)
                        .orElseThrow(() -> new AssertionError(
                                "terminal task was not recognized before replacement"));
        if (decision.state() != expectedState || !expectedDetail.equals(decision.detail())) {
            throw new AssertionError("unreaped terminal decision mismatch: " + decision);
        }
    }

    private static void forcedSubmissionOwnsTaskAndCallback() {
        FakeTrackedTask incoming = new FakeTrackedTask(false, false, "none", "same-task");
        FakeTrackedTask retained = new FakeTrackedTask(true, true, "none", "same-task");
        if (TaskStepExecutorAdapter.retentionDecision(incoming, retained, false)
                != TaskStepExecutorAdapter.RetentionDecision.DISPLACED) {
            throw new AssertionError("force-replace invariant did not reject an equal retained task");
        }
        if (TaskStepExecutorAdapter.retentionDecision(incoming, incoming, false)
                != TaskStepExecutorAdapter.RetentionDecision.SUBMITTED_INSTALLED) {
            throw new AssertionError("identity-installed task was not recognized");
        }
        if (TaskStepExecutorAdapter.retentionDecision(
                        incoming, new FakeLegacyTask(false), false)
                != TaskStepExecutorAdapter.RetentionDecision.DISPLACED) {
            throw new AssertionError("unrelated reentrant task was not classified as displacement");
        }
        if (TaskStepExecutorAdapter.retentionDecision(incoming, retained, true)
                != TaskStepExecutorAdapter.RetentionDecision.EXECUTION_TERMINAL) {
            throw new AssertionError("terminal execution was rebound after reentrant supersession");
        }
        FakeTrackedTask feedbackTask = new FakeTrackedTask(true, true, "none", "feedback-task");
        int[] feedback = {0};
        Runnable completion = TaskStepExecutorAdapter.once(() -> {
            if (feedbackTask.isTerminal() && feedbackTask.isSuccessful()) {
                feedback[0]++;
            }
        });
        if (TaskStepExecutorAdapter.retentionDecision(feedbackTask, feedbackTask, false)
                != TaskStepExecutorAdapter.RetentionDecision.SUBMITTED_INSTALLED) {
            throw new AssertionError("callback task was not identity-installed");
        }
        completion.run();
        if (feedback[0] != 1) {
            throw new AssertionError("completion did not observe the submitted task outcome");
        }
    }

    private static void assertDecision(Task task, StepState expectedState, String expectedDetail) {
        TaskStepExecutorAdapter.TerminalDecision decision =
                TaskStepExecutorAdapter.mapTerminalOutcome(task);
        if (decision.state() != expectedState || !expectedDetail.equals(decision.detail())) {
            throw new AssertionError("terminal decision mismatch: " + decision);
        }
    }

    /**
     * Cross-package harness for lifecycle tests that must drive the real adapter submit path while
     * sourcing both preflight decisions from a detached UserTaskChain.
     */
    public static final class ChainPreflightHarness extends TaskStepExecutorAdapter {
        private final BooleanSupplier reentrantGate;
        private final BooleanSupplier configuredIdleGate;

        public ChainPreflightHarness(
                BooleanSupplier reentrantGate,
                BooleanSupplier configuredIdleGate) {
            super(null);
            this.reentrantGate = reentrantGate;
            this.configuredIdleGate = configuredIdleGate;
        }

        @Override
        boolean rejectsReentrantTrackedSubmission() {
            return reentrantGate.getAsBoolean();
        }

        @Override
        boolean rejectsConfiguredIdleTrackedSubmission() {
            return configuredIdleGate.getAsBoolean();
        }

        public void seedAuthoritative(
                StepExecution execution,
                Task task,
                Runnable completion) {
            this.seedActiveExecutionForSelfTest(execution, task, completion);
        }

        public StepExecution createRunningExecution(
                String stepId, String stepKind, String reason) {
            StepExecution execution = new StepExecution(stepId, stepKind);
            execution.transitionTo(StepState.RUNNING, reason);
            return execution;
        }

        public boolean ownsAuthoritative(StepExecution execution, Task task) {
            return this.ownsActiveSlotForSelfTest(execution, task);
        }

        public void finishAuthoritative(StepExecution execution) {
            execution.transitionTo(StepState.SUCCEEDED, "self_test_task_finished");
            this.finishActiveCompletionForSelfTest(execution);
        }
    }

    private static class FakeLegacyTask extends Task {
        private final boolean finished;

        private FakeLegacyTask(boolean finished) {
            this.finished = finished;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        protected void onStart() {
        }

        @Override
        protected Task onTick() {
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return this == other;
        }

        @Override
        protected String toDebugString() {
            return "fake legacy task";
        }
    }

    private static final class FakeTrackedTask extends FakeLegacyTask
            implements TrackedTaskOutcomeProvider {
        private final boolean terminal;
        private final boolean successful;
        private final String reason;
        private final String equalityKey;

        private FakeTrackedTask(boolean terminal, boolean successful, String reason) {
            this(terminal, successful, reason, null);
        }

        private FakeTrackedTask(
                boolean terminal,
                boolean successful,
                String reason,
                String equalityKey) {
            super(false);
            this.terminal = terminal;
            this.successful = successful;
            this.reason = reason;
            this.equalityKey = equalityKey;
        }

        @Override
        public boolean isTerminal() {
            return terminal;
        }

        @Override
        public boolean isSuccessful() {
            return successful;
        }

        @Override
        public String controlledReason() {
            return reason;
        }

        @Override
        protected boolean isEqual(Task other) {
            return this == other || equalityKey != null
                    && other instanceof FakeTrackedTask task
                    && equalityKey.equals(task.equalityKey);
        }
    }
}
