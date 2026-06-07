package com.player2.playerengine;

import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.retrieval.RagIndex;
import com.google.common.base.Suppliers;
import com.player2.playerengine.automaton.KeepName;
import com.player2.playerengine.automaton.command.defaults.DefaultCommands;
import com.player2.playerengine.automaton.entity.CustomFishingBobberEntity;
import com.player2.playerengine.player2api.Player2ClientApiBridge;
import com.player2.playerengine.modintelligence.ModIntelligenceService;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.network.Player2DisconnectHandler;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import com.player2.playerengine.player2api.network.TtsClientPreferenceStore;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.manager.TTSManager;
import com.player2.playerengine.util.ExecutorShutdown;
import com.player2.playerengine.player2api.Event;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;

@KeepName
public final class PlayerEngine {
   public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

   public static final String MOD_ID = "playerengine";
   public static final String MOD_NAME = "PlayerEngine";
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID = id("client_player2_proxy_request");
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID = id("client_player2_proxy_response");
   public static final ResourceLocation TTS_PREFERENCE_PACKET_ID = id("tts_preference");
   public static final ResourceLocation TTS_PLAYBACK_DONE_PACKET_ID = id("tts_playback_done");
   public static final TagKey<Item> EMPTY_BUCKETS = TagKey.create(Registries.ITEM, id("empty_buckets"));
   public static final TagKey<Item> WATER_BUCKETS = TagKey.create(Registries.ITEM, id("water_buckets"));
   private static ThreadPoolExecutor threadPool;
   private static final AtomicBoolean backgroundExecutorsShutDown = new AtomicBoolean(false);

   public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(MOD_ID,
         Registries.ENTITY_TYPE);
   public static RegistrySupplier<EntityType<CustomFishingBobberEntity>> FISHING_BOBBER = ENTITY_TYPES.register(
         "custom_fishing_bobber",
         () -> EntityType.Builder
               .of((EntityType.EntityFactory<CustomFishingBobberEntity>) CustomFishingBobberEntity::new,
                     MobCategory.CREATURE)
               .sized(EntityType.FISHING_BOBBER.getWidth(), EntityType.FISHING_BOBBER.getHeight())
               .clientTrackingRange(64)
               .updateInterval(1)
               .build("custom_fishing_bobber")

   );

   public static ResourceLocation id(String path) {
      return new ResourceLocation(MOD_ID, path);
   }

   public static ThreadPoolExecutor getExecutor() {
      return threadPool;
   }

   /**
    * Allow the next logical server session to register shutdown again (integrated / same JVM).
    * Also recreates the worker pool if it was terminated by the previous session's shutdown,
    * so pathfinding threads can be submitted without a RejectedExecutionException.
    */
   public static void resetBackgroundExecutorsShutdownGate() {
      backgroundExecutorsShutDown.set(false);
      if (threadPool.isShutdown()) {
         threadPool = newWorkerPool();
      }
   }

