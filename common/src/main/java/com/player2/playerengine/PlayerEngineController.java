package com.player2.playerengine;

import com.player2.playerengine.automaton.AdditionalBaritoneSettings;
import com.player2.playerengine.chains.FoodChain;
import com.player2.playerengine.chains.MLGBucketFallChain;
import com.player2.playerengine.chains.ForceEquipGearChain;
import com.player2.playerengine.chains.MobDefenseChain;
import com.player2.playerengine.chains.PlayerDefenseChain;
import com.player2.playerengine.chains.PlayerInteractionFixChain;
import com.player2.playerengine.chains.PreEquipItemChain;
import com.player2.playerengine.chains.UnstuckChain;
import com.player2.playerengine.chains.UserTaskChain;
import com.player2.playerengine.chains.WorldSurvivalChain;
import com.player2.playerengine.commands.BlockScanner;
import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.control.InputControls;
import com.player2.playerengine.control.PlayerExtraController;
import com.player2.playerengine.control.SlotHandler;
import com.player2.playerengine.equip.ExplicitEquipPolicy;
import com.player2.playerengine.equip.PickupArmorEvalQueue;
import com.player2.playerengine.equip.PickupWeaponEvalQueue;

import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.player2api.AIPersistantData;
import com.player2.playerengine.player2api.Player2APIService;

import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.trackers.*;
import com.player2.playerengine.trackers.storage.ContainerSubTracker;
import com.player2.playerengine.trackers.storage.ItemStorageTracker;
import com.player2.playerengine.automaton.Baritone;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.automaton.api.utils.IEntityContext;
import com.player2.playerengine.automaton.api.utils.IInteractionController;
import com.player2.playerengine.trackers.CacheTracker;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.player2.playerengine.executor.IStepExecutorAdapter;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.Playground;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

public class PlayerEngineController {
   private final IBaritone baritone;
   private AIPersistantData aiPersistantData;
   private Player2APIService player2apiService;
   private final IEntityContext ctx;
   private CommandExecutor commandExecutor;
   private TaskRunner taskRunner;
   private TrackerManager trackerManager;
   private BotBehaviour botBehaviour;
   private UserTaskChain userTaskChain;
   private TaskStepExecutorAdapter stepExecutorAdapter;
   private FoodChain foodChain;
   private MobDefenseChain mobDefenseChain;
   private ForceEquipGearChain forceEquipGearChain;
   private MLGBucketFallChain mlgBucketChain;
   private ItemStorageTracker storageTracker;
   private ContainerSubTracker containerSubTracker;
   private EntityTracker entityTracker;
   private BlockScanner blockScanner;
   private SimpleChunkTracker chunkTracker;
   private CacheTracker cacheTracker;
   private MiscBlockTracker miscBlockTracker;
   private CraftingRecipeTracker craftingRecipeTracker;
   private EntityStuckTracker entityStuckTracker;
   private UserBlockRangeTracker userBlockRangeTracker;
   private InputControls inputControls;
   private SlotHandler slotHandler;
   private final ExplicitEquipPolicy explicitEquipPolicy = new ExplicitEquipPolicy();
   private final PickupArmorEvalQueue pickupArmorEvalQueue;
   private final PickupWeaponEvalQueue pickupWeaponEvalQueue;
   private PlayerExtraController extraController;
   private PlayerEngineSettings settings;
   private ChunkLoadingTracker chunkLoader;
   private boolean paused = false;
   private Task storedTask;
   public boolean isStopping = false;
   private Player owner;
   public static HashMap<UUID, Player2APIService> staticAPIServices = new HashMap<>();
   /** Registry of all active PlayerEngineController instances by bot entity UUID. Used by server admin commands. */
   public static final ConcurrentHashMap<UUID, PlayerEngineController> staticControllers = new ConcurrentHashMap<>();
   private boolean shouldDefendFromHostiles = false;

   /**
    * Throttle floor (ms) for non-milestone agentic progress lines. Identical across branches so a
    * given run produces the same broadcast cadence everywhere. Milestone/failure lines bypass this
    * interval (see {@link #reportAgenticProgress(String, boolean)}). Constant-only for C4; a runtime
    * config knob (agenticProgressMinIntervalMs) is deferred to a follow-up.
    */
   private static final long MIN_REPORT_INTERVAL_MS = 1500L;
   /** Last wall-clock time an agentic progress line was broadcast to a player for this bot. */
   private long lastAgenticReportMs = 0L;
   /** Last agentic progress message broadcast for this bot, for consecutive-duplicate suppression. */
   private String lastAgenticReportMessage = "";

