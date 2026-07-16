package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.farming.FarmPlantingRequestParser;
import com.player2.playerengine.tasks.farming.PlantFarmFeedback;
import com.player2.playerengine.tasks.farming.PlantFarmOutcome;
import com.player2.playerengine.tasks.farming.PlantFarmTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Direct finite ordered planting over one exact or automatically selected recorded farm. */
public final class PlantFarmCommand extends Command {
    public PlantFarmCommand() throws CommandException {
        super(
                "plant_farm",
                "Plants ordered item=count crop groups on one recorded 9x9 farm; optional exact x y z"
                        + " and preserve, mixed, or single:<item-id> farm policy.",
                new Arg<>(String.class, "requests"),
                new Arg<>(Integer.class, "x", (Integer) null, 1, false),
                new Arg<>(Integer.class, "y", (Integer) null, 1, false),
                new Arg<>(Integer.class, "z", (Integer) null, 1, false),
                new Arg<>(String.class, "farm_policy", "preserve", 4, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        int arity = parser.getArgUnits().length;
        if (arity != 1 && arity != 4 && arity != 5) {
            rejectArguments(mod);
            return;
        }
        String requests;
        BlockPos exact = null;
        String policy;
        try {
            requests = parser.get(String.class);
            Integer x = parser.get(Integer.class);
            Integer y = parser.get(Integer.class);
            Integer z = parser.get(Integer.class);
            policy = parser.get(String.class);
            if (x != null && y != null && z != null) {
                exact = new BlockPos(x, y, z);
            }
        } catch (CommandException invalid) {
            rejectArguments(mod);
            return;
        }
        FarmPlantingRequestParser.Parsed parsed = FarmPlantingRequestParser.parse(
                requests, exact, policy);
        if (!parsed.valid()) {
            rejectArguments(mod);
            return;
        }

        BlockPos anchor = mod.getEntity().blockPosition().immutable();
        String dimension = mod.getWorld().dimension().location().toString();
        PlantFarmTask task = new PlantFarmTask(
                anchor, dimension, parsed.requests(), parsed.exactCenter(), parsed.policy(), null);
        mod.runUserTaskTracked(
                "plant-farm", "plant_farm", task, RollbackPolicy.NONE,
                () -> finishFromOutcome(mod, task.outcome()));
    }

    private void rejectArguments(PlayerEngineController mod) {
        mod.reportAgenticProgress(Component.translatable(
                "message.playerengine.farming.plant.invalid_arguments"), true);
        this.finishWithError("invalid planting request: use plant_farm item_id=count[,item_id=count]"
                + " optionally followed by x y z and preserve, mixed, or single:<item-id>");
    }

    private void finishFromOutcome(PlayerEngineController mod, PlantFarmOutcome outcome) {
        mod.reportAgenticProgress(PlantFarmFeedback.player(outcome), true);
        String modelFeedback = PlantFarmFeedback.model(outcome);
        switch (PlantFarmFeedback.completion(outcome)) {
            case INFO -> this.finishWithInfo(modelFeedback);
            case NOTE -> this.finishWithNote(modelFeedback);
            case ERROR -> this.finishWithError(modelFeedback);
        }
    }
}
