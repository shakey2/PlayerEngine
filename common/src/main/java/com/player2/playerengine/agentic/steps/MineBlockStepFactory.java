package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.agentic.MineBlockParams;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link MineBlockTask} from a {@code mine_block} agentic step spec (WS4).
 *
 * <p>Args are parsed case/underscore-insensitively and clamped to the documented safe ranges (mirrors
 * {@code GatherLooseItemsStepFactory}). The required {@code blockId} (alias {@code block}) must be a
 * non-blank registry id or tag token; a blank/missing value yields an empty {@link Optional} so the
 * planner step is rejected upstream rather than constructing a task that can only fail.
 *
 * <p>Numeric defaults/clamps come from the plan's config table:
 * {@code radius 32.0 [4..128]}, {@code maxBlocks 16 [1..256]}, {@code timeoutSeconds 120 [10..600]},
 * {@code toolAcquireMaxContainers 8 [1..32]}, {@code toolAcquireClimbBudget 6 [1..16]}.
 */
public final class MineBlockStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        Map<String, String> args = step.args() != null ? step.args() : Map.of();

        String blockId = firstNonBlank(lookup(args, "blockid"), lookup(args, "block"));
        if (blockId == null || blockId.isBlank()) {
            return Optional.empty();
        }

        MineBlockParams params = new MineBlockParams(
                blockId.trim(),
                parseInt(args, "maxblocks", 16, 1, 256),
                parseDouble(args, "radius", 32.0, 4.0, 128.0),
                parseDouble(args, "timeoutseconds", 120.0, 10.0, 600.0),
                parseInt(args, "toolacquiremaxcontainers", 8, 1, 32),
                parseInt(args, "toolacquireclimbbudget", 6, 1, 16));

        return Optional.of(new MineBlockTask(params, context.runState(), context.reservations()));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static double parseDouble(Map<String, String> args, String key, double def, double min, double max) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(Map<String, String> args, String key, int def, int min, int max) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String lookup(Map<String, String> args, String key) {
        if (args == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        for (Map.Entry<String, String> e : args.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            if (k.equals(normalized)) {
                return e.getValue();
            }
        }
        return null;
    }
}
