package com.player2.playerengine.chains;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskChain;
import com.player2.playerengine.tasks.base.TaskRunner;

/** Detached lifecycle checks for UnstuckChain recovery-priority ownership. */
public final class UnstuckChainSelfTest {
    private UnstuckChainSelfTest() {
    }

    public static void runAll() {
        recoveryPriorityTracksOwnedLifecycle();
        neverFinishingRecoveryExpiresAndBacksOff();
        equalReselectionDoesNotRenewLease();
        powderSnowShimmyHasLiveExit();
        terminalRecoveryGetsOneReapTick();
    }

    private static void recoveryPriorityTracksOwnedLifecycle() {
        UnstuckChain.RecoveryLease lease = new UnstuckChain.RecoveryLease(3, 2);
        require(lease.decision(null) == UnstuckChain.RecoveryPriorityDecision.RELEASE,
                "null recovery must release priority");

        FakeRecovery recovery = new FakeRecovery();
        recovery.reset();
        lease.begin(recovery, false);
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.RETAIN,
                "assigned recovery must retain priority before its first tick");

        recovery.tick(new PassiveChain());
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.RETAIN,
                "active recovery must retain priority");

        recovery.finish();
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.REAP,
                "finished-but-unreaped recovery must retain priority for cleanup");

        recovery.stop();
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.REAP,
                "stopped recovery must retain exactly the scheduler reap turn");
    }

    private static void neverFinishingRecoveryExpiresAndBacksOff() {
        UnstuckChain.RecoveryLease lease = new UnstuckChain.RecoveryLease(3, 2);
        FakeRecovery recovery = new FakeRecovery();
        recovery.reset();
        recovery.tick(new PassiveChain());
        lease.begin(recovery, false);

        for (int i = 0; i < 3; i++) {
            require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.RETAIN,
                    "active recovery must retain priority while execution lease remains");
            lease.consumeExecutionTick(recovery);
        }
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.EXPIRE,
                "never-finishing active recovery must expire at the lease boundary");

        lease.markExpired(recovery);
        recovery.stop();
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.REAP,
                "expired recovery must receive one terminal reap turn");
        lease.release(recovery);
        require(lease.consumeCooldownTick(), "first cooldown tick must suppress reselection");
        require(lease.consumeCooldownTick(), "second cooldown tick must suppress reselection");
        require(!lease.consumeCooldownTick(), "cooldown must eventually release detection");
    }

    private static void equalReselectionDoesNotRenewLease() {
        UnstuckChain.RecoveryLease lease = new UnstuckChain.RecoveryLease(3, 2);
        FakeRecovery recovery = new FakeRecovery();
        recovery.reset();
        lease.begin(recovery, false);
        lease.consumeExecutionTick(recovery);
        int remaining = lease.executionTicksRemaining();

        lease.begin(recovery, true);
        require(lease.executionTicksRemaining() == remaining,
                "reselecting the same recovery identity must not renew its lease");
        require(lease.isPowderSnowShimmy(recovery),
                "an equality-preserved recovery may still gain its live-exit tag");
    }

    private static void powderSnowShimmyHasLiveExit() {
        require(!UnstuckChain.powderSnowShimmyShouldStop(true, true),
                "powder-snow shimmy must continue while the hazard remains");
        require(UnstuckChain.powderSnowShimmyShouldStop(true, false),
                "powder-snow shimmy must stop as soon as the entity exits powder snow");
        require(!UnstuckChain.powderSnowShimmyShouldStop(false, false),
                "non-powder recovery must not inherit the powder-snow exit rule");
    }

    private static void terminalRecoveryGetsOneReapTick() {
        UnstuckChain chain = new UnstuckChain(new TaskRunner(null));
        FakeRecovery recovery = new FakeRecovery();
        chain.setTask(recovery);
        chain.tick();
        recovery.stop();
        UnstuckChain.RecoveryLease lease = new UnstuckChain.RecoveryLease(3, 2);
        lease.begin(recovery, false);
        require(lease.decision(recovery) == UnstuckChain.RecoveryPriorityDecision.REAP,
                "stopped terminal recovery must still own the scheduler before reap");

        chain.tick();
        require(recovery.stopped() && chain.getCurrentTask() == null,
                "terminal reap tick must stop and clear recovery ownership");
        require(lease.decision(chain.getCurrentTask()) == UnstuckChain.RecoveryPriorityDecision.RELEASE,
                "reaped recovery cannot permanently capture priority");

        FakeRecovery finished = new FakeRecovery();
        chain.setTask(finished);
        chain.tick();
        finished.finish();
        chain.tick();
        require(finished.stopped() && chain.getCurrentTask() == null,
                "finished terminal recovery must also stop and clear in one reap tick");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeRecovery extends Task {
        private boolean finished;

        private void finish() {
            finished = true;
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
        public boolean isFinished() {
            return finished;
        }

        @Override
        protected boolean isEqual(Task other) {
            return this == other;
        }

        @Override
        protected String toDebugString() {
            return "unstuck recovery self-test";
        }
    }

    private static final class PassiveChain extends TaskChain {
        private PassiveChain() {
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
            return 0.0F;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public String getName() {
            return "unstuck passive self-test";
        }
    }
}
