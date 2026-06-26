package com.player2.playerengine.commands;

import com.player2.playerengine.FollowMode;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.player2api.AgentSideEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SetFollowModeCommand extends Command {
    public SetFollowModeCommand() throws CommandException {
        super(
            "set_follow_mode",
            "Sets how this companion behaves while following: NORMAL (default), COWARD (avoids monsters, "
                + "won't fight hostiles, stays close), or DEFENDER (fights off hostiles to protect you and "
                + "itself, stays close). Use when the user asks the companion to be careful/cowardly, to "
                + "protect them, or to act normally.",
            new Arg<>(FollowMode.class, "mode")
        );
        // NOTE: COWARD suppresses automatic HOSTILE/MOB combat only (the shouldDefendFromHostiles flag gates
        // MobDefenseChain). It does NOT disable PlayerDefenseChain (retaliation against attacking players).
        // Keep the wording "won't fight monsters/hostiles", never a blanket "no fighting".
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        FollowMode mode = parser.get(FollowMode.class);
        mod.setFollowMode(mode);

        // Player channel: tailored human chat line to the owner (DESIGN.md §3 truthfulness).
        // The MODE-CHANGE [FollowDiag] log already fires on the controller — do not duplicate it here.
        broadcastToOwner(mod, playerLineFor(mode));

        // Model channel: carry the active mode + its scoped behavioral consequence so the model
        // can answer truthfully. COWARD is scoped to hostile MOBS, never "all combat disabled".
        this.finishWithNote(modelNoteFor(mode));
    }

    private static String playerLineFor(FollowMode mode) {
        switch (mode) {
            case COWARD:
                return "I'll keep my head down and stay close — I won't fight monsters.";
            case DEFENDER:
                return "I'll stay close and fight off any hostiles that threaten us.";
            case NORMAL:
            default:
                return "Back to normal following.";
        }
    }

    private static String modelNoteFor(FollowMode mode) {
        switch (mode) {
            case COWARD:
                return "follow mode set to COWARD: automatic combat against hostile MOBS is now disabled and "
                    + "I will flee danger while staying near you (I may still defend myself if a PLAYER "
                    + "attacks me).";
            case DEFENDER:
                return "follow mode set to DEFENDER: I will fight off hostiles around us to protect you and "
                    + "myself while staying leashed near you (I won't chase far enough to abandon you).";
            case NORMAL:
            default:
                return "follow mode set to NORMAL: default following with automatic combat governed normally.";
        }
    }

    private static void broadcastToOwner(PlayerEngineController mod, String message) {
        MinecraftServer server = mod.getWorld() != null ? mod.getWorld().getServer() : null;
        if (server != null && mod.getOwner() != null) {
            UUID ownerUuid = mod.getOwner().getUUID();
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(ownerUuid);
            if (ownerPlayer != null) {
                AgentSideEffects.broadcastChatToPlayer(server, message, ownerPlayer);
            }
        }
    }
}
