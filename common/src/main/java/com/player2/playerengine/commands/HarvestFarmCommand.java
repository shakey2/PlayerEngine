package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.farming.FarmFeedback;
import com.player2.playerengine.tasks.farming.HarvestFarmOutcome;
import com.player2.playerengine.tasks.farming.HarvestFarmTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Direct one-pass mature-only harvest of the nearest or exact recorded farm. */
public final class HarvestFarmCommand extends Command {

    public HarvestFarmCommand() throws CommandException {
        super(
                "harvest_farm",
                "Harvests only fully grown crops from the nearest or exact recorded farm in one finite pass.",
                new Arg<>(Integer.class, "x", (Integer) null, 0, false),
                new Arg<>(Integer.class, "y", (Integer) null, 0, false),
                new Arg<>(Integer.class, "z", (Integer) null, 0, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        int arity = parser.getArgUnits().length;
        if (arity != 0 && arity != 3) {
            rejectArguments(mod);
            return;
        }
        BlockPos exactCenter = null;
        if (arity == 3) {
            try {
                exactCenter = new BlockPos(
                        parser.get(Integer.class),
                        parser.get(Integer.class),
                        parser.get(Integer.class));
            } catch (CommandException invalidCoordinates) {
                rejectArguments(mod);
                return;
            }
        }
        BlockPos anchor = mod.getEntity().blockPosition().immutable();
        String dimension = mod.getWorld().dimension().location().toString();
        HarvestFarmTask task = new HarvestFarmTask(anchor, dimension, exactCenter, null);
        mod.runUserTaskTracked(
                "harvest-farm", "harvest_farm", task, RollbackPolicy.NONE,
                () -> finishFromOutcome(mod, task.outcome()));
    }

    private void rejectArguments(PlayerEngineController mod) {
        mod.reportAgenticProgress(Component.translatable(
                "message.playerengine.farming.harvest.invalid_arguments"), true);
        this.finishWithError(
                "invalid farm coordinates: use harvest_farm with no coordinates or exactly three integers (x y z)");
    }

    private void finishFromOutcome(PlayerEngineController mod, HarvestFarmOutcome outcome) {
        mod.reportAgenticProgress(FarmFeedback.harvestPlayer(outcome), true);
        String modelFeedback = FarmFeedback.harvestModel(outcome);
        switch (FarmFeedback.harvestCompletion(outcome)) {
            case INFO -> this.finishWithInfo(modelFeedback);
            case NOTE -> this.finishWithNote(modelFeedback);
            case ERROR -> this.finishWithError(modelFeedback);
        }
    }
}