   public PlayerEngineController(IBaritone baritone, Character character, String player2GameId) {
      this.baritone = baritone;
      this.ctx = baritone.getEntityContext();
      this.commandExecutor = new CommandExecutor(this);
      this.taskRunner = new TaskRunner(this);
      this.trackerManager = new TrackerManager(this);
      this.userTaskChain = new UserTaskChain(this.taskRunner);
      this.stepExecutorAdapter = new TaskStepExecutorAdapter(this);
      this.mobDefenseChain = new MobDefenseChain(this.taskRunner);
      new PlayerInteractionFixChain(this.taskRunner);
      this.mlgBucketChain = new MLGBucketFallChain(this.taskRunner);
      new UnstuckChain(this.taskRunner);
      new PreEquipItemChain(this.taskRunner);
      new WorldSurvivalChain(this.taskRunner);
      this.foodChain = new FoodChain(this.taskRunner);
      this.forceEquipGearChain = new ForceEquipGearChain(this.taskRunner);
      new PlayerDefenseChain(this.taskRunner);
      this.storageTracker = new ItemStorageTracker(this, this.trackerManager,
            container -> this.containerSubTracker = container);
      this.entityTracker = new EntityTracker(this.trackerManager);
      this.blockScanner = new BlockScanner(this);
      this.chunkTracker = new SimpleChunkTracker(this);
      this.miscBlockTracker = new MiscBlockTracker(this);
      this.craftingRecipeTracker = new CraftingRecipeTracker(this.trackerManager);
      this.entityStuckTracker = new EntityStuckTracker(this.trackerManager);
      this.userBlockRangeTracker = new UserBlockRangeTracker(this.trackerManager);
      this.inputControls = new InputControls(this);
      this.slotHandler = new SlotHandler(this);
      this.pickupArmorEvalQueue = new PickupArmorEvalQueue(this);
      this.pickupWeaponEvalQueue = new PickupWeaponEvalQueue(this);
      this.extraController = new PlayerExtraController(this);
      this.initializeBaritoneSettings();
      this.botBehaviour = new BotBehaviour(this);
      this.cacheTracker = new CacheTracker(this);
      this.initializeCommands();
      PlayerEngineSettings.load(
            newSettings -> {
               this.settings = newSettings != null ? newSettings : new PlayerEngineSettings();
               List<Item> baritoneCanPlace = Arrays.stream(this.settings.getThrowawayItems(this, true)).toList();
               this.getBaritoneSettings().acceptableThrowawayItems.get().addAll(baritoneCanPlace);
               if ((!this.getUserTaskChain().isActive() || this.getUserTaskChain().isRunningIdleTask())
                     && this.getModSettings().shouldRunIdleCommandWhenNotActive()) {
                  this.getUserTaskChain().signalNextTaskToBeIdleTask();
                  this.getCommandExecutor().executeWithPrefix(this.getModSettings().getIdleCommand());
               }

               this.getExtraBaritoneSettings().avoidBlockBreak(this.userBlockRangeTracker::isNearUserTrackedBlock);
               this.getExtraBaritoneSettings().avoidBlockPlace(this.entityStuckTracker::isBlockedByEntity);
            });
      Playground.IDLE_TEST_INIT_FUNCTION(this);

      // AI setup: (should be at end to ensure as many things are not null as
      // possible)
      ConversationManager.getOrCreateEventQueueData(this);
      this.aiPersistantData = new AIPersistantData(this, character);
      this.player2apiService = new Player2APIService(this, player2GameId);
      staticAPIServices.put(this.getEntity().getUUID(), this.player2apiService);
      staticControllers.put(this.getEntity().getUUID(), this);
      this.chunkLoader = new ChunkLoadingTracker(this);
   }

   public void serverTick() {
      this.inputControls.onTickPre();
      this.storageTracker.setDirty();
      this.miscBlockTracker.tick();
      this.trackerManager.tick();
      this.blockScanner.tick();
      this.taskRunner.tick();
      this.cacheTracker.tick();
      this.inputControls.onTickPost();
      this.baritone.serverTick();
      this.player2apiService.trySendHeartbeat();
      this.chunkLoader.tick();
   }

   public static void staticServerTick(MinecraftServer server) {
      com.player2.playerengine.util.time.TimerGame.incrementServerTick();
      ConversationManager.injectOnTick(server);
      if (server != null && server.getTickCount() % 200 == 0) {
         pruneStaleControllers(server);
      }
   }

