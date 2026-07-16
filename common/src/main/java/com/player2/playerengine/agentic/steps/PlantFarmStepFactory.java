package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.farming.FarmPlantingRequestParser;
import com.player2.playerengine.tasks.farming.PlantFarmTask;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Optional;

/** Creates one finite ordered planting operation from a validated standalone agentic step. */
public final class PlantFarmStepFactory implements AgenticStepFactory {
    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        if (step == null || context == null || context.controller() == null) {
            return Optional.empty();
        }
        Map<String, String> args = step.args() == null ? Map.of() : step.args();
        FarmPlantingRequestParser.Parsed parsed = FarmPlantingRequestParser.parseArgs(args);
        if (!parsed.valid()) {
            return Optional.empty();
        }
        BlockPos anchor = context.controller().getEntity().blockPosition().immutable();
        String dimension = context.controller().getWorld().dimension().location().toString();
        return Optional.of(new PlantFarmTask(
                anchor,
                dimension,
                parsed.requests(),
                parsed.exactCenter(),
                parsed.policy(),
                context.runState()));
    }
}
