package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.movement.BodyLanguageTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class BodyLanguageCommand extends Command {
    public BodyLanguageCommand() throws CommandException {
        super("bodylang",
                "Perform a body language gesture. Action must be one of: greeting, nod_head, shake_head, victory.",
                new Arg<>(String.class, "action"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        BodyLanguageTask.Type resolvedType;
        try {
            resolvedType = BodyLanguageTask.resolveType(action);
        } catch (IllegalArgumentException e) {
            // Unknown action — report to both the player and the model; do not create a task.
            MinecraftServer server = mod.getWorld().getServer();
            if (server != null && mod.getOwner() != null) {
                UUID ownerUuid = mod.getOwner().getUUID();
                ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(ownerUuid);
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(
                            Component.translatable("message.playerengine.bodylang.unknown_gesture", action), false);
                }
            }
            this.finishWithError(
                    "Unknown bodylang action '" + action + "'. Valid: greeting, nod_head, shake_head, victory.");
            return;
        }
        mod.runUserTask(new BodyLanguageTask(resolvedType), this::finish);
    }

}