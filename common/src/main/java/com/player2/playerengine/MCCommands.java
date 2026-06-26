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
import com.player2.playerengine.player2api.BudgetThresholdsResolver;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceEnrichmentClient;
import com.player2.playerengine.modintelligence.enrich.ModIntelligenceSpendSafety;
import com.player2.playerengine.modintelligence.enrich.ModelBlacklist;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.deferred.DeferredJobStore;
import com.player2.playerengine.agentic.elliegps.EllieGPSWaypointCountingService;
import com.player2.playerengine.agentic.elliegps.EllieGPSWaypointIndex;
import com.player2.playerengine.util.helpers.MaterialAvailability;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.CraftMacroStep;
import com.player2.playerengine.tasks.crafting.resolver.IngredientInspectorImpl;
import com.player2.playerengine.tasks.crafting.resolver.MaterialResolver;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.resolver.ResolverResult;
import com.player2.playerengine.util.ItemTarget;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import com.player2.playerengine.memory.MemoryStoreRegistry;
import com.player2.playerengine.memory.ingest.MemoryIngestionService;
import net.minecraft.world.entity.player.Player;
import com.player2.playerengine.player2api.AgentSideEffects;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.learning.AliasLearningService;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.ToolRetriever;
import com.player2.playerengine.modintelligence.ModIntelligenceService;
import com.player2.playerengine.modintelligence.ModIntelligenceStatus;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentService;
import com.player2.playerengine.modintelligence.query.CapabilityHit;
import com.player2.playerengine.modintelligence.query.CapabilityQuery;
import com.player2.playerengine.modintelligence.query.CapabilityQueryService;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class MCCommands {

    public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

    /** Max {@code --category} flags before the goal string on {@code rag retrieve}. */
    private static final int RAG_RETRIEVE_MAX_CATEGORIES = 8;

    /** Phase D: ticks between memory-store dirty flushes (no-op when clean); ~5s at 20 TPS. */
    private static final int MEMORY_FLUSH_INTERVAL_TICKS = 100;

    public static void onInit() {
        LifecycleEvent.SERVER_STARTING.register(server -> {
            PlayerEngine.resetBackgroundExecutorsShutdownGate();
            LOGGER.info("Server starting, registering MC commands");
            register(server);
            ModIntelligenceService.initialize(server);
            // EllieGPS (C5): load the per-world waypoint store + index, then swap the real
            // counting service into the MaterialAvailability seam (startup-only swap; the
            // ellieGpsEnabled toggle is enforced per-call inside count()). Failures log WARN
            // and leave the stub in place — never abort server start.
            try {
                EllieGPSStore store = EllieGPSStore.loadForServer(server);
                EllieGPSWaypointIndex.setCurrent(EllieGPSWaypointIndex.loadOrRebuild(store));
                MaterialAvailability.setWaypointSource(new EllieGPSWaypointCountingService());
            } catch (Exception e) {
                LOGGER.warn("EllieGPS startup wiring failed — waypoint features degrade to the stub: {}",
                        e.getMessage());
            }
            // Deferred jobs (WS7): load the per-world deferred-smelt job registry so a job started
            // before a restart can be reconciled against the live furnace BE when its owning bot is
            // next active. Failures log WARN and start empty — never abort server start.
            try {
                DeferredJobStore.loadForServer(server);
            } catch (Exception e) {
                LOGGER.warn("DeferredJobStore startup load failed — deferred-smelt resume unavailable: {}",
                        e.getMessage());
            }
            // Respect-player-structures (WS2): load the per-world player-placed-block store so the
            // pathfinder's isProtected can consult it. Failures log WARN and leave protection off —
            // never abort server start.
            try {
                PlayerPlacedBlockStore.loadForServer(server);
            } catch (Exception e) {
                LOGGER.warn("PlayerPlacedBlockStore startup load failed — block protection unavailable: {}",
                        e.getMessage());
            }
            // Phase D memory (W2/W3 integration): install the store-provider seam so the async
            // ingestion path resolves a live per-companion MemoryStore through the registry (the
            // registry lazy-loads on first use; SERVER THREAD lookup inside server.execute). Failures
            // here only leave memory ingestion a no-op — never abort server start.
            try {
                MemoryIngestionService.setStoreProvider(MemoryStoreRegistry::getOrLoad);
            } catch (Exception e) {
                LOGGER.warn("Memory store-provider wiring failed — memory ingestion disabled: {}",
                        e.getClass().getSimpleName());
            }
        });
        // Phase D memory (W2 integration): tick-end flush of any dirty per-companion store. flushIfDirty
        // is a no-op for clean stores, so this is cheap; throttled so we don't compact+hash every tick.
        TickEvent.SERVER_POST.register(server -> {
            try {
                if (server != null && server.getTickCount() % MEMORY_FLUSH_INTERVAL_TICKS == 0) {
                    MemoryStoreRegistry.flushAllIfDirty(server);
                }
            } catch (Exception e) {
                LOGGER.warn("Memory tick-end flush failed: {}", e.getClass().getSimpleName());
            }
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
            // EllieGPS (C5): clear the per-world store + index so the counting service
            // degrades to 0 until the next SERVER_STARTING reloads them.
            try {
                EllieGPSStore ellieStore = EllieGPSStore.get();
                if (ellieStore != null) {
                    ellieStore.clear();
                }
                EllieGPSWaypointIndex.setCurrent(null);
            } catch (Exception e) {
                LOGGER.warn("SERVER_STOPPING EllieGPS cleanup failed: {}", e.getMessage());
            }
            // Deferred jobs (WS7): clear the per-world job store so it reloads fresh on the next
            // SERVER_STARTING (mirrors the EllieGPS store lifecycle above).
            try {
                DeferredJobStore deferredStore = DeferredJobStore.get();
                if (deferredStore != null) {
                    deferredStore.clear();
                }
            } catch (Exception e) {
                LOGGER.warn("SERVER_STOPPING DeferredJobStore cleanup failed: {}", e.getMessage());
            }
            // Respect-player-structures (WS2): final flush + clear of the player-placed-block store
            // (clear() flushes any dirty dimensions before nulling the singleton).
            try {
                PlayerPlacedBlockStore placedStore = PlayerPlacedBlockStore.get();
                if (placedStore != null) {
                    placedStore.clear();
                }
            } catch (Exception e) {
                LOGGER.warn("SERVER_STOPPING PlayerPlacedBlockStore cleanup failed: {}", e.getMessage());
            }
            // Phase D memory (W2 integration): final flush + clear of every per-companion store BEFORE
            // shutdownBackgroundExecutors() tears down the memory executor — so a dirty graph is persisted
            // while the world path is still valid. clearAll() does a synchronous final flush per store
            // (no LLM, no async), then drops the registry so the next SERVER_STARTING starts fresh.
            //
            // W6 session-end reflection: intentionally NOT triggered here. Per the plan's W6 binding
            // (§729-741) the lower-risk resolution is taken — reflection relies solely on the mid-session
            // cumulative-importance threshold path (fires off-tick with a live context), avoiding the
            // teardown race where a reflection dispatched at SERVER_STOPPING would hit a terminated
            // MEMORY_EXECUTOR / invalidated billing context. Memory data is still durably persisted below.
            try {
                MemoryStoreRegistry.clearAll(server);
            } catch (Exception e) {
                LOGGER.warn("SERVER_STOPPING memory store cleanup failed: {}", e.getClass().getSimpleName());
            }
            // Drop W3/W6 per-session static state (turn batcher + reflection in-flight latches) so a
            // same-JVM (integrated) restart does not carry stale buffered turns into the next session's
            // graph or leave a reflection latch stuck at true (permanently suppressing reflection).
            try {
                MemoryIngestionService.clearSessionState();
            } catch (Exception e) {
                LOGGER.warn("SERVER_STOPPING memory ingestion cleanup failed: {}", e.getClass().getSimpleName());
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
                         .then(registerCapability())
                         .then(registerResolve())
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
                .then(Commands.literal("audit")
                        .then(Commands.literal("tail")
                                .executes(ctx -> executeRagAuditTail(ctx, 20))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> executeRagAuditTail(
                                                ctx, IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(buildRagResetLearnedCommand())
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

        MinecraftServer probeServer = src.getServer();
        BudgetThresholds thresholds = BudgetThresholdsResolver.resolve(probeServer, billing);
        Player2ServerRuntimeConfig serverConfig = Player2ServerConfigHolder.get();

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

    private static int executeRagAuditTail(CommandContext<CommandSourceStack> ctx, int n) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> lines = AliasLearningService.auditTail(server, n);
        String body = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(body.isEmpty() ? "(no rows)" : body), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRagResetLearnedCommand() {
        return Commands.literal("reset_learned")
                .executes(ctx -> executeRagResetLearned(ctx, null, false))
                .then(Commands.literal("--all-owners")
                        .executes(ctx -> executeRagResetLearned(ctx, null, true))
                        .then(Commands.argument("toolId", StringArgumentType.word())
                                .executes(ctx -> executeRagResetLearned(
                                        ctx,
                                        StringArgumentType.getString(ctx, "toolId"),
                                        true))))
                .then(Commands.argument("toolId", StringArgumentType.word())
                        .executes(ctx -> executeRagResetLearned(
                                ctx,
                                StringArgumentType.getString(ctx, "toolId"),
                                false))
                        .then(Commands.literal("--all-owners")
                                .executes(ctx -> executeRagResetLearned(
                                        ctx,
                                        StringArgumentType.getString(ctx, "toolId"),
                                        true))));
    }

    private static int executeRagResetLearned(
            CommandContext<CommandSourceStack> ctx, String toolId, boolean allOwners) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        UUID ownerUuid = null;
        if (!allOwners) {
            try {
                ServerPlayer player = src.getPlayerOrException();
                ownerUuid = player.getUUID();
            } catch (Exception e) {
                src.sendFailure(Component.literal(
                        "Console must use reset_learned --all-owners or specify owner context via player."));
                return 0;
            }
        }
        int count = AliasLearningService.resetLearned(server, ownerUuid, toolId);
        String scope = allOwners ? "all owners" : ("owner " + ownerUuid);
        String tool = toolId != null ? (" tool=" + toolId) : " (all tools)";
        String msg = "Reset learned overlays for " + scope + tool + " (" + count + " owner dir(s) touched).";
        src.sendSuccess(() -> Component.literal(msg), true);
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

    private static LiteralArgumentBuilder<CommandSourceStack> registerCapability() {
        return Commands.literal("capability")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> capabilityStatus(ctx.getSource())))
                .then(Commands.literal("query")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> capabilityQuery(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "text"), null, null, null))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(ctx -> capabilityInspect(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "kind"),
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("rebuild")
                        .executes(ctx -> capabilityRebuild(ctx.getSource(), false))
                        .then(Commands.literal("force")
                                .executes(ctx -> capabilityRebuild(ctx.getSource(), true))))
                .then(Commands.literal("enrich")
                        .executes(ctx -> capabilityEnrich(ctx.getSource(), null))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(0))
                                .executes(ctx -> capabilityEnrich(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "limit")))));
    }

    private static int capabilityStatus(CommandSourceStack src) {
        ModIntelligenceStatus st = ModIntelligenceService.status();
        String msg = String.format(
                "ModIntelligence enabled=%s inspecting=%s enriching=%s entries=%d ready=%d partial=%d unknown=%d inspectFailed=%d tombstoned=%d queued=%d enriched=%d enrichFailures=%d lastBatch=%d/%d/%d packFp=%s lastError=%s",
                st.isEnabled(), st.isInspecting(), st.isEnriching(), st.getTotalEntries(),
                st.getReadyEntries(), st.getPartialEntries(), st.getUnknownEntries(),
                st.getFailedEntries(), st.getTombstonedEntries(), st.getQueuedEnrichments(),
                st.getEnrichedEntries(), st.getEnrichmentFailures(),
                st.getLastBatchValidated(), st.getLastBatchFailures(), st.getLastBatchRemaining(),
                st.getActivePackFingerprint() != null ? st.getActivePackFingerprint().substring(0,
                        Math.min(24, st.getActivePackFingerprint().length())) : "",
                st.getLastError() != null ? st.getLastError() : "");
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int capabilityQuery(CommandSourceStack src, String text, String kindFilter,
                                         String capFilter, Double minConf) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        CapabilityQuery query = CapabilityQuery.defaults()
                .setText(text)
                .setMinConfidence(minConf != null ? minConf : cfg.getModIntelligenceMinQueryConfidence());
        if (kindFilter != null) {
            CapabilitySubjectKind kind = CapabilitySubjectKind.valueOf(kindFilter.toUpperCase(Locale.ROOT));
            query.setSubjectKinds(EnumSet.of(kind));
        }
        if (capFilter != null) {
            query.setRequiredCapabilities(Set.of(capFilter));
        }
        List<CapabilityHit> hits = ModIntelligenceService.queryService()
                .query(query, cfg.getModIntelligenceQueryTopKClamped());
        StringBuilder sb = new StringBuilder("Capability query (").append(hits.size()).append(" hits):\n");
        for (CapabilityHit hit : hits) {
            sb.append(String.format("  %s %s score=%.3f conf=%.2f caps=%s status=%s%n",
                    hit.getSubjectKind(), hit.getSubjectId(), hit.getScore(),
                    hit.getBestCapabilityConfidence(), hit.getMatchedCapabilities(), hit.getStatus()));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int capabilityInspect(CommandSourceStack src, String kindRaw, String id) {
        CapabilitySubjectKind kind = CapabilitySubjectKind.valueOf(kindRaw.toUpperCase(Locale.ROOT));
        Optional<CapabilityMap> map = ModIntelligenceService.queryService().get(kind, id);
        if (map.isEmpty()) {
            src.sendFailure(Component.literal("No capability map for " + kind + " " + id));
            return 0;
        }
        CapabilityMap m = map.get();
        StringBuilder sb = new StringBuilder();
        sb.append("inspect ").append(kind).append(' ').append(id).append('\n');
        sb.append("status=").append(m.getStatus()).append(" fingerprint=").append(m.getEntryFingerprint()).append('\n');
        sb.append("capabilities=").append(m.getCapabilities().size()).append(" warnings=").append(m.getWarnings()).append('\n');
        if (m.getEnrichment() != null) {
            sb.append("enrichment=").append(m.getEnrichment().getShortDescription()).append('\n');
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int capabilityRebuild(CommandSourceStack src, boolean force) {
        MinecraftServer server = src.getServer();
        src.sendSuccess(() -> Component.literal("ModIntelligence rebuild started (async)"), false);
        PlayerEngine.getExecutor().execute(() -> ModIntelligenceService.runIngestion(server, force));
        return 1;
    }

    /**
     * @param limit explicit per-batch call cap; overrides the config cap for this batch (0 = unlimited)
     *        and skips the B4.5 large-queue budget gate (informed consent). {@code null} = no argument
     *        given, config governs. The joules budget hard/soft limits always remain enforced.
     */
    private static int capabilityEnrich(CommandSourceStack src, Integer limit) {
        if (!Player2ServerConfigHolder.get().isModIntelligenceEnrichmentEnabled()) {
            src.sendFailure(Component.literal("Enrichment disabled in server_player2.json"));
            return 0;
        }
        MinecraftServer server = src.getServer();
        int queued = ModIntelligenceService.status().getQueuedEnrichments();
        if (queued == 0) {
            src.sendFailure(Component.literal("ModIntelligence enrichment queue is empty"));
            return 0;
        }
        if (!ModIntelligenceEnrichmentClient.isBillingAvailable(server)) {
            src.sendFailure(Component.literal("ModIntelligence enrichment: billing unavailable (log in or join as payer)"));
            return 0;
        }
        ModelBlacklist.ModelBlacklistSnapshot blacklist = ModelBlacklist.load();
        var defer = ModIntelligenceSpendSafety.preflight(server, queued, blacklist, limit != null);
        if (defer.isPresent()) {
            Component msg = ModIntelligenceSpendSafety.messageFor(defer.get(), queued, blacklist);
            src.sendFailure(msg);
            ModIntelligenceSpendSafety.notifyPlayer(server, msg);
            return 0;
        }
        int configMax = Player2ServerConfigHolder.get().getModIntelligenceMaxEnrichmentCallsPerLaunch();
        String configMaxText = configMax <= 0 ? "unlimited" : String.valueOf(configMax);
        String limitText;
        if (limit == null) {
            limitText = configMaxText + " (config)";
        } else {
            limitText = (limit <= 0 ? "unlimited" : String.valueOf(limit))
                    + " — overrides config " + configMaxText;
        }
        // Feedback only AFTER scheduling — the result says what actually happened (the old code claimed
        // "batch started" unconditionally even when the request was dropped because a batch was running).
        ModIntelligenceService.ScheduleResult result = ModIntelligenceService.scheduleEnrichmentBatch(server, limit);
        return switch (result) {
            case STARTED -> {
                src.sendSuccess(() -> Component.literal(
                        "ModIntelligence enrichment batch started (queued=" + queued + ", limit: " + limitText + ")"), false);
                yield 1;
            }
            case ALREADY_RUNNING -> {
                String pendingText = limit == null
                        ? "config-limit"
                        : (limit <= 0 ? "unlimited" : limit + "-limit");
                src.sendSuccess(() -> Component.literal(
                        "ModIntelligence enrichment: a batch is already running; your " + pendingText
                                + " batch will start when it finishes (queued=" + queued + ")"), false);
                yield 1;
            }
            case NOTHING_QUEUED -> {
                src.sendFailure(Component.literal("ModIntelligence enrichment: nothing queued for enrichment"));
                yield 0;
            }
            case DISABLED -> {
                src.sendFailure(Component.literal(
                        "ModIntelligence enrichment is disabled in config (server_player2.json)"));
                yield 0;
            }
            case BILLING_UNAVAILABLE -> {
                src.sendFailure(Component.literal(
                        "ModIntelligence enrichment: billing not available yet — join a world or wait for the stored token"));
                yield 0;
            }
        };
    }

    /**
     * {@code /playerengine resolve <item> [count]} — OP-only (permission 2). Deterministic resolver
     * dry-run: runs {@link MaterialResolver#resolve} against a live bot's CURRENT inventory and prints
     * the {@link ResolverResult} (status, ordered sub-craft steps, external acquisitions, tiered
     * deficit, failure reason). Operator debug tooling for Workstream 6.
     *
     * <p><b>Invariants:</b> ZERO Player2/AiTask/Joules calls and persists nothing — it only reads
     * inventory counts and the Minecraft recipe/tag system through the deterministic resolver. It does
     * not enqueue, start, or mutate any task; it is logging/output only.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> registerResolve() {
        return Commands.literal("resolve")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(ctx -> executeResolve(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeResolve(
                                        ctx, IntegerArgumentType.getInteger(ctx, "count")))));
    }

    private static int executeResolve(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        String itemName = StringArgumentType.getString(ctx, "item");

        // Resolve the <item> string to a concrete Item; default the namespace to "minecraft:".
        String qualified = itemName.contains(":") ? itemName : "minecraft:" + itemName;
        ResourceLocation id = ResourceLocation.tryParse(qualified);
        Optional<Item> targetOpt = id != null
                ? BuiltInRegistries.ITEM.getOptional(id)
                : Optional.empty();
        if (targetOpt.isEmpty()) {
            src.sendFailure(Component.literal("Unknown item: '" + itemName + "' (parsed as '" + qualified + "')."));
            return 0;
        }
        Item target = targetOpt.get();

        // Acquire a live bot controller via the existing staticAPIServices pattern (resolver reads the
        // bot's current inventory through it). No API call is made.
        if (PlayerEngineController.staticAPIServices.isEmpty()) {
            src.sendFailure(Component.literal("No Player2 API service registered (no active bots)."));
            return 0;
        }
        Player2APIService apiService = PlayerEngineController.staticAPIServices.values().iterator().next();
        PlayerEngineController controller = apiService.getController();

        // Deterministic resolver: zero model calls, no persistence.
        MaterialResolver resolver = new MaterialResolver(new IngredientInspectorImpl(), new RecipeAccessImpl(), new CookingRecipeAccessImpl());
        ResolverResult result = resolver.resolve(controller, target, count);

        StringBuilder sb = new StringBuilder();
        sb.append("Resolve dry-run — ").append(qualified).append(" x").append(count).append('\n');
        sb.append("  status: ").append(result.status()).append('\n');
        sb.append("  remainingDeficit (tiered): ").append(result.remainingDeficit()).append('\n');
        sb.append("  steps (").append(result.remainingSteps().size()).append("):\n");
        for (CraftMacroStep step : result.remainingSteps()) {
            String pooled = step.outputMatches() != null
                    ? (" pooled=" + step.outputMatches().length)
                    : " pooled=none";
            sb.append("    - ").append(step.kind())
              .append(" x").append(step.craftsNeeded())
              .append(" [").append(step.debugLabel()).append("]")
              .append(pooled).append('\n');
        }
        sb.append("  external (").append(result.externalNeeded().size()).append("):\n");
        for (ItemTarget t : result.externalNeeded()) {
            sb.append("    - ").append(t.getCatalogueName())
              .append(" x").append(t.getTargetCount()).append('\n');
        }
        if (result.failureReason() != null && !result.failureReason().isEmpty()) {
            sb.append("  failureReason: ").append(result.failureReason()).append('\n');
        }

        LOGGER.info(sb.toString());
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return result.status() == ResolverResult.Status.UNOBTAINABLE ? 0 : 1;
    }

}