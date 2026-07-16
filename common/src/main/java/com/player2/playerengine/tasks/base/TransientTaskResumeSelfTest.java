package com.player2.playerengine.tasks.base;

/** Server-free lifecycle checks for the explicit transient-resume contract. */
public final class TransientTaskResumeSelfTest {
    private TransientTaskResumeSelfTest() {
    }

    public static void runAll() {
        transientInterruptUsesCheckpointHook();
        abandonedResumeUsesTerminalHook();
        deniedCheckpointFallsBackToTerminalStop();
        ordinaryStopNeverUsesResumeHooks();
        preStartDiscardUsesAbandonHook();
        childCleanupPrecedesRootFrameRelease();
    }

    private static void transientInterruptUsesCheckpointHook() {
        FakeResumableTask task = new FakeResumableTask(true);
        FakeChain chain = new FakeChain();
        task.tick(chain);
        task.interrupt(null, TaskSuspensionCause.HIGHER_PRIORITY_CHAIN);
        require(task.prepareCalls == 1, "priority interruption prepares one checkpoint");
        require(task.stopCalls == 0 && task.abandonCalls == 0,
                "transient interruption avoids terminal hooks");
        require(task.isTransientlySuspended(), "task exposes prepared suspension state");

        task.reset();
        task.tick(chain);
        require(task.startCalls == 2, "same logical task restarts after checkpoint");
        require(!task.isTransientlySuspended(), "reset consumes framework suspension state");
    }

    private static void abandonedResumeUsesTerminalHook() {
        FakeResumableTask task = new FakeResumableTask(true);
        task.tick(new FakeChain());
        task.interrupt(null, TaskSuspensionCause.GESTURE_OVERLAY);
        task.abandonTransientResume();
        require(task.abandonCalls == 1, "discarded checkpoint terminalizes exactly once");
        require(task.stopCalls == 0, "discarded checkpoint does not replay ordinary stop cleanup");
        require(task.stopped() && !task.isTransientlySuspended(),
                "discarded task cannot resume later");
    }

    private static void deniedCheckpointFallsBackToTerminalStop() {
        FakeResumableTask task = new FakeResumableTask(false);
        task.tick(new FakeChain());
        task.interrupt(null, TaskSuspensionCause.HIGHER_PRIORITY_CHAIN);
        require(task.prepareCalls == 1, "denied checkpoint was consulted");
        require(task.stopCalls == 1, "denied checkpoint runs terminal stop behavior");
        require(task.stopped() && !task.isActive() && !task.isTransientlySuspended(),
                "denied checkpoint is sealed for chain reap instead of silently restarting");
    }

    private static void ordinaryStopNeverUsesResumeHooks() {
        FakeResumableTask task = new FakeResumableTask(true);
        task.tick(new FakeChain());
        task.stop();
        require(task.stopCalls == 1, "ordinary stop remains terminal");
        require(task.prepareCalls == 0 && task.abandonCalls == 0,
                "ordinary stop never enters transient lifecycle");
    }

    private static void preStartDiscardUsesAbandonHook() {
        FakeResumableTask task = new FakeResumableTask(true);
        task.reset();
        require(task.isAssigned(), "reset records chain ownership before first tick");
        task.stop();
        require(task.abandonCalls == 1 && task.stopCalls == 0 && task.stopped(),
                "discarding an assigned resumable task yields one typed terminal hook");
    }

    private static void childCleanupPrecedesRootFrameRelease() {
        java.util.List<String> order = new java.util.ArrayList<>();
        OrderTrackingChild child = new OrderTrackingChild(order);
        OrderTrackingRoot root = new OrderTrackingRoot(order, child);
        root.tick(new FakeChain());
        root.interrupt(null, TaskSuspensionCause.HIGHER_PRIORITY_CHAIN);
        require(order.equals(java.util.List.of("prepare", "child", "root")),
                "nested cleanup preserves child-before-root LIFO ownership");
        require(child.stopped() && !child.isActive(),
                "detached child is sealed at framework level after cleanup");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeResumableTask extends Task
            implements TransientlyResumableTask {
        private final boolean allowCheckpoint;
        private int startCalls;
        private int stopCalls;
        private int prepareCalls;
        private int abandonCalls;

        private FakeResumableTask(boolean allowCheckpoint) {
            this.allowCheckpoint = allowCheckpoint;
        }

        @Override
        protected void onStart() {
            startCalls++;
        }

        @Override
        protected Task onTick() {
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
            stopCalls++;
        }

        @Override
        public boolean prepareForTransientResume(TaskSuspensionCause cause) {
            prepareCalls++;
            return allowCheckpoint;
        }

        @Override
        public void onTransientResumeAbandoned() {
            abandonCalls++;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "transient resume self-test";
        }
    }

    private static final class FakeChain extends TaskChain {
        private FakeChain() {
            super(new TaskRunner(null));
        }

        @Override
        protected void onStop() {
        }

        @Override
        public void onInterrupt(TaskChain other) {
        }

        @Override
        protected void onTick() {
        }

        @Override
        public float getPriority() {
            return 0;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public String getName() {
            return "Transient resume self-test";
        }
    }

    private static final class OrderTrackingRoot extends Task
            implements TransientlyResumableTask {
        private final java.util.List<String> order;
        private final OrderTrackingChild child;

        private OrderTrackingRoot(java.util.List<String> order, OrderTrackingChild child) {
            this.order = order;
            this.child = child;
        }

        @Override
        protected void onStart() {
        }

        @Override
        protected Task onTick() {
            return child;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean prepareForTransientResume(TaskSuspensionCause cause) {
            order.add("prepare");
            return true;
        }

        @Override
        public void onTransientResumeAbandoned() {
        }

        @Override
        public void afterChildrenStopped() {
            order.add("root");
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "order-tracking root";
        }
    }

    private static final class OrderTrackingChild extends Task {
        private final java.util.List<String> order;

        private OrderTrackingChild(java.util.List<String> order) {
            this.order = order;
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
            order.add("child");
        }

        @Override
        protected boolean isEqual(Task other) {
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "order-tracking child";
        }
    }
}
