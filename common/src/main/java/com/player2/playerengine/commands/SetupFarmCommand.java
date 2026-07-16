package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.farming.FarmFeedback;
import com.player2.playerengine.tasks.farming.FarmTaskOutcome;
import com.player2.playerengine.tasks.farming.SetupFarmTask;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** Direct finite farm setup command: automatic, anchored to a participant, or at an exact center. */
public final class SetupFarmCommand extends Command {

    public SetupFarmCommand() throws CommandException {
        super(
                "setup_farm",
                "Builds and records a fixed 9x9 irrigated farm. With no argument it resumes an eligible unfinished farm or selects a nearby safe site automatically. For ordinary conversational requests such as 'make a farm here' without a precise block, use coordinate-less setup_farm; use an exact anchor only when the owner explicitly requests exact feet or coordinates. Direct bot-command aliases (not JSON step args): setup_farm bot/setup_farm here selects the exact block beneath the NPC; setup_farm owner/setup_farm player selects the exact block beneath the owner. These aliases never use player or NPC gaze, and every exact anchor retains the hard safety gates. Setup prepares the plot; plant_farm plants crops separately.",
                new Arg<>(Integer.class, "x", (Integer) null, 0, false),
                new Arg<>(Integer.class, "y", (Integer) null, 0, false),
                new Arg<>(Integer.class, "z", (Integer) null, 0, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        int arity = parser.getArgUnits().length;
        if (arity != 0 && arity != 1 && arity != 3) {
            rejectArguments(mod);
            return;
        }

        BlockPos surfaceAnchor = mod.getEntity().blockPosition().below().immutable();
        BlockPos exactCenter = null;
        if (arity == 1) {
            Player owner = mod.getOwner();
            BlockPos ownerSurfaceAnchor = owner != null && owner.level() == mod.getWorld()
                    ? owner.blockPosition().below().immutable()
                    : null;
            exactCenter = resolveLocationAlias(
                    parser.getArgUnits()[0], surfaceAnchor, ownerSurfaceAnchor);
            if (exactCenter == null) {
                rejectArguments(mod);
                return;
            }
        } else if (arity == 3) {
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
        String dimension = mod.getWorld().dimension().location().toString();
        SetupFarmTask task = new SetupFarmTask(surfaceAnchor, dimension, exactCenter, null);
        mod.runUserTaskTracked(
                "setup-farm",
                "setup_farm",
                task,
                RollbackPolicy.NONE,
                () -> finishFromOutcome(mod, task.outcome()));
    }

    static BlockPos resolveLocationAlias(
            String rawAlias,
            BlockPos botSurfaceAnchor,
            BlockPos ownerSurfaceAnchor) {
        if (rawAlias == null) {
            return null;
        }
        BlockPos selected = switch (rawAlias.toLowerCase(Locale.ROOT)) {
            case "bot", "here" -> botSurfaceAnchor;
            case "owner", "player" -> ownerSurfaceAnchor;
            default -> null;
        };
        return selected == null ? null : selected.immutable();
    }

    private void rejectArguments(PlayerEngineController mod) {
        mod.reportAgenticProgress(
                net.minecraft.network.chat.Component.translatable(
                        "message.playerengine.farming.setup.invalid_arguments"),
                true);
        this.finishWithError(
                "invalid farm location: use setup_farm, setup_farm bot, setup_farm owner, or setup_farm x y z; here and player are aliases");
    }

    private void finishFromOutcome(PlayerEngineController mod, FarmTaskOutcome outcome) {
        mod.reportAgenticProgress(FarmFeedback.setupPlayer(outcome), true);
        String modelFeedback = FarmFeedback.setupModel(outcome);
        if (outcome != null && outcome.successful()) {
            if (FarmFeedback.isIndexDegraded(outcome)) {
                this.finishWithNote(modelFeedback);
            } else {
                this.finishWithInfo(modelFeedback);
            }
        } else {
            this.finishWithError(modelFeedback);
        }
    }
}
