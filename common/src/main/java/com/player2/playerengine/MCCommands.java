package com.player2.playerengine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.player2.playerengine.executor.BudgetFallbackBehavior;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.ModelTierRouter;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.ProfileUrlResolver;
import com.player2.playerengine.player2api.RoutingResult;
import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.PlayerBudgetConfigHolder;
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
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.ToolRetriever;

public class MCCommands {

    public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

    /** Max {@code --category} flags before the goal string on {@code rag retrieve}. */
    private static final int RAG_RETRIEVE_MAX_CATEGORIES = 8;

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
                         .then(registerRouting())
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

    /**
     * {@code /playerengine rag <retrieve|reload|inspect>} — OP-only (permission 2).
     *
     * <ul>
     *   <li>{@code retrieve <goal>} or {@code retrieve --category <tag> ... <goal>} — runs
     *       retrieval for the executing player's per-owner index and prints top-5 results
     *       (category flags must precede the goal). Falls back to the global index when
     *       executed from a non-player context (e.g. the server console).
     *   <li>{@code reload} — re-reads all overlay files from disk, rebuilds the global
     *       retriever, and evicts all per-owner caches.
     *   <li>{@code inspect <toolId>} — prints all fields of the merged ToolDocument for the
     *       executing player's index; marks keywords/examples added by an overlay with [+].
     * </ul>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> registerRag() {
        return Commands.literal("rag")
                .requires(src -> src.hasPermission(2))
                .then(buildRagRetrieveCommand())
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

    private static LiteralArgumentBuilder<CommandSourceStack> buildRagRetrieveCommand() {
        LiteralArgumentBuilder<CommandSourceStack> retrieve = Commands.literal("retrieve");
        retrieve.then(Commands.argument("goal", StringArgumentType.greedyString())
                .executes(ctx -> executeRagRetrieve(ctx, Collections.emptyList())));
        ArgumentBuilder<CommandSourceStack, ?> withCategories =
                Commands.argument("goal", StringArgumentType.greedyString())
                        .executes(ctx -> executeRagRetrieve(ctx, collectRagCategoryArgs(ctx)));
        for (int i = RAG_RETRIEVE_MAX_CATEGORIES; i >= 1; i--) {
            final int idx = i;
            withCategories = Commands.literal("--category")
                    .then(Commands.argument("cat" + idx, StringArgumentType.word())
                            .then(withCategories));
        }
        retrieve.then(withCategories);
        return retrieve;
    }

    private static List<String> collectRagCategoryArgs(CommandContext<CommandSourceStack> ctx) {
        List<String> cats = new ArrayList<>();
        for (int i = 1; i <= RAG_RETRIEVE_MAX_CATEGORIES; i++) {
            String key = "cat" + i;
            try {
                cats.add(StringArgumentType.getString(ctx, key));
            } catch (IllegalArgumentException ignored) {
                break;
            }
        }
        return cats;
    }

    private static int executeRagRetrieve(CommandContext<CommandSourceStack> ctx, List<String> categoryTags) {
        CommandSourceStack src = ctx.getSource();
        String goal = StringArgumentType.getString(ctx, "goal");

        ToolRetriever retriever = resolveRetriever(src);
        if (retriever == null) {
            src.sendFailure(Component.literal("RAG index not initialized."));
            return 0;
        }

        Set<String> filter = categoryTags.isEmpty()
                ? null
                : new HashSet<>(categoryTags);
        List<RetrievalHit> hits = retriever.retrieve(goal, 5, filter);

        StringBuilder sb = new StringBuilder();
        if (filter != null && !filter.isEmpty()) {
            sb.append("Categories: ").append(String.join(", ", filter)).append("\n");
        }
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
        if (hits.isEmpty()) {
            sb.append("  (no results)");
        }
        LOGGER.info(sb.toString());
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return hits.size();
    }

    /**
     * Resolves the appropriate {@link ToolRetriever} for a command source.
     * Uses the executing player's per-owner retriever if available; falls back to global.
     */
    /**
     * {@code /playerengine routing probe <TASK_CLASS>} — OP-only (permission 2).
     * Optional {@code --simulate-joules <n>} and {@code --simulate-soft-budget} (read-only; no HTTP).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> registerRouting() {
        return Commands.literal("routing")
                .requires(src -> src.hasPermission(2))
                .then(buildRoutingProbeCommand());
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildRoutingProbeCommand() {
        LiteralArgumentBuilder<CommandSourceStack> probe = Commands.literal("probe");

        probe.then(routingProbeTaskClassArg(null, false));

        probe.then(Commands.literal("--simulate-soft-budget")
                .then(routingProbeTaskClassArg(null, true)));

        probe.then(Commands.literal("--simulate-joules")
                .then(Commands.argument("joules", IntegerArgumentType.integer(0))
                        .then(routingProbeTaskClassArg("joules", false))));

        probe.then(Commands.literal("--simulate-joules")
                .then(Commands.argument("joules", IntegerArgumentType.integer(0))
                        .then(Commands.literal("--simulate-soft-budget")
                                .then(routingProbeTaskClassArg("joules", true)))));

        return probe;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> routingProbeTaskClassArg(
            String joulesArgName, boolean simulateSoftBudget) {
        return Commands.argument("taskClass", StringArgumentType.word())
                .suggests(ROUTING_TASK_CLASS_SUGGEST)
                .executes(ctx -> {
                    Integer simJoules = joulesArgName != null
                            ? IntegerArgumentType.getInteger(ctx, joulesArgName)
                            : null;
                    return executeRoutingProbe(ctx, simJoules, simulateSoftBudget);
                });
    }

    private static final SuggestionProvider<CommandSourceStack> ROUTING_TASK_CLASS_SUGGEST = (ctx, builder) -> {
        for (AiTaskClass c : AiTaskClass.values()) {
            builder.suggest(c.name());
        }
        return builder.buildFuture();
    };

    private static int executeRoutingProbe(CommandContext<CommandSourceStack> ctx,
            Integer simulateJoules,
            boolean simulateSoftBudget) {
        CommandSourceStack src = ctx.getSource();
        String taskClassRaw = StringArgumentType.getString(ctx, "taskClass");
        AiTaskClass taskClass;
        try {
            taskClass = AiTaskClass.valueOf(taskClassRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown task class: " + taskClassRaw
                    + ". Use RETRIEVAL, RERANKING, SUMMARIZATION, PLANNING, or DECISION."));
            return 0;
        }

        if (PlayerEngineController.staticAPIServices.isEmpty()) {
            src.sendFailure(Component.literal("No Player2 API service registered (no active bots)."));
            return 0;
        }
        Player2APIService apiService = PlayerEngineController.staticAPIServices.values().iterator().next();
        PlayerEngineController controller = apiService.getController();

        String initiatorName = null;
        try {
            initiatorName = src.getPlayerOrException().getName().getString();
        } catch (Exception ignored) {
        }

        Player2PayerResolution.ApiBillingContext billing =
                Player2PayerResolution.resolve(controller, initiatorName, apiService.getClientId());
        String billingKey = billing.billingKey();

        Player2ServerRuntimeConfig serverConfig = Player2ServerConfigHolder.get();
        BudgetThresholds thresholds;
        if (serverConfig.getPayerMode() == Player2PayerMode.PROMPTER_PAYS && billing.onlinePayer() != null) {
            thresholds = PlayerBudgetConfigHolder.load(
                    billing.onlinePayer().getServer(), billing.onlinePayer().getUUID());
        } else {
            thresholds = serverConfig;
        }

        JoulesCache.JoulesSnapshot liveSnap = billingKey != null
                ? JoulesCache.get(billingKey).orElse(null)
                : null;
        JoulesCache.JoulesSnapshot snap = liveSnap;
        if (simulateJoules != null) {
            String tier = liveSnap != null ? liveSnap.patronTier : "";
            snap = JoulesCache.snapshotForProbe(simulateJoules, tier);
        }

        BudgetTracker.BudgetCheckResult callBudget = simulateSoftBudget
                ? BudgetTracker.BudgetCheckResult.SOFT_LIMIT
                : BudgetTracker.peek(billingKey, thresholds);
        BudgetTracker.BudgetCheckResult joulesBudget =
                JoulesCache.checkJoulesThreshold(snap, thresholds);
        BudgetTracker.BudgetCheckResult combined =
                BudgetTracker.stricter(callBudget, joulesBudget);

        boolean a4WouldSupersede = false;
        String a4Note = "no";
        if (combined == BudgetTracker.BudgetCheckResult.SOFT_LIMIT) {
            String fallback = serverConfig.getFallbackProfile();
            boolean dedicated = serverConfig.isDedicatedClientProxy();
            if (serverConfig.getBudgetFallbackBehavior() == BudgetFallbackBehavior.SWITCH_PROFILE
                    && fallback != null && !fallback.isBlank() && !dedicated) {
                a4WouldSupersede = ProfileUrlResolver.resolve(apiService, fallback).isPresent();
                a4Note = a4WouldSupersede
                        ? "yes — A4 SWITCH_PROFILE would use fallback '" + fallback + "' (B3 skipped)"
                        : "soft budget active but fallback profile '" + fallback + "' not resolved";
            } else if (simulateSoftBudget) {
                a4Note = "simulated soft budget — "
                        + (serverConfig.getBudgetFallbackBehavior() == BudgetFallbackBehavior.HARD_STOP
                        ? "HARD_STOP would block"
                        : "no SWITCH_PROFILE / missing fallback / dedicated proxy");
            } else {
                a4Note = "soft budget active — "
                        + serverConfig.getBudgetFallbackBehavior().name();
            }
        } else if (combined == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            a4Note = "hard budget — all AI calls blocked (B3 not reached)";
        }

        RoutingResult routing = ModelTierRouter.resolveWithRule(
                taskClass,
                ProfileUrlResolver.getSoleNamedProfileBaseUrl(apiService),
                snap,
                thresholds,
                serverConfig);

        StringBuilder sb = new StringBuilder();
        sb.append("Routing probe — ").append(taskClass.name()).append('\n');
        sb.append("  B3 outcome: ").append(ModelTierRouter.describeOutcome(routing.decision())).append('\n');
        sb.append("  B3 matched rule: ").append(routing.matchedRule()).append('\n');
        sb.append("  on-device: ").append(routing.decision().isOnDevice()).append('\n');
        if (snap != null) {
            sb.append("  joules: ").append(snap.joulesDisplay());
            if (simulateJoules != null) {
                sb.append(" (simulated)");
            }
            sb.append('\n');
            sb.append("  patron_tier: ").append(snap.patronTier.isEmpty() ? "(none)" : snap.patronTier).append('\n');
            long ageMs = System.currentTimeMillis() - snap.refreshedAtMs;
            sb.append("  joules cache age: ").append(ageMs / 1000).append("s\n");
        } else {
            sb.append("  joules: (no cached snapshot)\n");
        }
        sb.append("  softJoulesThreshold: ").append(thresholds.getSoftJoulesThreshold()).append('\n');
        sb.append("  hardJoulesThreshold: ").append(thresholds.getHardJoulesThreshold()).append('\n');
        sb.append("  call budget (peek): ").append(callBudget.name());
        if (simulateSoftBudget) {
            sb.append(" (simulated soft)");
        }
        sb.append('\n');
        sb.append("  joules budget: ").append(joulesBudget.name()).append('\n');
        sb.append("  combined budget: ").append(combined.name()).append('\n');
        sb.append("  A4 would supersede B3: ").append(a4Note).append('\n');
        sb.append("  fallbackProfile: ")
                .append(serverConfig.getFallbackProfile() != null ? serverConfig.getFallbackProfile() : "(none)")
                .append('\n');
        sb.append("  budgetFallbackBehavior: ").append(serverConfig.getBudgetFallbackBehavior().name()).append('\n');
        sb.append("  dedicatedClientProxy: ").append(serverConfig.isDedicatedClientProxy()).append('\n');
        sb.append("  cached ai_profiles: ").append(ProfileUrlResolver.getProfileNames(apiService)).append('\n');
        sb.append("  sole named profile URL: ")
                .append(ProfileUrlResolver.getSoleNamedProfileBaseUrl(apiService).orElse("(none)")).append('\n');
        sb.append("  billingKey: ").append(billingKey != null ? billingKey : "(none)").append('\n');

        LOGGER.info(sb.toString());
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
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