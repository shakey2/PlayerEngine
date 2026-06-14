
package com.player2.playerengine.player2api;

import java.util.function.Consumer;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.retrieval.RagDeepSearchCommands;
import com.player2.playerengine.retrieval.learning.AliasLearningService;
import com.player2.playerengine.tasks.LookAtOwnerTask;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.manager.TTSManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.UUID;

public class AgentSideEffects {
    private static final Logger LOGGER = LogManager.getLogger();

    public sealed interface CommandExecutionStopReason
            permits CommandExecutionStopReason.Cancelled,
            CommandExecutionStopReason.Finished,
            CommandExecutionStopReason.Error {
        String commandName();

        record Cancelled(String commandName) implements CommandExecutionStopReason {
        }

        record Finished(String commandName, String note) implements CommandExecutionStopReason {
        }

        record Error(String commandName, String errMsg) implements CommandExecutionStopReason {
        }
    }

    public static void onEntityMessage(MinecraftServer server, Event.CharacterMessage characterMessage) {
        // message part:
        if (characterMessage.message() != null && !characterMessage.message().isBlank()) {
            AgentConversationData sendingCharacterData = characterMessage.sendingCharacterData();
            String message = String.format("<%s> %s", sendingCharacterData.getName(), characterMessage.message());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // if you are an owner, or close, send to player.
                // if(sendingCharacterData.isOwner(player.getUUID()) ||
                // isClose(sendingCharacterData, player) ){
                broadcastChatToPlayer(server, message, player);
                // }
            }
            TTSManager.TTS(characterMessage.message(), sendingCharacterData.getCharacter(),
                    sendingCharacterData.getPlayer2apiService(), sendingCharacterData.getUUID());
            // Per-bot speaking cooldown replaces the old server-wide TTS lock: this bot is gated
            // until its message is plausibly done playing client-side, but other bots can keep
            // dispatching LLM calls in their own billing buckets.
            sendingCharacterData.markSpeakingFor(characterMessage.message());
            ConversationManager.onAICharacterMessage(characterMessage,
                    characterMessage.sendingCharacterData().getUUID());
        }

