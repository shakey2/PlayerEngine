package com.player2.playerengine.agentic;

import com.player2.playerengine.commands.SetupFarmCommand;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.tasks.farming.FarmPlantingRequestParser;

import java.util.List;
import java.util.Map;

/** Pure validation checks for the standalone setup, harvest, and planting contracts. */
public final class AgenticFarmPlanContractSelfTest {
    private AgenticFarmPlanContractSelfTest() {
    }

    public static void runAll() {
        require(AgenticPlanValidator.validSetupFarmCoordinates(Map.of()),
                "automatic setup arguments accepted");
        require(AgenticPlanValidator.validSetupFarmCoordinates(
                        Map.of("x", "1", "y", "64", "z", "-2")),
                "complete integer coordinates accepted");
        require(!AgenticPlanValidator.validSetupFarmCoordinates(Map.of("x", "1")),
                "partial coordinates rejected");
        require(!AgenticPlanValidator.validSetupFarmCoordinates(
                        Map.of("x", "1", "y", "64", "z", "2", "radius", "4")),
                "unknown setup arguments rejected");
        require(!AgenticPlanValidator.validSetupFarmCoordinates(
                        Map.of("x", "1", "y", "64", "z", "two")),
                "non-integer setup coordinates rejected");

        AgenticStepSpec farm = new AgenticStepSpec(
                "farm-1", AgenticSchemas.STEP_SETUP_FARM, Map.of(), "build a farm");
        AgenticStepSpec gather = new AgenticStepSpec(
                "gather-1", AgenticSchemas.STEP_GATHER_LOOSE_ITEMS, Map.of(), "gather");
        AgenticStepSpec harvest = new AgenticStepSpec(
                "harvest-1", AgenticSchemas.STEP_HARVEST_FARM, Map.of(), "harvest a farm");
        AgenticStepSpec plant = new AgenticStepSpec(
                "plant-1", AgenticSchemas.STEP_PLANT_FARM,
                Map.of("requests", "minecraft:carrot=23,minecraft:wheat_seeds=10"),
                "plant a farm");
        require(AgenticPlanValidator.validateSequence(List.of(farm)) == null,
                "standalone setup sequence accepted");
        require("invalid_step_sequence".equals(
                        AgenticPlanValidator.validateSequence(List.of(gather, farm))),
                "setup cannot be embedded in a multi-step sequence");
        require(AgenticPlanValidator.validateSequence(List.of(harvest)) == null,
                "standalone harvest sequence accepted");
        require("invalid_step_sequence".equals(
                        AgenticPlanValidator.validateSequence(List.of(farm, harvest))),
                "farming operations cannot be combined in one plan");
        require(AgenticPlanValidator.validateSequence(List.of(plant)) == null,
                "standalone planting sequence accepted");
        require("invalid_step_sequence".equals(
                        AgenticPlanValidator.validateSequence(List.of(harvest, plant))),
                "planting cannot be combined with another farming operation");
        require(FarmPlantingRequestParser.parseArgs(plant.args()).valid(),
                "ordered planting args accepted");

        assertSetupFarmResumeContract(
                AgenticPlannerPrompt.systemPrompt(), "planner system prompt");
        assertSetupFarmResumeContract(
                AgenticToolContextBuilder.allowedStepsSchema(), "allowed-steps schema");
        assertSetupFarmResumeContract(
                SeedToolMetadata.byId(AgenticSchemas.STEP_SETUP_FARM).whenToUse(),
                "retrieval tool card");

        assertDirectSetupFarmAliasContract(
                AgenticPlannerPrompt.systemPrompt(), "planner system prompt");
        assertDirectSetupFarmAliasContract(
                AgenticToolContextBuilder.allowedStepsSchema(), "allowed-steps schema");
        assertDirectSetupFarmAliasContract(
                SeedToolMetadata.byId(AgenticSchemas.STEP_SETUP_FARM).whenToUse(),
                "retrieval tool card");
        assertDirectSetupFarmAliasContract(
                directSetupFarmDescription(), "RAG-disabled/direct command description");
        assertSetupFarmMetadataSemantics();

        assertPlantFarmContract(AgenticPlannerPrompt.systemPrompt(), "planner system prompt");
        assertPlantFarmContract(
                AgenticToolContextBuilder.allowedStepsSchema(), "allowed-steps schema");
        assertPlantFarmContract(
                SeedToolMetadata.byId(AgenticSchemas.STEP_PLANT_FARM).whenToUse(),
                "retrieval tool card");
    }

    private static void assertSetupFarmResumeContract(String text, String source) {
        require(text != null
                        && text.contains("nearest whose complete repair envelope is loaded")
                        && text.contains("none has a fully loaded repair envelope")
                        && text.contains("stops instead of creating a duplicate")
                        && text.contains(
                        "only when no compatible unfinished farm exists in range"),
                source + " must match the coordinate-less resume and duplicate-prevention runtime");
    }

    private static void assertDirectSetupFarmAliasContract(String text, String source) {
        require(text != null
                        && text.contains("ordinary conversational requests")
                        && text.contains("'make a farm here'")
                        && text.contains("without a precise block")
                        && text.contains("coordinate-less setup_farm")
                        && text.contains("explicitly requests exact feet or coordinates")
                        && text.contains("Direct bot-command aliases (not JSON step args)")
                        && text.contains("setup_farm bot/setup_farm here")
                        && text.contains("setup_farm owner/setup_farm player")
                        && text.contains("exact block beneath the NPC")
                        && text.contains("exact block beneath the owner")
                        && text.contains("never use player or NPC gaze")
                        && text.contains("every exact anchor retains the hard safety gates"),
                source + " must distinguish ordinary automatic setup from explicit exact anchors");
    }

    private static String directSetupFarmDescription() {
        try {
            return new SetupFarmCommand().getDescription();
        } catch (CommandException invalidCommandContract) {
            throw new AssertionError("setup_farm command metadata must construct", invalidCommandContract);
        }
    }

    private static void assertSetupFarmMetadataSemantics() {
        String description = SeedToolMetadata.byId(
                AgenticSchemas.STEP_SETUP_FARM).description();
        String usage = SeedToolMetadata.byId(
                AgenticSchemas.STEP_SETUP_FARM).whenToUse();
        require(description.contains("fixed 9x9")
                        && description.contains("separate plant_farm operation"),
                "setup retrieval description separates fixed plot preparation from planting");
        require(usage.contains("coordinate-less setup_farm")
                        && usage.contains("ordinary conversational requests")
                        && usage.contains("without a precise block")
                        && usage.contains("explicitly requests exact feet or coordinates")
                        && usage.contains("exact block beneath the NPC")
                        && usage.contains("exact block beneath the owner")
                        && usage.contains("never use player or NPC gaze")
                        && usage.contains("every exact anchor retains the hard safety gates")
                        && usage.contains("do not retry it unchanged")
                        && usage.contains("claim clearing or tilling happened")
                        && usage.contains("does not plant crops; use plant_farm separately"),
                "setup retrieval metadata exposes automatic, exact, no-retry, and planting semantics");
    }

    private static void assertPlantFarmContract(String text, String source) {
        require(text != null
                        && text.contains("ordered")
                        && text.contains("planting item")
                        && text.contains("open")
                        && text.contains("slot")
                        && text.contains("stem")
                        && text.contains("village")
                        && text.contains("before village chests"),
                source + " must describe ordered generic planting and village-source priority");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
