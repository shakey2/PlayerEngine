package com.player2.playerengine;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.Player2APIService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.world.entity.player.Player;
import com.player2.playerengine.player2api.AgentSideEffects;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.manager.ConversationManager;

public class MCCommands {

    public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

    public static void onInit() {
        LifecycleEvent.SERVER_STARTING.register(server -> {
            PlayerEngine.resetBackgroundExecutorsShutdownGate();
            LOGGER.info("Server starting, registering MC commands");
            register(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            // On dedicated, drop queued AI work before tearing down executors so the next start
            // doesn't pick up a stuck queue. Integrated server keeps single-player conversation
            // state for the next session.
            if (server != null && server.isDedicatedServer()) {
                try {
                    ConversationManager.QueueClearSummary summary = ConversationManager.clearPendingWork();
                    LOGGER.info("SERVER_STOPPING (dedicated): drained queues={} buckets={}",
                            summary.queuesCleared(), summary.bucketsShutdown());
                } catch (Exception e) {
                    LOGGER.warn("SERVER_STOPPING clearPendingWork failed: {}", e.getMessage());
                }
            }
            PlayerEngine.shutdownBackgroundExecutors();
        });
    }

    public static void register(MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        registerFromDispatch(dispatcher);
    }

    private static void registerFromDispatch(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("playerengine")
                         .then(registerRelog())
                         .then(registerSummon())
                         .then(registerQueueClear())
                        .then(registerHelp()));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> registerHelp() {
        return Commands.literal("help")
                .executes(context -> {
                    LOGGER.info("help command");
                    Player player = context.getSource().getPlayerOrException();

                    String message = """
                        Help: here are the following
                        - 'playerengine relog':
                        - 'help': displays this help
                        - 'tpto <username>': teleports you to AI
                        - 'queue clear [player]': (OP) drop pending AI work; optional scope to one player's bots
                        - 'list': lists AI usernames
                    """;

                    AgentSideEffects.broadcastChatToPlayer(player.level().getServer(), message, (ServerPlayer) player);
                    return 1;
                });
    }

    /**
     * {@code /playerengine queue clear [player]} — OP-only. Flushes pending AI events and shuts
     * down per-billing LLM buckets so a stuck or runaway queue can be recovered without a server
     * restart. With a player argument it's scoped to that player's bots.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> registerQueueClear() {
        return Commands.literal("queue")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            ConversationManager.QueueClearSummary summary = ConversationManager.clearPendingWork();
                            sendQueueClearFeedback(ctx.getSource(), summary, null);
                            return summary.queuesCleared();
                        })
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    ConversationManager.QueueClearSummary summary =
                                            ConversationManager.clearPendingWorkFor(target.getUUID());
                                    sendQueueClearFeedback(ctx.getSource(), summary, target.getName().getString());
                                    return summary.queuesCleared();
                                })));
    }

    private static void sendQueueClearFeedback(CommandSourceStack src,
            ConversationManager.QueueClearSummary summary, String targetName) {
        String label = targetName != null
                ? "PlayerEngine queue clear (" + targetName + ")"
                : "PlayerEngine queue clear";
        String body = String.format("%s: drained %d conversation queue(s), shut down %d LLM bucket(s).",
                label, summary.queuesCleared(), summary.bucketsShutdown());
        LOGGER.info(body);
        src.sendSuccess(() -> Component.literal(body), true);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerRelog() {
        
        return Commands.literal("relog")
                .executes(context -> {
                    for (Player2APIService service : PlayerEngineController.staticAPIServices.values()) {
                        LOGGER.info("relog command");
                        String clientId = service.getClientId();
                        Player player = context.getSource().getPlayerOrException();
                        AuthenticationManager.getInstance().invalidateToken(player, clientId);
                    }
                    return 1;
                });
    }


    private static final SuggestionProvider<CommandSourceStack> NPC_SUGGEST = (context, builder) -> {
        CommandSourceStack src = context.getSource();
        Player player = src.getPlayerOrException();
        UUID ownerUUID = player.getUUID();
        String remaining = builder.getRemaining().toLowerCase();

        for (AgentConversationData data : ConversationManager.getDataByOwner(ownerUUID)) {
            String name = data.getName();

            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    };
    private static LiteralArgumentBuilder<CommandSourceStack> registerSummon() {
        return Commands.literal("tpto")
                .then(Commands.argument("username", StringArgumentType.string())
                        .suggests(NPC_SUGGEST)
                        .executes(context -> {
                            String username = StringArgumentType.getString(context, "username");
                            Player owner = context.getSource().getPlayerOrException();
                            UUID ownerUUID = owner.getUUID();
                            LOGGER.info("tpto command: {} {}", username, ownerUUID);
                            for (AgentConversationData data : ConversationManager.getDataByOwner(ownerUUID)) {
                                LOGGER.info("looking for name {}", data.getName());
                                if(data.getName().equals(username)){
                                    LOGGER.info("Match on username: {}", username);
                                    AgentSideEffects.teleportOwnerTo(data);
                                }
                            }
                            return 1;
                        }));
    } 

}