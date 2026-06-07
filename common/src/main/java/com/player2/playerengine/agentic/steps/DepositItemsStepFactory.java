package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.agentic.DepositItemsTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link DepositItemsTask} from a validated {@code deposit_items} step spec.
 *
 * <p>Arg parsing mirrors {@link ResolveStorageChestStepFactory}: case/underscore-insensitive
 * lookup, clamped doubles, lenient bool parsing. {@code itemIds} accepts a comma- or
 * space-separated list.
 */
public final class DepositItemsStepFactory implements AgenticStepFactory {

    // Timeout clamp bounds mirror PlayerEngineSettings.getAgenticDepositTimeoutSeconds().
    private static final double MIN_TIMEOUT_SECONDS = 10.0;
    private static final double MAX_TIMEOUT_SECONDS = 300.0;
    private static final boolean DEFAULT_DEPOSIT_ALL = true;

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();
        List<String> itemIds = parseIdList(args, "itemids");
        DepositItemsParams params = new DepositItemsParams(
                itemIds,
                parseBool(args, "depositall", DEFAULT_DEPOSIT_ALL),
                parseBool(args, "keeptools", settings.isAgenticDepositKeepTools()),
                parseDouble(args, "timeoutseconds", settings.getAgenticDepositTimeoutSeconds(),
                        MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS));
        return Optional.of(new DepositItemsTask(params, context));
    }

    private static List<String> parseIdList(Map<String, String> args, String key) {
        String raw = lookup(args, key);
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split("[,;\\s]+")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static double parseDouble(Map<String, String> args, String key, double def, double min, double max) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(raw)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(Map<String, String> args, String key, boolean def) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("1") || lower.equals("yes");
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
