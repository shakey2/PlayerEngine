package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.manager.HeartbeatManager;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import com.player2.playerengine.player2api.utils.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class Player2APIService {
   private static final Logger LOGGER = LogManager.getLogger();

   private String clientId;
   private PlayerEngineController controller;

   /** Effective billing for the current AI/conversation turn (set by {@link AgentConversationData#process}). */
   private volatile Player2PayerResolution.ApiBillingContext activeBillingContext;

   public Player2APIService(PlayerEngineController controller, String clientId) {
      this.clientId = clientId;
      this.controller = controller;
   }

   public String getClientId() {
      return clientId;
   }

   public PlayerEngineController getController() {
      return controller;
   }

   public void setActiveBillingContext(Player2PayerResolution.ApiBillingContext ctx) {
      this.activeBillingContext = ctx;
   }

   public Player2PayerResolution.ApiBillingContext billingOrFallback() {
      Player2PayerResolution.ApiBillingContext ctx = activeBillingContext;
      if (ctx != null) {
         return ctx;
      }
      return Player2PayerResolution.resolve(controller, null, clientId);
   }

   private Map<String, JsonElement> api(String method, String endpoint, JsonObject body) throws Exception {
      return Player2ApiDispatcher.route(controller, clientId, method, endpoint, body, billingOrFallback());
   }

   public JsonObject completeConversation(ConversationHistory conversationHistory) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = new JsonArray();

      for (JsonObject msg : conversationHistory.getListJSON()) {
         messagesArray.add(msg);
      }
      String lastMessageForDebug = conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1)
            .toString();

      requestBody.add("messages", messagesArray);
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = sendChatCompletionRequest(requestBody);
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               String content = messageObject.get("content").getAsString();
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return Utils.parseCleanedJson(content);
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   public String completeConversationToString(ConversationHistory conversationHistory) throws Exception {
      JsonObject requestBody = new JsonObject();
      JsonArray messagesArray = new JsonArray();

      for (JsonObject msg : conversationHistory.getListJSON()) {
         messagesArray.add(msg);
      }

      requestBody.add("messages", messagesArray);
      String lastMessageForDebug = conversationHistory.getListJSON().get(conversationHistory.getListJSON().size() - 1)
            .toString();
      LOGGER.info("Called complete conversation (string) HTTP request, last msg={}", lastMessageForDebug);
      Map<String, JsonElement> responseMap = sendChatCompletionRequest(requestBody);
      if (responseMap.containsKey("choices")) {
         JsonArray choices = responseMap.get("choices").getAsJsonArray();
         if (choices.size() != 0) {
            JsonObject messageObject = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (messageObject != null && messageObject.has("content")) {
               LOGGER.info("Finished complete conversation HTTP request last msg={}", lastMessageForDebug);
               return messageObject.get("content").getAsString();
            }
         }
      }

      throw new Exception("Invalid response format: " + responseMap.toString());
   }

   private Map<String, JsonElement> sendChatCompletionRequest(JsonObject requestBody) throws Exception {
      return api("POST", "/v1/chat/completions", requestBody);
   }

   public void textToSpeech(String message, Character character, Consumer<Map<String, JsonElement>> onFinish) {
      try {
         ServerPlayer owner = (ServerPlayer) controller.getOwner();
         MinecraftServer server = owner.getServer();
         if (server == null) return;

         double TTS_RANGE = 64.0;

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == owner.level() && player.distanceTo(owner) <= TTS_RANGE) {
               RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                     player.registryAccess());
               buf.writeUtf(clientId);
               buf.writeUtf("");
               buf.writeUtf(message);
               buf.writeDouble(1);
               buf.writeVarInt(character.voiceIds().length);
               for (String id : character.voiceIds()) {
                  buf.writeUtf(id);
               }

               player.connection.send(NetworkManager.toPacket(NetworkManager.Side.S2C,
                     ResourceLocation.fromNamespaceAndPath("playerengine", "stream_tts"), buf));
            }
         }
         onFinish.accept(null);
      } catch (Exception var9) {
         LOGGER.error("Error broadcasting TTS", var9);
      }
   }

   // public void textToSpeech(String message, Character character,
   // Consumer<Map<String, JsonElement>> onFinish) {
   // try {
   // JsonObject requestBody = new JsonObject();
   // requestBody.addProperty("speed", 1);
   // requestBody.addProperty("text", message);
   // requestBody.addProperty("audio_format", "mp3");
   // JsonArray voiceIdsArray = new JsonArray();
   //
   // for (String voiceId : character.voiceIds()) {
   // voiceIdsArray.add(voiceId);
   // }
   //
   // requestBody.add("voice_ids", voiceIdsArray);
   // LOGGER.info("TTS request w/ msg={}", message);
   // Map<String, JsonElement> responseMap =
   // Player2HTTPUtils.sendRequest(controller.getOwner(), clientId,"/v1/tts/speak",
   // true, requestBody);
   // onFinish.accept(responseMap);
   // } catch (Exception var9) {
   // }
   // }

   public void startSTT() {
      JsonObject requestBody = new JsonObject();
      requestBody.addProperty("timeout", 180);

      try {
         api("POST", "/v1/stt/start", requestBody);
      } catch (Exception var3) {
         System.err.println("[Player2APIService/startSTT]: Error" + var3.getMessage());
      }
   }

   public String stopSTT() {
      try {
         Map<String, JsonElement> responseMap = api("POST", "/v1/stt/stop", null);
         if (!responseMap.containsKey("text")) {
            throw new Exception("Could not find key 'text' in response");
         } else {
            return responseMap.get("text").getAsString();
         }
      } catch (Exception var2) {
         return var2.getMessage();
      }
   }

   public void trySendHeartbeat() {
      var cfg = Player2ServerConfigHolder.get();
      String ownerName = controller.getOwnerUsername();
      boolean tokenPresent = !TokenStorage.getToken(ownerName, clientId).isEmpty();
      boolean ownerOnline = controller.getOwner() instanceof ServerPlayer sp
            && controller.getPlayer().level().getServer().getPlayerList().getPlayer(sp.getUUID()) != null;
      if (!Player2ServerConfigHolder.shouldSendServerControllerHeartbeat(cfg, tokenPresent, ownerOnline)) {
         return;
      }
      if (HeartbeatManager.shouldHeartbeat(ownerName, clientId)) {
         if (tokenPresent) {
            sendHeartbeat();
         }
         HeartbeatManager.storeHeartbeatTime(ownerName, clientId);
      }
   }

   public void sendHeartbeat() {
      com.player2.playerengine.player2api.auth.AuthenticationManager.getExecutor().submit(() -> {
         try {
            System.out.println("Sending Heartbeat " + clientId);
            Player2PayerResolution.ApiBillingContext bill = Player2PayerResolution.resolve(controller, null, clientId);
            Player2ApiDispatcher.route(controller, clientId, "GET", "/v1/health", null, bill);
            System.out.println("Heartbeat Successful");
         } catch (Exception var2) {
            System.err.printf("Heartbeat Fail: %s\n", var2.getMessage());
         }
      });
   }

   /**
    * Search for schematics given a query
    * 
    * @return List of schematics matching the query
    */
   public List<JsonObject> searchSchematics(String query) {
      try {
         JsonObject requestBody = new JsonObject();
         requestBody.addProperty("query", query);
         requestBody.addProperty("max_results", 10);

         Map<String, JsonElement> responseMap = api("POST", "/v1/minecraft/schematics/search", requestBody);

         JsonElement resultsJsonElement = responseMap.get("results");
         if (resultsJsonElement != null && resultsJsonElement.isJsonArray()) {
            JsonArray resultsJsonArray = resultsJsonElement.getAsJsonArray();

            List<JsonObject> schematics = new ArrayList<>();
            for (JsonElement voiceElement : resultsJsonArray) {
               JsonObject voiceObject = voiceElement.getAsJsonObject();
               schematics.add(voiceObject);
            }
            return schematics;
         } else {
            System.err.println(
                  "No results field array in response with keys: [" + String.join(",", responseMap.keySet()) + "]");
         }
      } catch (Exception e) {
         System.err.println("Search schematics request failed: " + e.getMessage());
      }
      return Collections.emptyList();
   }

   /**
    * Get schematic binary given a schematic ID
    * 
    * @return Schematic binary data
    */
   public String getSchematicBinary(String schematicId) {
      try {
         Map<String, JsonElement> responseMap = api("GET", "/v1/minecraft/schematics/" + schematicId, null);
         JsonElement dataJsonElement = responseMap.get("data");
         if (dataJsonElement != null && dataJsonElement.isJsonPrimitive()) {
            return dataJsonElement.getAsString();
         } else {
            System.err.println(
                  "No data field primitive in response with keys: [" + String.join(",", responseMap.keySet()) + "]");
         }
      } catch (Exception e) {
         System.err.println("Get schematic binary request failed: " + e.getMessage());
      }
      return "";
   }

   public String getGameData(String key) {
      try {
         Map<String, JsonElement> responseMap = api("GET",
               "/v1/games/" + clientId + "/data/user?key=" + key, null);
         JsonElement valueElement = responseMap.get("value");
         if (valueElement != null && valueElement.isJsonPrimitive()) {
            return valueElement.getAsString();
         }
      } catch (Exception e) {
         // Usually 404 when key is not found
         System.err.println("Get game data request failed: " + e.getMessage());
      }
      return null;
   }

   public boolean setGameData(String key, String value) {
      try {
         JsonObject requestBody = new JsonObject();
         requestBody.addProperty("key", key);
         requestBody.addProperty("value", value);
         Map<String, JsonElement> responseMap = api("PUT", "/v1/games/" + clientId + "/data/user", requestBody);
         JsonElement successElement = responseMap.get("success");
         if (successElement != null && successElement.isJsonPrimitive()) {
            return successElement.getAsBoolean();
         }
      } catch (Exception e) {
         System.err.println("Set game data request failed: " + e.getMessage());
      }
      return false;
   }
}
