package com.player2.playerengine;

import com.player2.playerengine.player2api.AgentSideEffects;
import com.google.common.base.Suppliers;
import com.player2.playerengine.automaton.KeepName;
import com.player2.playerengine.automaton.command.defaults.DefaultCommands;
import com.player2.playerengine.automaton.entity.CustomFishingBobberEntity;
import com.player2.playerengine.player2api.Player2ClientApiBridge;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.network.Player2DisconnectHandler;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import dev.architectury.event.events.common.PlayerEvent;
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
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;

@KeepName
public final class PlayerEngine {
   public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);

   public static final String MOD_ID = "playerengine";
   public static final String MOD_NAME = "PlayerEngine";
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID = id("client_player2_proxy_request");
   public static final ResourceLocation CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID = id("client_player2_proxy_response");
   public static final TagKey<Item> EMPTY_BUCKETS = TagKey.create(Registries.ITEM, id("empty_buckets"));
   public static final TagKey<Item> WATER_BUCKETS = TagKey.create(Registries.ITEM, id("water_buckets"));
   private static final ThreadPoolExecutor threadPool;
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

   /** Allow the next logical server session to register shutdown again (integrated / same JVM). */
   public static void resetBackgroundExecutorsShutdownGate() {
      backgroundExecutorsShutDown.set(false);
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
      ExecutorShutdown.shutdownNowAwait("TTSManager", TTSManager.getExecutor());
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
      MCCommands.onInit();
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID,
            (buf, context) -> {
               Player2ClientApiBridge.handleClientProxyResponse(buf, (ServerPlayer) context.getPlayer());
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            new ResourceLocation("playerengine", "user_message"),
            (buf, context) -> {
               LOGGER.info("Server: Recieved user_message packet");
               String username = context.getPlayer().getName().getString();
               String message = buf.readUtf();
               ConversationManager.onUserChatMessage(new Event.UserMessage(message, username));
               AgentSideEffects.broadcastChatToAllPlayers(context.getPlayer().getServer(),
                     String.format("<%s> %s", context.getPlayer().getName().getString(), message));
            });
      NetworkManager.registerReceiver(NetworkManager.Side.C2S,
            new ResourceLocation("playerengine", "request_stt"),
            (buf, context) -> {
               LOGGER.info("Server: Recieved request_stt packet");
               String clientId = buf.readUtf();
               String username = context.getPlayer().getName().getString();
               String storedToken = TokenStorage.getToken(username, clientId);
               FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
               buf2.writeUtf(storedToken);
               LOGGER.info("Server: Sending response_stt packet w/ token {}", storedToken);
               ((ServerPlayer) context.getPlayer()).connection.send(NetworkManager.toPacket(
                     NetworkManager.Side.S2C,
                     new ResourceLocation("playerengine", "response_stt"), buf2));
            });
   }

   static {
      AtomicInteger threadCounter = new AtomicInteger(0);
      threadPool = new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> new Thread(r, MOD_NAME + " Worker " + threadCounter.incrementAndGet()));
   }
}
