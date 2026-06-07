package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.agentic.ResolveStorageChestTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ResolveStorageChestStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();
        ResolveStorageChestParams params = new ResolveStorageChestParams(
                parseDouble(args, "searchradius", settings.getAgenticStorageSearchRadius(), 4.0, 64.0),
                parseDouble(args, "placementradius", settings.getAgenticStoragePlacementRadius(), 2.0, 16.0),
                parseBool(args, "preferexisting", settings.isAgenticStoragePreferExisting()),
                parseBool(args, "allowplacement", settings.isAgenticStorageAllowPlacement()),
                parseBool(args, "avoidlootchests", settings.isAgenticStorageAvoidLootChests()),
                parseDouble(args, "timeoutseconds", settings.getAgenticStorageResolveTimeoutSeconds(), 10.0, 300.0));
        return Optional.of(new ResolveStorageChestTask(params, context));
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