   private static ThreadPoolExecutor newWorkerPool() {
      AtomicInteger threadCounter = new AtomicInteger(0);
      return new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> new Thread(r, MOD_NAME + " Worker " + threadCounter.incrementAndGet()));
   }

   /**
    * Stops mod-owned thread pools with bounded wait so dedicated shutdown does not hang on HTTP.
    * Idempotent per server session; paired with {@link #resetBackgroundExecutorsShutdownGate()}.
    */
   public static void shutdownBackgroundExecutors() {
      if (!backgroundExecutorsShutDown.compareAndSet(false, true)) {
         return;
      }
      ConversationManager.shutdownAndResetLLMCompleters();
      // Use the manager's reset so integrated-server restart in the same JVM gets a fresh
      // executor instead of being left with a terminated thread.
      TTSManager.shutdownAndReset();
      ExecutorShutdown.shutdownNowAwait("PlayerEngine.workerPool", threadPool);
      ExecutorShutdown.shutdownNowAwait("AuthenticationManager.auth", AuthenticationManager.getExecutor());
      ExecutorShutdown.shutdownNowAwait("AuthenticationManager.polling", AuthenticationManager.getPollingExecutor());
   }

   public static void onInitialize() {
      Player2ServerConfigHolder.load();
      PlayerEvent.PLAYER_QUIT.register(Player2DisconnectHandler::onPlayerQuit);
      PlayerEvent.PLAYER_JOIN.register(Player2ServerNetworking::sendConfigSync);
      DefaultCommands.registerAll();
      ENTITY_TYPES.register();
      PlayerEngineStorageMigration.runOnce();
      copyToolOverridesReadmeIfAbsent();
      RagIndex.initialize();
      MCCommands.onInit();
      TickEvent.SERVER_POST.register(PlayerEngineController::staticServerTick);
      ConversationManager.init();
      ModIntelligenceService.registerEventHandlers();
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID,
            (buf, context) -> {
               Player2ClientApiBridge.handleClientProxyResponse(buf, (ServerPlayer) context.getPlayer());
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            new ResourceLocation("playerengine", "user_message"),
            (buf, context) -> {
               String username = context.getPlayer().getName().getString();
               String message = buf.readUtf();
               LOGGER.info("Server: received user_message packet (voice/STT) from {} len={} preview=\"{}\"",
                     username, message.length(), com.player2.playerengine.player2api.utils.SttLogging.messagePreview(message));
               ConversationManager.onUserChatMessage(new Event.UserMessage(message, username, true));
               AgentSideEffects.broadcastChatToAllPlayers(context.getPlayer().getServer(),
                     String.format("<%s> %s", context.getPlayer().getName().getString(), message));
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            new ResourceLocation("playerengine", "request_stt"),
            (buf, context) -> {
               String clientId = buf.readUtf();
               String username = context.getPlayer().getName().getString();
               LOGGER.info("Server: received request_stt packet from {} clientId={}", username, clientId);
               String storedToken = TokenStorage.getToken(username, clientId);
               if (storedToken == null || storedToken.isBlank()) {
                  LOGGER.warn("Server: no stored STT token for user={} clientId={} (Player2 login may be required)",
                        username, clientId);
               }
               FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
               buf2.writeUtf(storedToken == null ? "" : storedToken);
               LOGGER.info("Server: sending response_stt packet to {} (tokenPresent={})",
                     username, storedToken != null && !storedToken.isBlank());
               ((ServerPlayer) context.getPlayer()).connection.send(NetworkManager.toPacket(
                     NetworkManager.Side.S2C,
                     new ResourceLocation("playerengine", "response_stt"), buf2));
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            TTS_PREFERENCE_PACKET_ID,
            (buf, context) -> {
               boolean enabled = buf.readBoolean();
               TtsClientPreferenceStore.setTtsEnabled(context.getPlayer().getUUID(), enabled);
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            TTS_PLAYBACK_DONE_PACKET_ID,
            (buf, context) -> {
               if (!Player2ServerConfigHolder.get().isBotTtsPlaybackAckEnabled()) {
                  return;
               }
               ServerPlayer sender = (ServerPlayer) context.getPlayer();
               String botUuidStr = buf.readUtf();
               UUID botUuid;
               try {
                  botUuid = UUID.fromString(botUuidStr);
               } catch (IllegalArgumentException e) {
                  LOGGER.warn("PlayerEngine: tts_playback_done invalid bot UUID: {}", botUuidStr);
                  return;
               }
               AgentConversationData botData = ConversationManager.queueData.get(botUuid);
               if (botData == null) {
                  return;
               }
               Player owner = botData.getMod().getOwner();
               if (owner == null || !owner.getUUID().equals(sender.getUUID())) {
                  return;
               }
               LOGGER.info("PlayerEngine: tts_playback_done ACK for bot={} from owner={}",
                     botData.getName(), sender.getName().getString());
               botData.clearTtsCooldown();
            });
   }

   /**
    * Copies {@code tool_overrides.README.md} from the JAR resources to
    * {@code playerengine/} on first launch so operators always have a reference
    * file alongside the live overlay location. Does nothing if the file already exists.
    */
   private static void copyToolOverridesReadmeIfAbsent() {
      try {
         java.nio.file.Path dest = PlayerEnginePaths.userFile("tool_overrides.README.md");
         if (!java.nio.file.Files.exists(dest)) {
            java.nio.file.Files.createDirectories(dest.getParent());
            try (java.io.InputStream in = PlayerEngine.class.getClassLoader()
                  .getResourceAsStream("tool_overrides.README.md")) {
               if (in != null) {
                  java.nio.file.Files.copy(in, dest);
                  LOGGER.info("PlayerEngine: copied tool_overrides.README.md to {}", dest);
               }
            }
         }
      } catch (java.io.IOException e) {
         LOGGER.warn("PlayerEngine: could not copy tool_overrides.README.md: {}", e.getMessage());
      }
   }

   static {
      threadPool = newWorkerPool();
   }
}
