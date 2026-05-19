package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;

import java.util.Optional;

/**
 * Smoke tests for {@link ModelTierRouter} (B3 § 5a).
 *
 * <p>Run manually: {@code java ...ModelTierRouterSelfTest} with {@code -ea} if your JVM supports assertions.
 */
public final class ModelTierRouterSelfTest {

    private ModelTierRouterSelfTest() {}

    public static void main(String[] args) {
        int failed = 0;
        failed += run("RETRIEVAL on-device rule 1", () -> {
            RoutingResult r = resolve(AiTaskClass.RETRIEVAL, Optional.empty(), null, thresholds(100, 0), config(false));
            assertTrue(r.decision().isOnDevice(), "onDevice");
            assertEq(1, r.matchedRule(), "rule");
        });
        failed += run("dedicatedClientProxy rule 2", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.of("http://127.0.0.1:1/named"), snap(500, "gold"), thresholds(100, 0), config(true));
            assertFalse(r.decision().isOnDevice(), "not onDevice");
            assertTrue(r.decision().profileBaseUrlOverride().isEmpty(), "default");
            assertEq(2, r.matchedRule(), "rule");
        });
        failed += run("SUMMARIZATION rule 3", () -> {
            RoutingResult r = resolve(AiTaskClass.SUMMARIZATION, Optional.of("http://127.0.0.1:1/named"), snap(500, "gold"), thresholds(100, 0), config(false));
            assertEq(3, r.matchedRule(), "rule");
            assertTrue(r.decision().profileBaseUrlOverride().isEmpty(), "default");
        });
        failed += run("RERANKING rule 3", () -> {
            RoutingResult r = resolve(AiTaskClass.RERANKING, Optional.of("http://127.0.0.1:1/named"), snap(500, "gold"), thresholds(100, 0), config(false));
            assertEq(3, r.matchedRule(), "rule");
        });
        failed += run("Joules soft demotion rule 4", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.of("http://127.0.0.1:1/named"), snap(50, "gold"), thresholds(100, 0), config(false));
            assertEq(4, r.matchedRule(), "rule");
            assertTrue(r.decision().profileBaseUrlOverride().isEmpty(), "default");
        });
        failed += run("sole named profile rule 5", () -> {
            RoutingResult r = resolve(AiTaskClass.PLANNING, Optional.of("http://127.0.0.1:4315/patron"), snap(500, "gold"), thresholds(100, 0), config(false));
            assertEq(5, r.matchedRule(), "rule");
            assertEq("http://127.0.0.1:4315/patron", r.decision().profileBaseUrlOverride().orElse(""), "url");
        });
        failed += run("no named profile rule 6", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.empty(), snap(500, "gold"), thresholds(100, 0), config(false));
            assertEq(6, r.matchedRule(), "rule");
        });
        failed += run("snapshot null skips demotion (rule 5)", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.of("http://127.0.0.1:1/named"), null, thresholds(100, 0), config(false));
            assertEq(5, r.matchedRule(), "rule");
        });
        failed += run("softJoulesThreshold 0 skips demotion (rule 5)", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.of("http://127.0.0.1:1/named"), snap(1, ""), thresholds(0, 0), config(false));
            assertEq(5, r.matchedRule(), "rule");
        });
        failed += run("multi-profile empty sole → rule 6", () -> {
            RoutingResult r = resolve(AiTaskClass.PLANNING, Optional.empty(), snap(500, "gold"), thresholds(100, 0), config(false));
            assertEq(6, r.matchedRule(), "rule");
        });
        failed += run("non-patron with named still routes rule 5", () -> {
            RoutingResult r = resolve(AiTaskClass.DECISION, Optional.of("http://127.0.0.1:1/named"), snap(500, ""), thresholds(100, 0), config(false));
            assertEq(5, r.matchedRule(), "rule");
        });

        if (failed == 0) {
            System.out.println("ModelTierRouterSelfTest: PASS (" + 11 + " cases)");
        } else {
            System.err.println("ModelTierRouterSelfTest: FAIL (" + failed + " case(s) failed)");
            System.exit(1);
        }
    }

    private static RoutingResult resolve(
            AiTaskClass taskClass,
            Optional<String> soleNamed,
            JoulesCache.JoulesSnapshot snapshot,
            BudgetThresholds thresholds,
            Player2ServerRuntimeConfig serverConfig) {
        return ModelTierRouter.resolveWithRule(taskClass, soleNamed, snapshot, thresholds, serverConfig);
    }

    private static JoulesCache.JoulesSnapshot snap(long joules, String tier) {
        return JoulesCache.snapshotForProbe(joules, tier);
    }

    private static BudgetThresholds thresholds(int softJoules, int hardJoules) {
        return new BudgetThresholds() {
            @Override public int getSoftBudgetCallsPerWindow() { return 0; }
            @Override public int getHardBudgetCallsPerWindow() { return 0; }
            @Override public int getBudgetWindowMinutes() { return 60; }
            @Override public int getSoftJoulesThreshold() { return softJoules; }
            @Override public int getHardJoulesThreshold() { return hardJoules; }
            @Override public int getJoulesRefreshIntervalSeconds() { return 60; }
        };
    }

    private static Player2ServerRuntimeConfig config(boolean dedicatedProxy) {
        Player2ServerRuntimeConfig c = new Player2ServerRuntimeConfig();
        c.setDedicatedClientProxy(dedicatedProxy);
        return c;
    }

    private static int run(String name, Runnable test) {
        try {
            test.run();
            System.out.println("  OK  " + name);
            return 0;
        } catch (AssertionError e) {
            System.err.println("  FAIL " + name + ": " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("  FAIL " + name + ": " + e);
            return 1;
        }
    }

    private static void assertEq(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private static void assertEq(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected '" + expected + "' got '" + actual + "'");
        }
    }

    private static void assertTrue(boolean cond, String label) {
        if (!cond) {
            throw new AssertionError(label + ": expected true");
        }
    }

    private static void assertFalse(boolean cond, String label) {
        if (cond) {
            throw new AssertionError(label + ": expected false");
        }
    }
}
