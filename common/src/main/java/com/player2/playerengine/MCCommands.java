package com.player2.playerengine;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.ToolRetriever;

import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
                        .then(registerRag())
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
                    // Your AgentSideEffects.broadcastChatToPlayer call (keeps original behavior)
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

    /**
     * {@code /playerengine rag retrieve "<goal>"} — OP-only debug command.
     * Runs the on-device retrieval pipeline and prints top-5 tool IDs with scores to chat.
     * The live LLM path is not affected; this surface also provides the B1.5 reload and inspect commands.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> registerRag() {
        return Commands.literal("rag")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("retrieve")
                        .then(Commands.argument("goal", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    String goal = StringArgumentType.getString(ctx, "goal");

                                    ToolRetriever retriever = resolveRetriever(src);
                                    if (retriever == null) {
                                        src.sendFailure(Component.literal("RAG index not initialized."));
                                        return 0;
                                    }
                                    java.util.List<RetrievalHit> hits = retriever.retrieve(goal, 5);
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("RAG top-").append(hits.size())
                                      .append(" for \"").append(goal).append("\":\n");
                                    for (int i = 0; i < hits.size(); i++) {
                                        RetrievalHit h = hits.get(i);
                                        sb.append(i + 1).append(". ").append(h.toolId())
                                          .append(" (score=").append(String.format("%.4f", h.score()))
                                          .append(", bm25=").append(
                                              h.bm25Rank() == Integer.MAX_VALUE ? "-" : h.bm25Rank())
                                          .append(", minhash=").append(
                                              h.minHashRank() == Integer.MAX_VALUE ? "-" : h.minHashRank())
                                          .append(")\n");
                                    }
                                    if (hits.isEmpty()) sb.append("  (no results)");
                                    LOGGER.info(sb.toString());
                                    src.sendSuccess(() -> Component.literal(sb.toString()), false);
                                    return hits.size();
                                })))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            RagIndex.reloadAll(server);
                            ToolRetriever global = RagIndex.getGlobal();
                            String msg = "RAG reload complete. Global index: "
                                    + (global != null ? global.documentCount() + " docs" : "FAILED");
                            LOGGER.info(msg);
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                            return 1;
                        }))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("toolId", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    String toolId = StringArgumentType.getString(ctx, "toolId");

                                    ToolRetriever retriever = resolveRetriever(src);
                                    if (retriever == null) {
                                        src.sendFailure(Component.literal("RAG index not initialized."));
                                        return 0;
                                    }

                                    ToolDocument merged = retriever.getRegistry().getDocument(toolId);
                                    if (merged == null) {
                                        src.sendFailure(Component.literal(
                                                "No tool found with id '" + toolId + "'."));
                                        return 0;
                                    }

                                    ToolDocument baseline = SeedToolMetadata.byId(toolId);
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("=== inspect: ").append(toolId).append(" ===\n");
                                    sb.append("name: ").append(merged.name()).append("\n");
                                    sb.append("description: ").append(merged.description()).append("\n");
                                    sb.append("whenToUse: ").append(merged.whenToUse()).append("\n");
                                    sb.append("categoryTags: ")
                                      .append(String.join(", ", merged.categoryTags())).append("\n");
                                    sb.append("keywords:\n");
                                    java.util.Set<String> baseKeywords = baseline == null
                                            ? java.util.Collections.emptySet()
                                            : new java.util.HashSet<>(baseline.keywords());
                                    for (String kw : merged.keywords()) {
                                        boolean overlay = !baseKeywords.contains(kw)
                                                && !baseKeywords.contains(kw.toLowerCase());
                                        sb.append("  ").append(overlay ? "[+] " : "    ").append(kw).append("\n");
                                    }
                                    sb.append("examples:\n");
                                    java.util.Set<String> baseExamples = baseline == null
                                            ? java.util.Collections.emptySet()
                                            : new java.util.HashSet<>(baseline.examples());
                                    for (String ex : merged.examples()) {
                                        boolean overlay = !baseExamples.contains(ex);
                                        sb.append("  ").append(overlay ? "[+] " : "    ").append(ex).append("\n");
                                    }
                                    LOGGER.info(sb.toString());
                                    src.sendSuccess(() -> Component.literal(sb.toString()), false);
                                    return 1;
                                })));
    }

    private static ToolRetriever resolveRetriever(CommandSourceStack src) {
        try {
            ServerPlayer player = src.getPlayerOrException();
            MinecraftServer server = src.getServer();
            return RagIndex.getForOwner(server, player.getUUID());
        } catch (Exception e) {
            return RagIndex.getGlobal();
        }
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
