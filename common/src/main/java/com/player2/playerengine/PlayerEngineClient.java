package com.player2.playerengine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.client.PlayerEngineClientConfigCache;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import com.player2.playerengine.player2api.utils.AudioUtils;
import com.player2.playerengine.automaton.KeepName;
import com.player2.playerengine.automaton.client.CustomFishingBobberRenderer;
import com.player2.playerengine.player2api.manager.HeartbeatManager;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import com.player2.playerengine.player2api.ChatclefConfigPersistantState;
import com.player2.playerengine.player2api.utils.STTUtils;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@KeepName
public final class PlayerEngineClient {
   public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);
   private static final int MAX_PAYLOAD_BYTES = 1_048_576;

   public static boolean isTtsEnabled() {
      return ChatclefConfigPersistantState.isTtsEnabled();
   }

   public static void setTtsEnabled(boolean enabled) {
      ChatclefConfigPersistantState.setTtsEnabled(enabled);
      syncTtsPreferenceToServer();
   }

   public static void syncTtsPreferenceToServer() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.getConnection() == null) {
         return;
      }
      FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
      buf.writeBoolean(ChatclefConfigPersistantState.isTtsEnabled());
      mc.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
            PlayerEngine.TTS_PREFERENCE_PACKET_ID, buf));
   }
   private static int heartbeatTickCounter;

   public static void onInitializeClient() {
      EntityRendererRegistry.register(PlayerEngine.FISHING_BOBBER, CustomFishingBobberRenderer::new);
      STTUtils.onInitialize();
      ClientLifecycleEvent.CLIENT_STOPPING.register(client -> STTUtils.shutdown());
      NetworkManager.registerReceiver(NetworkManager.Side.S2C,
            new ResourceLocation("playerengine", "stream_tts"), (buf, context) -> {
               if (!ChatclefConfigPersistantState.isTtsEnabled()) {
                  return;
               }
               String clientId = buf.readUtf();
               String token = buf.readUtf();
               String text = buf.readUtf();
               double speed = buf.readDouble();
               int voiceIdCount = buf.readVarInt();
               String[] voiceIds = new String[voiceIdCount];
               for (int i = 0; i < voiceIdCount; i++) {
                  voiceIds[i] = buf.readUtf();
               }
               String botUuid = buf.readUtf();

               CompletableFuture.runAsync(() -> {
                  try {
                     String tokenToUse = token == null || token.isBlank()
                           ? Player2HTTPUtils.awaitToken(Minecraft.getInstance().player, clientId)
                           : token;
                     AudioUtils.streamAudio(clientId, tokenToUse, text, speed, voiceIds);
                     sendTtsPlaybackDone(botUuid);
                  } catch (Exception e) {
                     LOGGER.warn("Client: TTS request failed for clientId={}: {}", clientId, e.getMessage());
                  }
               });
            });

      NetworkManager.registerReceiver(NetworkManager.Side.S2C,
            PlayerEngine.CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID,
            (buf, context) -> {
               String requestId = buf.readUtf();
               String clientId = buf.readUtf();
               String method = buf.readUtf();
               String endpoint = buf.readUtf();
               byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
               CompletableFuture.runAsync(() -> handlePlayer2ProxyRequest(requestId, clientId, method, endpoint, payload));
            });

      NetworkManager.registerReceiver(NetworkManager.Side.S2C, Player2ServerNetworking.SYNC_SERVER_PLAYER2,
            (buf, context) -> {
               boolean dedicated = buf.readBoolean();
               String payerMode = buf.readUtf();
               boolean ownerOffline = buf.readBoolean();
               String hbId = buf.readUtf();
               PlayerEngineClientConfigCache.applyFromSync(dedicated, payerMode, ownerOffline, hbId);
            });

      ClientTickEvent.CLIENT_POST.register(client -> {
         heartbeatTickCounter++;
         if (heartbeatTickCounter % 1200 != 0) {
            return;
         }
         if (!PlayerEngineClientConfigCache.shouldSendPlayerHeartbeat()) {
            return;
         }
         Minecraft mc = Minecraft.getInstance();
         if (mc.player == null || mc.getConnection() == null) {
            return;
         }
         String uid = mc.player.getName().getString();
         String cid = PlayerEngineClientConfigCache.getHeartbeatClientId();
         if (!HeartbeatManager.shouldHeartbeat(uid, cid)) {
            return;
         }
         CompletableFuture.runAsync(() -> {
            try {
               Player2HTTPUtils.sendRequest(mc.player, cid, "/v1/health", false, null);
               HeartbeatManager.storeHeartbeatTime(uid, cid);
            } catch (Exception e) {
               LOGGER.debug("Client heartbeat skipped: {}", e.getMessage());
            }
         });
      });
   }

   private static void handlePlayer2ProxyRequest(String requestId, String clientId, String method, String endpoint,
         byte[] payload) {
      Minecraft client = Minecraft.getInstance();
      if (client.player == null || client.getConnection() == null) {
         sendProxyResponse(requestId, false, "Client player connection is not ready");
         return;
      }

      try {
         JsonObject body = null;
         if (payload != null && payload.length > 0) {
            body = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
         }
         LOGGER.info("Client: Player2 proxy {} {} {}", requestId, method, endpoint);
         JsonObject response = new JsonObject();
         java.util.Map<String, JsonElement> map = Player2HTTPUtils.sendRequest(client.player, clientId, endpoint,
               method, body);
         map.forEach(response::add);
         sendProxyResponse(requestId, true, response.toString());
      } catch (Exception e) {
         LOGGER.warn("Client: Player2 proxy {} failed: {}", requestId, e.getMessage());
         sendProxyResponse(requestId, false, e.getMessage() == null ? e.toString() : e.getMessage());
      }
   }

   private static void sendTtsPlaybackDone(String botUuid) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.getConnection() == null || mc.player == null) {
         return;
      }
      mc.execute(() -> {
         if (mc.getConnection() == null) {
            return;
         }
         FriendlyByteBuf doneBuf = new FriendlyByteBuf(Unpooled.buffer());
         doneBuf.writeUtf(botUuid);
         mc.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
               PlayerEngine.TTS_PLAYBACK_DONE_PACKET_ID, doneBuf));
      });
   }

   private static void sendProxyResponse(String requestId, boolean success, String payloadText) {
      Minecraft client = Minecraft.getInstance();
      client.execute(() -> {
         if (client.player == null || client.getConnection() == null) {
            return;
         }
         byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
         FriendlyByteBuf responseBuf = new FriendlyByteBuf(Unpooled.buffer());
         responseBuf.writeUtf(requestId);
         responseBuf.writeBoolean(success);
         responseBuf.writeByteArray(payload);
         client.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
               PlayerEngine.CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID, responseBuf));
      });
   }
}