   /**
    * Removes this controller from global registries (admin commands, API routing, agentic snapshots).
    * Call when the companion entity is removed or discarded — not on {@link #stop()} alone.
    */
   public void unregisterFromGlobalRegistry() {
      unregisterFromGlobalRegistry(this.getEntity().getUUID());
   }

   public static void unregisterFromGlobalRegistry(UUID entityUuid) {
      if (entityUuid == null) {
         return;
      }
      staticControllers.remove(entityUuid);
      staticAPIServices.remove(entityUuid);
      AgenticRunRegistry.clear(entityUuid);
   }

   /**
    * Drops registry entries whose entity no longer exists on the server (despawn, world unload, death).
    *
    * @return number of stale entries removed
    */
   public static int pruneStaleControllers(MinecraftServer server) {
      if (server == null || staticControllers.isEmpty()) {
         return 0;
      }
      int removed = 0;
      for (Iterator<Map.Entry<UUID, PlayerEngineController>> it = staticControllers.entrySet().iterator();
           it.hasNext(); ) {
         Map.Entry<UUID, PlayerEngineController> entry = it.next();
         UUID uuid = entry.getKey();
         PlayerEngineController controller = entry.getValue();
         if (!isControllerEntityActive(server, controller, uuid)) {
            it.remove();
            staticAPIServices.remove(uuid);
            AgenticRunRegistry.clear(uuid);
            removed++;
         }
      }
      return removed;
   }

