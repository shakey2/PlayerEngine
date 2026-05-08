package com.player2.playerengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.utils.AudioUtils;
import com.player2.playerengine.automaton.KeepName;
import com.player2.playerengine.automaton.client.CustomFishingBobberRenderer;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import com.player2.playerengine.player2api.utils.STTUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@KeepName
public final class PlayerEngineClient {
   public static boolean enabledTTS = true;
   public static final Logger LOGGER = LogManager.getLogger(PlayerEngine.MOD_NAME);
   private static final int MAX_PAYLOAD_BYTES = 1_048_576;
   public static void onInitializeClient() {
      EntityRendererRegistry.register(PlayerEngine.FISHING_BOBBER, CustomFishingBobberRenderer::new);
      STTUtils.onInitialize();
      NetworkManager.registerReceiver(NetworkManager.Side.S2C, new ResourceLocation("playerengine", "stream_tts"), (buf, context) -> {
         if(!enabledTTS){
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

         CompletableFuture.runAsync(() -> {
            try {
               Minecraft client = Minecraft.getInstance();
               String tokenToUse = token == null || token.isBlank()
                     ? Player2HTTPUtils.awaitToken(client.player, clientId)
                     : token;
               AudioUtils.streamAudio(clientId, tokenToUse, text, speed, voiceIds);
            } catch (Exception e) {
               LOGGER.warn("Client: TTS request failed for clientId={}: {}", clientId, e.getMessage());
            }
         });
      });
      NetworkManager.registerReceiver(NetworkManager.Side.S2C,
         new ResourceLocation("playerengine", "response_stt"),
         (buf, context) -> {
            String token = buf.readUtf();
            LOGGER.info("Client: Recieved packet response_stt token from server isNullOrEmpty={}",
            token == null || token.isEmpty());
            if (token == null || token.isEmpty()) {
               return;
            }
            STTUtils.connect(token);
      });

      NetworkManager.registerReceiver(NetworkManager.Side.S2C,
            PlayerEngine.CLIENT_CHAT_COMPLETION_REQUEST_PACKET_ID,
            (buf, context) -> {
               String requestId = buf.readUtf();
               String clientId = buf.readUtf();
               byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
               String requestText = new String(payload, StandardCharsets.UTF_8);
               CompletableFuture.runAsync(() -> handleChatCompletionRequest(requestId, clientId, requestText));
            });
   }

   private static void handleChatCompletionRequest(String requestId, String clientId, String requestText) {
      Minecraft client = Minecraft.getInstance();
      if (client.player == null || client.getConnection() == null) {
         sendChatCompletionResponse(requestId, false, "Client player connection is not ready");
         return;
      }

      try {
         JsonObject requestBody = JsonParser.parseString(requestText).getAsJsonObject();
         LOGGER.info("Client: Calling local chat completion for request={} clientId={}", requestId, clientId);
         JsonObject response = new JsonObject();
         Player2HTTPUtils.sendRequest(client.player, clientId, "/v1/chat/completions", true, requestBody)
               .forEach(response::add);
         sendChatCompletionResponse(requestId, true, response.toString());
      } catch (Exception e) {
         LOGGER.warn("Client: Chat completion request {} failed: {}", requestId, e.getMessage());
         sendChatCompletionResponse(requestId, false, e.getMessage() == null ? e.toString() : e.getMessage());
      }
   }

   private static void sendChatCompletionResponse(String requestId, boolean success, String payloadText) {
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
               PlayerEngine.CLIENT_CHAT_COMPLETION_RESPONSE_PACKET_ID, responseBuf));
      });
   }
}
