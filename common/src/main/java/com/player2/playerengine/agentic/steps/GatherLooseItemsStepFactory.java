package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.agentic.GatherLooseItemsTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GatherLooseItemsStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();
        GatherLooseItemsParams params = new GatherLooseItemsParams(
                parseDouble(args, "radius", settings.getGatherLooseItemsRadius(), 2.0, 64.0),
                parseInt(args, "maxitems", settings.getGatherLooseItemsMaxItems(), 1, 1024),
                parseInt(args, "maxstacks", 256, 1, 256),
                parseDouble(args, "settleseconds", settings.getGatherLooseItemsSettleSeconds(), 0.5, 20.0),
                parseDouble(args, "timeoutseconds", settings.getGatherLooseItemsTimeoutSeconds(), 5.0, 300.0),
                parseBool(args, "freeinventoryiffull", true),
                parseItemFilters(args.get("itemfilters")));
        return Optional.of(new GatherLooseItemsTask(params, context.runState()));
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

    private static int parseInt(Map<String, String> args, String key, int def, int min, int max) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw)));
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

    private static List<String> parseItemFilters(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
