package com.player2.playerengine.agentic;

import java.util.List;

public record AgenticPlanValidationResult(
        boolean valid,
        AgenticPlan sanitizedPlan,
        List<String> warnings,
        List<String> errors
) {
    public static AgenticPlanValidationResult invalid(List<String> errors) {
        return new AgenticPlanValidationResult(false, null, List.of(), List.copyOf(errors));
    }

    public static AgenticPlanValidationResult invalid(List<String> errors, List<String> warnings) {
        return new AgenticPlanValidationResult(false, null, List.copyOf(warnings), List.copyOf(errors));
    }

    public static AgenticPlanValidationResult ok(AgenticPlan plan, List<String> warnings) {
        return new AgenticPlanValidationResult(true, plan, List.copyOf(warnings), List.of());
    }
}
