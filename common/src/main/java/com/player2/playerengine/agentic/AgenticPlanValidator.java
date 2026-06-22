package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict validation and sanitization for schema-v1 agentic plans (C2). */
public final class AgenticPlanValidator {

    private static final Pattern STEP_ID = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final int GOAL_SUMMARY_MAX = 120;
    private static final int RATIONALE_MAX = 180;
    private static final int PLANNER_NOTE_MAX = 180;

    private AgenticPlanValidator() {}

    public static AgenticPlanValidationResult validate(AgenticPlan plan, PlayerEngineSettings settings) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (plan == null) {
            return AgenticPlanValidationResult.invalid(List.of("plan_missing"));
        }
        if (plan.schemaVersion() != AgenticSchemas.PLAN_SCHEMA_VERSION) {
            errors.add(plan.schemaVersion() > AgenticSchemas.PLAN_SCHEMA_VERSION
                    ? "unsupported_schema"
                    : "missing_or_invalid_schema_version");
            return AgenticPlanValidationResult.invalid(errors);
        }
        String goalSummary = sanitizeSingleLine(plan.goalSummary(), GOAL_SUMMARY_MAX, "goal_summary", errors);
        if (goalSummary == null && errors.isEmpty()) {
            errors.add("goal_summary_required");
        }
        String plannerNote = sanitizeOptionalSingleLine(plan.plannerNote(), PLANNER_NOTE_MAX, warnings);

        List<AgenticStepSpec> steps = plan.steps();
        if (steps == null || steps.isEmpty()) {
            errors.add("steps_required");
            return AgenticPlanValidationResult.invalid(errors, warnings);
        }
        int maxSteps = settings.getAgenticPlannerMaxSteps();
        if (steps.size() > maxSteps) {
            errors.add("too_many_steps");
            return AgenticPlanValidationResult.invalid(errors, warnings);
        }

        List<AgenticStepSpec> sanitizedSteps = new ArrayList<>();
        Set<String> seenKinds = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            AgenticStepSpec step = steps.get(i);
            if (step == null) {
                errors.add("step_" + i + "_null");
                continue;
            }
            String id = sanitizeStepId(step.id(), errors, i);
            String kind = step.kind() != null ? step.kind().trim() : "";
            String kindLower = kind.toLowerCase(Locale.ROOT);
            if (AgenticSchemas.FORBIDDEN_FUTURE_STEP_KINDS.contains(kindLower)
                    || kindLower.contains("elliegps")
                    || kindLower.contains("waypoint")) {
                errors.add("forbidden_step_kind:" + kind);
                continue;
            }
            if (!AgenticSchemas.ALLOWED_STEP_KINDS.contains(kind)) {
                errors.add("unknown_step_kind:" + kind);
                continue;
            }
            if (!seenKinds.add(kind)) {
                errors.add("duplicate_step_kind:" + kind);
                continue;
            }
            Map<String, String> args = sanitizeArgs(step.args());
            String rationale = sanitizeOptionalSingleLine(step.rationale(), RATIONALE_MAX, warnings);
            if (id != null) {
                sanitizedSteps.add(new AgenticStepSpec(id, kind, args, rationale));
            }
        }
        if (!errors.isEmpty()) {
            return AgenticPlanValidationResult.invalid(errors, warnings);
        }
        if (sanitizedSteps.isEmpty()) {
            return AgenticPlanValidationResult.invalid(List.of("no_valid_steps"), warnings);
        }
        String sequenceError = validateSequence(sanitizedSteps);
        if (sequenceError != null) {
            errors.add(sequenceError);
            return AgenticPlanValidationResult.invalid(errors, warnings);
        }
        AgenticPlan sanitized = new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                goalSummary,
                List.copyOf(sanitizedSteps),
                plannerNote);
        return AgenticPlanValidationResult.ok(sanitized, warnings);
    }

    /**
     * Accepts exactly the ordered step sequences:
     * <pre>
     * [gather_loose_items]
     * [resolve_storage_chest]
     * [gather_loose_items, resolve_storage_chest]
     * [resolve_storage_chest, deposit_items]
     * [gather_loose_items, resolve_storage_chest, deposit_items]
     * [resolve_storage_chest, deposit_items, label_chest]
     * [gather_loose_items, resolve_storage_chest, deposit_items, label_chest]
     * [mine_block]
     * [gather_loose_items, mine_block]
     * [mine_block, gather_loose_items]
     * [mine_block, resolve_storage_chest, deposit_items]
     * [mine_block, resolve_storage_chest, deposit_items, label_chest]
     * </pre>
     * A lone [deposit_items] (no resolved target) and any label_chest that is
     * not last / not preceded by deposit_items are rejected. Duplicate kinds and
     * max-step caps are already enforced by the caller.
     *
     * <p>mine_block sequences: bare mine ([m]) and gather+mine (both orders) are valid
     * terminal plans — mined materials stay in inventory, no auto-store is implied.
     * Explicit-store chains ([m,r,d] and [m,r,d,l]) are admitted only when the plan
     * explicitly requests storage. [m,r] (resolve-without-deposit) and any
     * gather+mine+store quadruple are intentionally excluded to keep the matrix bounded.
     */
    private static String validateSequence(List<AgenticStepSpec> steps) {
        List<String> kinds = new ArrayList<>(steps.size());
        for (AgenticStepSpec step : steps) {
            kinds.add(step.kind());
        }
        String g = AgenticSchemas.STEP_GATHER_LOOSE_ITEMS;
        String r = AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST;
        String d = AgenticSchemas.STEP_DEPOSIT_ITEMS;
        String l = AgenticSchemas.STEP_LABEL_CHEST;
        String m = AgenticSchemas.STEP_MINE_BLOCK;
        List<List<String>> allowed = List.of(
                List.of(g),
                List.of(r),
                List.of(g, r),
                List.of(r, d),
                List.of(g, r, d),
                List.of(r, d, l),
                List.of(g, r, d, l),
                List.of(m),
                List.of(g, m),
                List.of(m, g),
                List.of(m, r, d),
                List.of(m, r, d, l));
        for (List<String> seq : allowed) {
            if (seq.equals(kinds)) {
                return null;
            }
        }
        return "invalid_step_sequence";
    }

    private static String sanitizeStepId(String raw, List<String> errors, int index) {
        if (raw == null || raw.isBlank()) {
            errors.add("step_" + index + "_id_required");
            return null;
        }
        String id = raw.trim();
        if (!STEP_ID.matcher(id).matches()) {
            errors.add("step_" + index + "_id_invalid");
            return null;
        }
        return id;
    }

    private static Map<String, String> sanitizeArgs(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            String key = e.getKey().trim().toLowerCase(Locale.ROOT);
            String val = e.getValue() != null ? e.getValue().trim() : "";
            if (!val.isEmpty()) {
                out.put(key, val);
            }
        }
        return Map.copyOf(out);
    }

    private static String sanitizeSingleLine(String raw, int maxLen, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(field + "_required");
            return null;
        }
        String line = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (line.length() > maxLen) {
            line = line.substring(0, maxLen);
        }
        return line;
    }

    private static String sanitizeOptionalSingleLine(String raw, int maxLen, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (line.length() > maxLen) {
            warnings.add("truncated_field");
            line = line.substring(0, maxLen);
        }
        return line;
    }
}
