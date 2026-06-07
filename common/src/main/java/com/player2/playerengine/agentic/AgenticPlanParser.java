package com.player2.playerengine.agentic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.utils.Utils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses one JSON object into {@link AgenticPlan}; extracts markdown-wrapped JSON once. */
public final class AgenticPlanParser {

    private AgenticPlanParser() {}

    public static AgenticPlan parseOrNull(String raw) {
        JsonObject json = parseJsonObject(raw);
        if (json == null) {
            return null;
        }
        return fromJson(json);
    }

    static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Utils.parseCleanedJson(trimmed);
        } catch (Exception ignored) {
            // fall through
        }
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String slice = trimmed.substring(start, i + 1);
                    try {
                        return JsonParser.parseString(slice).getAsJsonObject();
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    static AgenticPlan fromJson(JsonObject json) {
        int schemaVersion = json.has("schemaVersion") && !json.get("schemaVersion").isJsonNull()
                ? json.get("schemaVersion").getAsInt()
                : 0;
        String goalSummary = stringField(json, "goalSummary");
        String plannerNote = stringField(json, "plannerNote");
        List<AgenticStepSpec> steps = new ArrayList<>();
        if (json.has("steps") && json.get("steps").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("steps");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject stepObj = el.getAsJsonObject();
                String id = stringField(stepObj, "id");
                String kind = stringField(stepObj, "kind");
                String rationale = stringField(stepObj, "rationale");
                Map<String, String> args = new LinkedHashMap<>();
                if (stepObj.has("args") && stepObj.get("args").isJsonObject()) {
                    JsonObject argsObj = stepObj.getAsJsonObject("args");
                    for (String key : argsObj.keySet()) {
                        JsonElement val = argsObj.get(key);
                        if (val != null && val.isJsonPrimitive()) {
                            args.put(key, val.getAsString());
                        }
                    }
                }
                steps.add(new AgenticStepSpec(id, kind, Map.copyOf(args), rationale));
            }
        }
        return new AgenticPlan(schemaVersion, goalSummary, List.copyOf(steps), plannerNote);
    }

    private static String stringField(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }
}
