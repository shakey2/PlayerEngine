package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.agentic.LabelChestTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the optional best-effort {@code label_chest} step (Part C3, Workstream 3). Mirrors
 * {@link ResolveStorageChestStepFactory}'s case/underscore-insensitive arg lookup and clamped
 * timeout parsing. The label timeout default/clamp comes from {@link PlayerEngineSettings}.
 */
public final class LabelChestStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();
        LabelChestParams params = new LabelChestParams(
                parseLines(args),
                parseBool(args, "autolabel", true),
                parseString(args, "signitemid", ""),
                parseDouble(args, "timeoutseconds", settings.getAgenticLabelTimeoutSeconds(), 10.0, 180.0));
        return Optional.of(new LabelChestTask(params, context));
    }

    /**
     * Collects explicit front-side label lines. Supports either a single comma-separated
     * {@code lines} arg or numbered {@code line0..line3} args. Returns up to four trimmed,
     * non-null lines; an empty list means "derive the deterministic template".
     */
    private static List<String> parseLines(Map<String, String> args) {
        String csv = lookup(args, "lines");
        List<String> out = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                out.add(part.trim());
                if (out.size() >= 4) {
                    break;
                }
            }
            return List.copyOf(out);
        }
        for (int i = 0; i < 4; i++) {
            String v = lookup(args, "line" + i);
            if (v == null) {
                break;
            }
            out.add(v.trim());
        }
        return List.copyOf(out);
    }

    private static String parseString(Map<String, String> args, String key, String def) {
        String raw = lookup(args, key);
        return raw != null ? raw.trim() : def;
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
