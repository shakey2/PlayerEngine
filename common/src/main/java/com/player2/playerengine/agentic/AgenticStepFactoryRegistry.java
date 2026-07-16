package com.player2.playerengine.agentic;

import com.player2.playerengine.agentic.steps.DepositItemsStepFactory;
import com.player2.playerengine.agentic.steps.GatherLooseItemsStepFactory;
import com.player2.playerengine.agentic.steps.LabelChestStepFactory;
import com.player2.playerengine.agentic.steps.MineBlockStepFactory;
import com.player2.playerengine.agentic.steps.ResolveStorageChestStepFactory;
import com.player2.playerengine.agentic.steps.SmeltStepFactory;
import com.player2.playerengine.agentic.steps.SmithStepFactory;
import com.player2.playerengine.agentic.steps.SetupFarmStepFactory;
import com.player2.playerengine.agentic.steps.HarvestFarmStepFactory;
import com.player2.playerengine.agentic.steps.PlantFarmStepFactory;
import com.player2.playerengine.tasks.base.Task;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class AgenticStepFactoryRegistry {

    private final Map<String, AgenticStepFactory> factories = new LinkedHashMap<>();

    public AgenticStepFactoryRegistry() {
        register(AgenticSchemas.STEP_GATHER_LOOSE_ITEMS, new GatherLooseItemsStepFactory());
        register(AgenticSchemas.STEP_RESOLVE_STORAGE_CHEST, new ResolveStorageChestStepFactory());
        register(AgenticSchemas.STEP_DEPOSIT_ITEMS, new DepositItemsStepFactory());
        register(AgenticSchemas.STEP_LABEL_CHEST, new LabelChestStepFactory());
        register(AgenticSchemas.STEP_SMELT_ITEMS, new SmeltStepFactory());
        register(AgenticSchemas.STEP_SMITH_ITEMS, new SmithStepFactory());
        register(AgenticSchemas.STEP_MINE_BLOCK, new MineBlockStepFactory());
        register(AgenticSchemas.STEP_SETUP_FARM, new SetupFarmStepFactory());
        register(AgenticSchemas.STEP_HARVEST_FARM, new HarvestFarmStepFactory());
        register(AgenticSchemas.STEP_PLANT_FARM, new PlantFarmStepFactory());
    }

    public void register(String kind, AgenticStepFactory factory) {
        factories.put(kind, factory);
    }

    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        if (step == null || step.kind() == null) {
            return Optional.empty();
        }
        AgenticStepFactory factory = factories.get(step.kind());
        if (factory == null) {
            return Optional.empty();
        }
        return factory.createTask(step, context);
    }
}
