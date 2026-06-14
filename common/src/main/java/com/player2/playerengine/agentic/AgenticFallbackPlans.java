package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineSettings;
import java.util.List;
import java.util.Map;

/** Deterministic plans when Player2 planning is unavailable. */
public final class AgenticFallbackPlans {

    private AgenticFallbackPlans() {}

    public static AgenticPlan gatherLooseItemsPlan(String goalText, PlayerEngineSettings settings) {
        String summary = goalText != null && !goalText.isBlank()
                ? truncate(goalText.replace('\n', ' '), 120)
                : "Gather nearby dropped items";
        Map<String, String> args = Map.of(
                "radius", String.valueOf(settings.getGatherLooseItemsRadius()),
                "maxitems", String.valueOf(settings.getGatherLooseItemsMaxItems()),
                "settleseconds", String.valueOf(settings.getGatherLooseItemsSettleSeconds()),
                "timeoutseconds", String.valueOf(settings.getGatherLooseItemsTimeoutSeconds()));
        AgenticStepSpec step = new AgenticStepSpec(
                "gather-1",
                AgenticSchemas.STEP_GATHER_LOOSE_ITEMS,
                args,
                "Deterministic fallback for nearby drop pickup.");
        return new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                summary,
                List.of(step),
                "fallback_gather");
    }

    public static AgenticPlan resolveStorageChestPlan(String goalText, PlayerEngineSettings settings) {
        String summary = goalText != null && !goalText.isBlank()
                ? truncate(goalText.replace('\n', ' '), 120)
                : "Resolve storage chest";
        Map<String, String> args = storageArgs(settings);
        return new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                summary,
                List.of(new AgenticStepSpec(
                        "storage-1",
                        AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST,
                        args,
                        "Deterministic fallback for storage preparation.")),
                "fallback_storage");
    }

    public static AgenticPlan gatherThenResolvePlan(String goalText, PlayerEngineSettings settings) {
        String summary = goalText != null && !goalText.isBlank()
                ? truncate(goalText.replace('\n', ' '), 120)
                : "Gather drops and prepare storage";
        List<AgenticStepSpec> steps = List.of(
                gatherStep(settings),
                new AgenticStepSpec(
                        "storage-1",
                        AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST,
                        storageArgs(settings),
                        "Prepare a chest for storage."));
        return new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                summary,
                steps,
                "fallback_gather_and_storage");
    }

    public static AgenticPlan resolveThenDepositPlan(String goalText, PlayerEngineSettings settings, boolean withLabel) {
        String summary = goalText != null && !goalText.isBlank()
                ? truncate(goalText.replace('\n', ' '), 120)
                : "Store items in a chest";
        List<AgenticStepSpec> steps = new java.util.ArrayList<>();
        steps.add(new AgenticStepSpec(
                "storage-1",
                AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST,
                storageArgs(settings),
                "Prepare a chest for storage."));
        steps.add(new AgenticStepSpec(
                "deposit-1",
                AgenticSchemas.STEP_DEPOSIT_ITEMS,
                depositArgs(settings),
                "Deposit the selected items into the resolved chest."));
        if (withLabel && settings.isAgenticEnableLabelChest()) {
            steps.add(labelStep(settings));
        }
        return new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                summary,
                List.copyOf(steps),
                withLabel ? "fallback_resolve_deposit_label" : "fallback_resolve_deposit");
    }

    public static AgenticPlan gatherResolveDepositPlan(String goalText, PlayerEngineSettings settings, boolean withLabel) {
        String summary = goalText != null && !goalText.isBlank()
                ? truncate(goalText.replace('\n', ' '), 120)
                : "Gather drops and store them in a chest";
        List<AgenticStepSpec> steps = new java.util.ArrayList<>();
        steps.add(gatherStep(settings));
        steps.add(new AgenticStepSpec(
                "storage-1",
                AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST,
                storageArgs(settings),
                "Prepare a chest for storage."));
        steps.add(new AgenticStepSpec(
                "deposit-1",
                AgenticSchemas.STEP_DEPOSIT_ITEMS,
                depositArgs(settings),
                "Deposit the gathered items into the resolved chest."));
        if (withLabel && settings.isAgenticEnableLabelChest()) {
            steps.add(labelStep(settings));
        }
        return new AgenticPlan(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                summary,
                List.copyOf(steps),
                withLabel ? "fallback_gather_resolve_deposit_label" : "fallback_gather_resolve_deposit");
    }

    /**
     * The standard settings-driven gather step ("gather-1"). Shared by the deterministic fallback
     * plans above and by {@code AgenticPlannerService}'s gather augmentation of accepted model
     * plans (deterministic-over-model guard: deposit plan + drops on the ground + nothing
     * depositable in inventory means a gather step must run first).
     */
    static AgenticStepSpec gatherStep(PlayerEngineSettings settings) {
        return new AgenticStepSpec(
                "gather-1",
                AgenticSchemas.STEP_GATHER_LOOSE_ITEMS,
                Map.of(
                        "radius", String.valueOf(settings.getGatherLooseItemsRadius()),
                        "maxitems", String.valueOf(settings.getGatherLooseItemsMaxItems()),
                        "settleseconds", String.valueOf(settings.getGatherLooseItemsSettleSeconds()),
                        "timeoutseconds", String.valueOf(settings.getGatherLooseItemsTimeoutSeconds())),
                "Collect nearby drops.");
    }

    private static AgenticStepSpec labelStep(PlayerEngineSettings settings) {
        return new AgenticStepSpec(
                "label-1",
                AgenticSchemas.STEP_LABEL_CHEST,
                Map.of(
                        "autolabel", "true",
                        "timeoutseconds", String.valueOf(settings.getAgenticLabelTimeoutSeconds())),
                "Label the storage chest (best-effort).");
    }

    private static Map<String, String> depositArgs(PlayerEngineSettings settings) {
        return Map.of(
                "depositall", "true",
                "keeptools", String.valueOf(settings.isAgenticDepositKeepTools()),
                "timeoutseconds", String.valueOf(settings.getAgenticDepositTimeoutSeconds()));
    }

    private static Map<String, String> storageArgs(PlayerEngineSettings settings) {
        return Map.of(
                "searchradius", String.valueOf(settings.getAgenticStorageSearchRadius()),
                "placementradius", String.valueOf(settings.getAgenticStoragePlacementRadius()),
                "preferexisting", String.valueOf(settings.isAgenticStoragePreferExisting()),
                "allowplacement", String.valueOf(settings.isAgenticStorageAllowPlacement()),
                "avoidlootchests", String.valueOf(settings.isAgenticStorageAvoidLootChests()),
                "timeoutseconds", String.valueOf(settings.getAgenticStorageResolveTimeoutSeconds()));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
