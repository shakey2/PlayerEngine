package com.player2.playerengine.player2api;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pure checks for local idle-command routing and terminal ownership. */
public final class AgentSideEffectsSelfTest {
    private AgentSideEffectsSelfTest() {
    }

    public static void runAll() {
        idleRoutingUsesExactCommandId();
        mixedIdleChainsAreRejectedInEitherOrder();
        handledAndIgnoredIdleResolveExactlyOnce();
        rejectedMixedIdleResolvesExactlyOnceAsError();
    }

    private static void idleRoutingUsesExactCommandId() {
        require(AgentSideEffects.classifyIdleHandling("idle", true, true)
                        == AgentSideEffects.IdleHandling.IGNORE_ACTIVE_TASK,
                "idle must not replace active user work");
        require(AgentSideEffects.classifyIdleHandling("idle", false, true)
                        == AgentSideEffects.IdleHandling.INSTALL_LOOK_AT_OWNER,
                "enabled idle policy must install LookAtOwner");
        require(AgentSideEffects.classifyIdleHandling("idle", false, false)
                        == AgentSideEffects.IdleHandling.LEAVE_WITHOUT_USER_TASK,
                "disabled idle policy must leave the user chain empty");
        require(AgentSideEffects.classifyIdleHandling("setup_farm_idle", false, true)
                        == AgentSideEffects.IdleHandling.NOT_IDLE,
                "command names containing idle must not be mistaken for @idle");
        require(AgentSideEffects.classifyIdleHandling(null, false, true)
                        == AgentSideEffects.IdleHandling.NOT_IDLE,
                "missing command id is not idle");
    }

    private static void handledAndIgnoredIdleResolveExactlyOnce() {
        assertOneFinishedTerminal(AgentSideEffects.IdleHandling.IGNORE_ACTIVE_TASK);
        assertOneFinishedTerminal(AgentSideEffects.IdleHandling.INSTALL_LOOK_AT_OWNER);
        assertOneFinishedTerminal(AgentSideEffects.IdleHandling.LEAVE_WITHOUT_USER_TASK);
    }

    private static void mixedIdleChainsAreRejectedInEitherOrder() {
        require(AgentSideEffects.classifyIdleCommandLine("@idle", "@")
                        == AgentSideEffects.IdleCommandShape.SOLE_IDLE,
                "standalone idle must retain local terminal handling");
        require(AgentSideEffects.classifyIdleCommandLine("@idle; setup_farm", "@")
                        == AgentSideEffects.IdleCommandShape.MIXED_IDLE,
                "idle before an action must be rejected instead of dropping that action");
        require(AgentSideEffects.classifyIdleCommandLine("@setup_farm; idle", "@")
                        == AgentSideEffects.IdleCommandShape.MIXED_IDLE,
                "idle after an action must be rejected instead of hanging chain completion");
        require(AgentSideEffects.classifyIdleCommandLine("@setup_farm_idle", "@")
                        == AgentSideEffects.IdleCommandShape.NO_IDLE,
                "an id containing idle is not the idle command");
        require(AgentSideEffects.classifyIdleCommandLine("@setup_farm; @idle", "@")
                        == AgentSideEffects.IdleCommandShape.MIXED_IDLE,
                "a repeated prefix cannot bypass mixed-idle rejection");
    }

    private static void rejectedMixedIdleResolvesExactlyOnceAsError() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<AgentSideEffects.CommandExecutionStopReason> terminal =
                new AtomicReference<>();
        AgentSideEffects.completeRejectedMixedIdle(reason -> {
            callbacks.incrementAndGet();
            terminal.set(reason);
        });
        require(callbacks.get() == 1
                        && terminal.get() instanceof AgentSideEffects.CommandExecutionStopReason.Error error
                        && "idle".equals(error.commandName())
                        && error.errMsg().contains("cannot be combined"),
                "mixed idle must resolve once with bounded actionable model feedback");
    }

    private static void assertOneFinishedTerminal(AgentSideEffects.IdleHandling handling) {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<AgentSideEffects.CommandExecutionStopReason> terminal =
                new AtomicReference<>();
        AgentSideEffects.completeHandledIdle(handling, "@idle", reason -> {
            callbacks.incrementAndGet();
            terminal.set(reason);
        });
        require(callbacks.get() == 1
                        && terminal.get() instanceof AgentSideEffects.CommandExecutionStopReason.Finished finished
                        && "@idle".equals(finished.commandName()),
                "locally-handled idle must resolve once as Finished: " + handling);
        AgentSideEffects.CommandExecutionStopReason.Finished finishedResult =
                (AgentSideEffects.CommandExecutionStopReason.Finished) terminal.get();
        if (handling == AgentSideEffects.IdleHandling.IGNORE_ACTIVE_TASK) {
            require(finishedResult.note() != null
                            && finishedResult.note().contains("real user task is still active")
                            && finishedResult.note().contains("use stop")
                            && finishedResult.note().length() <= 160,
                    "ignored idle must report one bounded degradation with cancellation guidance");
        } else {
            require(finishedResult.note() == null,
                    "installed/empty idle handling must remain an unqualified success");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