   private static boolean isControllerEntityActive(MinecraftServer server,
                                                   PlayerEngineController controller,
                                                   UUID entityUuid) {
      if (controller == null) {
         return false;
      }
      try {
         LivingEntity entity = controller.getEntity();
         if (entity == null || entity.isRemoved()) {
            return false;
         }
         if (!entity.isAlive()) {
            return false;
         }
         if (entity.level() instanceof ServerLevel serverLevel && serverLevel.getEntity(entityUuid) == entity) {
            return true;
         }
         for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(entityUuid) == entity) {
               return true;
            }
         }
         // Chunk unloaded: keep registry entry until the entity is actually removed.
         return true;
      } catch (RuntimeException ignored) {
         return false;
      }
   }

   /** Label for {@code chain status}: character display name + short entity id. */
   public String getChainStatusDisplayName() {
      Character character = this.getAIPersistantData() != null ? this.getAIPersistantData().getCharacter() : null;
      String name = character != null ? character.shortName() : this.getEntity().getName().getString();
      String shortId = this.getEntity().getUUID().toString().substring(0, 8);
      return name + " (" + shortId + ")";
   }

   /**
    * Stops Baritone/input and cancels the user task chain. When a tracked step is running,
    * {@link StopReason#CANCELLED_OPERATOR} is recorded on the step execution log.
    */
   public void stop() {
      stop(StopReason.CANCELLED_OPERATOR);
   }

   /**
    * Same as {@link #stop()} but records {@code trackedStepCancelReason} on the active
    * {@link TaskStepExecutorAdapter} when applicable (e.g. disconnect policy).
    */
   public void stop(StopReason trackedStepCancelReason) {
      this.stepExecutorAdapter.armPendingChainCancel(trackedStepCancelReason);
      this.getUserTaskChain().cancel(this);
      if (this.taskRunner.getCurrentTaskChain() != null) {
         this.taskRunner.getCurrentTaskChain().stop();
      }

      this.getTaskRunner().disable();
      this.getBaritone().getPathingBehavior().forceCancel();
      this.getBaritone().getInputOverrideHandler().clearAllKeys();
   }

   /**
    * Despawn/dismiss/removal hook (cross-mod contract with Player2NPC). When the companion is torn
    * down while a task or agentic run is in flight, the run silently vanished and the model's next
    * turn began with a fresh greeting and no knowledge of what was running — so the model could (and
    * did) falsely claim the task succeeded. This emits the interruption to BOTH audiences (DESIGN.md
    * §3) before {@code stop} cancels the chains:
    * <ul>
    *   <li>the player gets a concise chat line (only when an owner ServerPlayer is online);</li>
    *   <li>the model gets an InfoMessage on THIS companion's conversation queue, enqueued BEFORE the
    *       call site's {@code ConversationManager.despwnCompanion} wipes that queue.</li>
    * </ul>
    * MUST be called by Player2NPC despawn sites in place of {@code stop()}, ahead of
    * {@code unregisterFromGlobalRegistry()} and {@code despwnCompanion(uuid)}. {@code ownerPlayer} may
    * be {@code null} (e.g. the entity {@code remove} path with the owner offline): the player line is
    * then skipped but the model InfoMessage is still enqueued, preserving truthfulness.
    */
   public void stopWithRespawnNotification(ServerPlayer ownerPlayer) {
      boolean active = this.hasActiveNonIdleUserTask()
            || AgenticRunRegistry.get(this.getEntity().getUUID()).isPresent();
      if (active) {
         // Model-facing (truthfulness-directed): enqueue onto THIS companion's queue before it is wiped.
         AiConversationFeedback.enqueueInfo(this,
               "You were despawned while a task was running. The task has been cancelled and will not"
                     + " resume. Do not claim the task completed or succeeded.");
         // Player-facing: concise human chat line, only when the owner is an online ServerPlayer.
         if (ownerPlayer != null && ownerPlayer.getServer() != null) {
            Character character = this.getAIPersistantData() != null
                  ? this.getAIPersistantData().getCharacter() : null;
            String name = character != null && character.shortName() != null
                  ? character.shortName() : "Your companion";
            AgentSideEffects.broadcastChatToPlayer(ownerPlayer.getServer(),
                  name + " was despawned while a task was running — the task has been stopped and will"
                        + " not resume.",
                  ownerPlayer);
         }
      }
      this.stop(StopReason.CANCELLED_RESPAWN);
   }

   private void initializeBaritoneSettings() {
      this.getExtraBaritoneSettings().canWalkOnEndPortal(false);
      this.getExtraBaritoneSettings().avoidBlockPlace(this.entityStuckTracker::isBlockedByEntity);
      this.getExtraBaritoneSettings().avoidBlockBreak(this.userBlockRangeTracker::isNearUserTrackedBlock);
      this.getBaritoneSettings().freeLook.set(false);
      this.getBaritoneSettings().overshootTraverse.set(true);
      this.getBaritoneSettings().allowOvershootDiagonalDescend.set(true);
      this.getBaritoneSettings().allowInventory.set(true);
      this.getBaritoneSettings().allowParkour.set(false);
      this.getBaritoneSettings().allowParkourAscend.set(false);
      this.getBaritoneSettings().allowParkourPlace.set(false);
      this.getBaritoneSettings().allowDiagonalDescend.set(false);
      this.getBaritoneSettings().allowDiagonalAscend.set(false);
      this.getBaritoneSettings().fadePath.set(true);
      this.getBaritoneSettings().mineScanDroppedItems.set(false);
      this.getBaritoneSettings().mineDropLoiterDurationMSThanksLouca.set(0L);
      this.getExtraBaritoneSettings().configurePlaceBucketButDontFall(true);
      this.getBaritoneSettings().randomLooking.set(0.0);
      this.getBaritoneSettings().randomLooking113.set(0.0);
      this.getBaritoneSettings().failureTimeoutMS.reset();
      this.getBaritoneSettings().planAheadFailureTimeoutMS.reset();
      this.getBaritoneSettings().movementTimeoutTicks.reset();
   }

   private void initializeCommands() {
      try {
         PlayerEngineCommands.init(this);
      } catch (Exception var2) {
         var2.printStackTrace();
      }
   }

   public void runUserTask(Task task, Runnable onFinish) {
      this.userTaskChain.runTask(this, task, onFinish);
   }

   public void runUserTask(Task task) {
      this.runUserTask(task, () -> {
      });
   }

   /**
    * Submit a task for tracked execution through the Phase A2 executor adapter.
    *
    * The adapter validates preconditions, records PENDING -> RUNNING ->
    * SUCCEEDED/FAILED state transitions, and always invokes {@code onComplete}
    * at the terminal state so CommandExecutor's sequential chain is preserved.
    *
    * @param stepId         Stable log identifier for this step.
    * @param stepKind       Action family descriptor (e.g. {@code follow_player}).
    * @param task           Pre-built Task to execute on UserTaskChain.
    * @param rollbackPolicy Compensating action policy on failure.
    * @param onComplete     Callback invoked at any terminal state.
    * @return The StepExecution tracker for observing state and log.
    */
   public StepExecution runUserTaskTracked(String stepId, String stepKind,
                                           Task task, RollbackPolicy rollbackPolicy,
                                           Runnable onComplete) {
      return this.stepExecutorAdapter.submit(stepId, stepKind, task, rollbackPolicy, onComplete);
   }

   public IStepExecutorAdapter getStepExecutorAdapter() {
      return this.stepExecutorAdapter;
   }

   public void cancelUserTask() {
      this.userTaskChain.cancel(this);
   }

   public CommandExecutor getCommandExecutor() {
      return this.commandExecutor;
   }

   public LivingEntity getEntity() {
      return this.ctx.entity();
   }

   public ServerLevel getWorld() {
      return this.ctx.world();
   }

   public IInteractionController getInteractionManager() {
      return this.ctx.playerController();
   }

   public IBaritone getBaritone() {
      return this.baritone;
   }

   public com.player2.playerengine.automaton.api.Settings getBaritoneSettings() {
      return this.baritone.settings();
   }

   public AdditionalBaritoneSettings getExtraBaritoneSettings() {
      return ((Baritone) this.baritone).getExtraBaritoneSettings();
   }

   public TaskRunner getTaskRunner() {
      return this.taskRunner;
   }

   public UserTaskChain getUserTaskChain() {
      return this.userTaskChain;
   }

   public boolean hasActiveNonIdleUserTask() {
      return this.userTaskChain.hasActiveNonIdleUserTask();
   }

   public java.util.Optional<com.player2.playerengine.executor.StepExecution> getActiveTrackedStep() {
      return this.stepExecutorAdapter.getActiveExecution();
   }

   public BotBehaviour getBehaviour() {
      return this.botBehaviour;
   }

   public boolean isPaused() {
      return this.paused;
   }

   public void setPaused(boolean pausing) {
      this.paused = pausing;
   }

   public Task getStoredTask() {
      return this.storedTask;
   }

   public void setStoredTask(Task currentTask) {
      this.storedTask = currentTask;
   }

   public ItemStorageTracker getItemStorage() {
      return this.storageTracker;
   }

   public EntityTracker getEntityTracker() {
      return this.entityTracker;
   }

   public CraftingRecipeTracker getCraftingRecipeTracker() {
      return this.craftingRecipeTracker;
   }

   public BlockScanner getBlockScanner() {
      return this.blockScanner;
   }

   public SimpleChunkTracker getChunkTracker() {
      return this.chunkTracker;
   }

   public MiscBlockTracker getMiscBlockTracker() {
      return this.miscBlockTracker;
   }

   public PlayerEngineSettings getModSettings() {
      if (this.settings == null) {
         this.settings = new PlayerEngineSettings();
      }
      return this.settings;
   }

   public FoodChain getFoodChain() {
      return this.foodChain;
   }

   public MobDefenseChain getMobDefenseChain() {
      return this.mobDefenseChain;
   }

   public MLGBucketFallChain getMLGBucketChain() {
      return this.mlgBucketChain;
   }

   public void log(String message) {
      Debug.logMessage(message);
   }

   public void logWarning(String message) {
      Debug.logWarning(message);
   }

   public static boolean inGame() {
      return true;
   }

   public LivingEntity getPlayer() {
      return this.ctx.entity();
   }

   public InputControls getInputControls() {
      return this.inputControls;
   }

   public SlotHandler getSlotHandler() {
      return this.slotHandler;
   }

   public PickupArmorEvalQueue getPickupArmorEvalQueue() {
      return this.pickupArmorEvalQueue;
   }

   public PickupWeaponEvalQueue getPickupWeaponEvalQueue() {
      return this.pickupWeaponEvalQueue;
   }

   public ExplicitEquipPolicy getExplicitEquipPolicy() {
      return this.explicitEquipPolicy;
   }

   public LivingEntityInventory getInventory() {
      return this.getBaritone().getEntityContext().inventory();
   }

   public PlayerExtraController getControllerExtras() {
      return this.extraController;
   }

   public void setChatClefEnabled(boolean enabled) {
      ConversationManager.getOrCreateEventQueueData(this).setEnabled(enabled);

      if (!enabled) {
         this.getUserTaskChain().cancel(this);
         this.getTaskRunner().disable();
      }
   }

   public void logCharacterMessage(String message, Character character, boolean isPublic) {
      int maxLength = 256;
      int start = 0;

      while (start < message.length()) {
         int end = Math.min(start + maxLength, message.length());
         String chunk = message.substring(start, end);
         if (chunk.length() > 0 && !chunk.isBlank()) {
            Debug.logCharacterMessage(chunk, character, isPublic);
         }

         start = end;
      }
   }

   public Player getOwner() {
      return this.owner;
   }

   public void setOwner(Player owner) {
      this.owner = owner;
      aiPersistantData.updateSystemPrompt();
   }

   public boolean isOwner(UUID playerToCheck) {
      return playerToCheck.equals(owner.getUUID());
   }

   public AIPersistantData getAIPersistantData() {
      return this.aiPersistantData;
   }

   public Player2APIService getPlayer2APIService() {
      return this.player2apiService;
   }

   public String getOwnerUsername() {
      if (getOwner() == null) {
         return "UNKNOWN OWNER";
      }
      return getOwner().getName().getString();
   }

   public Optional<ServerPlayer> getClosestPlayer() {
      return this.getWorld().players().stream().sorted((a, b) -> {
         float adist = a.distanceTo(this.getEntity());
         float bdist = b.distanceTo(this.getEntity());
         return Float.compare(adist, bdist);
      }).findFirst();
   }

   /**
    * Player-facing agentic progress broadcast (non-milestone). Convenience overload that applies the
    * {@link #MIN_REPORT_INTERVAL_MS} throttle. See {@link #reportAgenticProgress(String, boolean)}.
    */
   public void reportAgenticProgress(String message) {
      reportAgenticProgress(message, false);
   }

   /**
    * Deterministic, throttled/deduplicated agentic progress reporting to the OWNING player. Reuses
    * the existing chat channel ({@link AgentSideEffects#broadcastChatToPlayer}); it adds NO Player2
    * API call and consumes no model budget — the {@code message} is a pre-built deterministic string
    * (a step's {@code describeProgress()} output or a fixed milestone/failure note).
    *
    * <p>Audience is OWNER-SCOPED: it targets {@link #getOwner()} when that owner is an online
    * {@link ServerPlayer}, else the nearest player, else no-ops. This INTENTIONALLY differs from
    * {@link AgentSideEffects#onEntityMessage} which broadcasts AI dialogue to ALL players (its
    * owner-filter is commented out). Progress is a private status feed for the owner, not dialogue —
    * do not "fix" this to all-players.
    *
    * <p>Throttle/dedup: non-milestone lines are skipped when fired within {@link #MIN_REPORT_INTERVAL_MS}
    * of the last broadcast. Milestone/failure lines ({@code milestone == true}) BYPASS the interval so a
    * failure is never swallowed by the rate limit, but ALL lines still dedup against the last broadcast
    * message for this bot. No-ops on null/blank message, missing server, or no resolvable player.
    *
    * @param message   pre-built deterministic progress/failure text (no model call)
    * @param milestone when true, bypass the interval throttle (still deduplicated)
    */
   public void reportAgenticProgress(String message, boolean milestone) {
      // 1. blank guard.
      if (message == null || message.isBlank()) {
         return;
      }
      // 2. resolve server — there is no getServer() on the controller; use getPlayer().getServer().
      MinecraftServer server = (getPlayer() != null) ? getPlayer().getServer() : null;
      if (server == null) {
         return;
      }
      // 3. resolve target ServerPlayer. getOwner() returns Player (NOT ServerPlayer), so the
      //    instanceof-cast is required; fall back to the nearest player; else no-op.
      ServerPlayer target;
      if (getOwner() instanceof ServerPlayer sp) {
         target = sp;
      } else {
         target = getClosestPlayer().orElse(null);
      }
      if (target == null) {
         return;
      }
      long now = System.currentTimeMillis();
      // 4. throttle (non-milestone only): skip if too soon after the last broadcast.
      if (!milestone && (now - lastAgenticReportMs) < MIN_REPORT_INTERVAL_MS) {
         return;
      }
      // 5. dedup: skip identical consecutive messages (applies to milestones too).
      if (message.equals(lastAgenticReportMessage)) {
         return;
      }
      // 6. broadcast via the single existing player-chat path, prefixed with the bot name.
      AgentSideEffects.broadcastChatToPlayer(server, "[" + agenticReportBotName() + "] " + message, target);
      // 7. update throttle/dedup state.
      lastAgenticReportMs = now;
      lastAgenticReportMessage = message;
   }

   /** Short bot name used as the {@code [<botName>] } prefix on agentic progress/failure lines. */
   private String agenticReportBotName() {
      Character character = this.getAIPersistantData() != null ? this.getAIPersistantData().getCharacter() : null;
      if (character != null && character.shortName() != null && !character.shortName().isBlank()) {
         return character.shortName();
      }
      return this.getEntity().getName().getString();
   }

   public boolean getShouldDefendFromHostiles(){
      return this.shouldDefendFromHostiles;
   }
   public void setShouldDefendFromHostiles(boolean toSet){
      this.shouldDefendFromHostiles = toSet;
   }
}
