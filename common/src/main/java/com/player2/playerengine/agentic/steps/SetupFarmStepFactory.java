package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.farming.SetupFarmTask;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Creates the finite tracked farm-setup root from a validated standalone agentic step. */
public final class SetupFarmStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        if (step == null || context == null || context.controller() == null) {
            return Optional.empty();
        }
        Map<String, String> args = step.args() == null ? Map.of() : step.args();
        BlockPos exactCenter = null;
        if (!args.isEmpty()) {
            Optional<BlockPos> parsed = parseExplicitCenter(args);
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            exactCenter = parsed.get();
        }

        BlockPos surfaceAnchor = context.controller().getEntity().blockPosition().below().immutable();
        String dimension = context.controller().getWorld().dimension().location().toString();
        return Optional.of(new SetupFarmTask(
                surfaceAnchor, dimension, exactCenter, context.runState()));
    }

    static Optional<BlockPos> parseExplicitCenter(Map<String, String> args) {
        if (args == null || args.size() != 3
                || !args.keySet().containsAll(java.util.Set.of("x", "y", "z"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(args.get("x")),
                    Integer.parseInt(args.get("y")),
                    Integer.parseInt(args.get("z"))));
        } catch (NumberFormatException | NullPointerException invalid) {
            return Optional.empty();
        }
    }
}