        // command part:
        if (characterMessage.command() != null && !characterMessage.command().isBlank()) {
            onCommandListGenerated(characterMessage.sendingCharacterData().getMod(), characterMessage.command(),
                    characterMessage.sendingCharacterData()::onCommandFinish);
        }
    }

    public static void onError(MinecraftServer server, String errMsg, ServerPlayer player) {
        LOGGER.error(errMsg);
        broadcastErrorMsgToPlayer(server, errMsg, player);
    }

    public static void onCommandListGenerated(PlayerEngineController mod, String command,
                                              Consumer<CommandExecutionStopReason> onStop) {
        CommandExecutor cmdExecutor = mod.getCommandExecutor();
        String commandWithPrefix = cmdExecutor.isClientCommand(command) ? command
                : (cmdExecutor.getCommandPrefix() + command);
        if (commandWithPrefix.equals("@stop")) {
            mod.isStopping = true;
        } else {
            mod.isStopping = false;
        }
        if (commandWithPrefix.contains("idle")) {
            if (mod.hasActiveNonIdleUserTask()) {
                LOGGER.info("Ignoring idle while a non-idle user task is active (step={})",
                        mod.getActiveTrackedStep().map(e -> e.getStepKind()).orElse("unknown"));
                return;
            }
            // ISSUE 1 (temporary, user request): only set LookAtOwner when explicitly enabled. When gated
            // off, @idle leaves the bot with NO user task (idles) — StatusUtils already reports LookAtOwner
            // as "no task", so idle-as-no-task is consistent. Still early-return so @idle never falls
            // through into command execution.
            if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                mod.runUserTask(new LookAtOwnerTask());
            }
            return;
        }

        // add quotes to build_structure so it gets proccessed as one arg:
        String processedCommandWithPrefix = commandWithPrefix;
        // String processedCommandWithPrefix = commandWithPrefix.replaceFirst(
        //         "^(@build_structure)\\s+(?![\"'])(.+)$",
        //         "$1 \"$2\"");

        MinecraftServer server = mod.getWorld().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        UUID botUuid = mod.getPlayer().getUUID();
        String acceptedCommandId = firstCommandId(processedCommandWithPrefix, cmdExecutor);
        if (RagDeepSearchCommands.isMetaCommandId(acceptedCommandId)) {
            LOGGER.debug("[B5] ignoring virtual command rag_deepsearch in AgentSideEffects");
            return;
        }

        // Shared finish handler for both the clean path (note == null) and the success-with-note path
        // (note != null). Behavior is identical apart from the note carried on the Finished reason, so
        // the clean/cancelled paths are byte-for-byte unchanged.
        Consumer<String> onFinishWithNote =
                (note) -> {
                    if (mod.isStopping) {
                        LOGGER.info(
                                "[AgentSideEffects/AgentSideEffects]: (%s) was cancelled. Not adding finish event to queue.",
                                processedCommandWithPrefix);
                        onStop.accept(new CommandExecutionStopReason.Cancelled(commandWithPrefix));
                        LOGGER.info("after cancel, not running look at owner");
                    } else {
                        if (!commandWithPrefix.equals("@bodylang greeting")) {
                            LOGGER.info("Running on stop after finish cmd={}", commandWithPrefix);
                            onStop.accept(new CommandExecutionStopReason.Finished(commandWithPrefix, note));
                        } else {
                            LOGGER.info("Ignore onStop for bodylang greeting");
                        }
                        // ISSUE 1: gate LookAtOwner scheduling. onStop.accept(Finished) above already ran;
                        // skipping this leaves the bot idle (no user task), the desired behavior. The
                        // runUserTask call is the LAST statement in this block, so the finish flow
                        // (queue events, AliasLearning) is unaffected.
                        if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                            LOGGER.info("Running look at owner task after finish cmd={}", commandWithPrefix);
                            mod.runUserTask(new LookAtOwnerTask());
                        }
                    }
                };

        Runnable runExecute =
                () ->
                        cmdExecutor.execute(
                                processedCommandWithPrefix,
                                () -> {
                                    if (server != null && ownerUuid != null && acceptedCommandId != null) {
                                        AliasLearningService.onCommandAccepted(
                                                server, ownerUuid, botUuid, acceptedCommandId, cmdExecutor);
                                    }
                                },
                                () -> onFinishWithNote.accept(null),
                                onFinishWithNote,
                                (err) -> {
                                    if (server != null && ownerUuid != null && acceptedCommandId != null) {
                                        AliasLearningService.onCommandRejected(
                                                server, ownerUuid, botUuid, acceptedCommandId, err.getMessage());
                                    }
                                    onStop.accept(
                                            new CommandExecutionStopReason.Error(commandWithPrefix, err.getMessage()));
                                    // ISSUE 1: gate LookAtOwner scheduling. onStop.accept(Error) above
                                    // already ran; skipping this leaves the bot idle (no user task). Last
                                    // statement in the error callback, so the error flow is unaffected.
                                    if (mod.getModSettings().isEnableLookAtOwnerIdle()) {
                                        LOGGER.info("Running look at owner aftr error in cmd={}", commandWithPrefix);
                                        mod.runUserTask(new LookAtOwnerTask());
                                    }
                                });

        if (server != null && !server.isSameThread()) {
            server.execute(runExecute);
        } else {
            runExecute.run();
        }
    }

    public static void broadcastChatToPlayer(MinecraftServer server, String message, ServerPlayer player) {
        player.displayClientMessage(Component.literal(message), false);
    }
    private static void broadcastErrorMsgToPlayer(MinecraftServer server, String message, ServerPlayer player) {
        MutableComponent output = Component.literal(message);
        output.setStyle(output.getStyle().applyFormat(ChatFormatting.RED));
        player.displayClientMessage(output, false);
    }

    public static void broadcastChatToAllPlayers(MinecraftServer server, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            broadcastChatToPlayer(server, message, player);
        }
    }

    /** First semicolon-separated command name without prefix or arguments. */
    public static String firstCommandId(String commandWithPrefix, CommandExecutor cmdExecutor) {
        if (commandWithPrefix == null || commandWithPrefix.isBlank()) {
            return null;
        }
        try {
            String line = commandWithPrefix;
            if (cmdExecutor.isClientCommand(line)) {
                line = line.substring(cmdExecutor.getCommandPrefix().length());
            }
            String first = line.split(";")[0].trim();
            if (first.isEmpty()) {
                return null;
            }
            int sp = first.indexOf(' ');
            String name = sp == -1 ? first : first.substring(0, sp);
            return name.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    public static void teleportOwnerTo(AgentConversationData data){
        Player owner = data.getMod().getOwner();
        LivingEntity entity = data.getEntity();

        double x = entity.getX() + 0.5;
        double y = entity.getY() + 0.5;
        double z = entity.getZ() + 0.5;

        owner.teleportTo(x, y, z);
    }

}