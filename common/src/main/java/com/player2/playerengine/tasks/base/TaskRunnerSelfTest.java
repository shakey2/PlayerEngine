package com.player2.playerengine.tasks.base;

import java.util.UUID;

/** Pure checks for bounded, controller-specific scheduler diagnostics. */
public final class TaskRunnerSelfTest {
    private TaskRunnerSelfTest() {
    }

    public static void runAll() {
        UUID id = UUID.fromString("36b68a77-1111-2222-3333-444444444444");
        require("Sucrose (36b68a77)".equals(
                        TaskRunner.formatDiagnosticIdentity("Sucrose", id)),
                "diagnostic identity must include display name and short entity id");
        require("Sucrose Injected (36b68a77)".equals(
                        TaskRunner.formatDiagnosticIdentity("Sucrose\nInjected", id)),
                "diagnostic display name must be single-line");
        require("A companion name that is deliber (36b68a77)".equals(
                        TaskRunner.formatDiagnosticIdentity(
                                "A companion name that is deliberately much too long", id)),
                "diagnostic display name must be bounded");
        require("unknown (unknown)".equals(
                        TaskRunner.formatDiagnosticIdentity(null, null)),
                "missing controller identity must degrade safely");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
