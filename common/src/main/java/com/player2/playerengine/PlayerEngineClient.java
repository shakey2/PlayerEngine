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
import net.minecraft.network.RegistryFriendlyByteBuf;
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
      if (mc.getConnection() == null || mc.player == null) {
         return;
      }
      RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.player.registryAccess());
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
            ResourceLocation.fromNamespaceAndPath("playerengine", "stream_tts"), (buf, context) -> {
               if (!ChatclefConfigPersistantState.isTtsEnabled()) {
                  return;
               }
               // ---- read in the EXACT server write order (Player2APIService.textToSpeech) ----
               // legacy stream_tts fields:
               String clientId = buf.readUtf();
               String token = buf.readUtf();
               buf.readUtf(); // full stripped message — chunks (read below) are spoken instead; kept to preserve wire order
               double speed = buf.readDouble();
               int voiceIdCount = buf.readVarInt();
               String[] voiceIds = new String[voiceIdCount];
               for (int i = 0; i < voiceIdCount; i++) {
                  voiceIds[i] = buf.readUtf();
               }
               String botUuid = buf.readUtf();
               // appended bodylang-gesture fields (append-only, never reorder):
               int chunkCount = buf.readVarInt();
               String[] chunks = new String[chunkCount];
               for (int i = 0; i < chunkCount; i++) {
                  chunks[i] = buf.readUtf();
               }
               int boundaryCount = buf.readVarInt();
               // boundaryChunkIndex[b] = the chunk index AFTER which boundary b fires; segIndex is the
               // index into this VALID-ONLY boundary list (mirrors the server's pendingSegmentActions).
               int[] boundaryChunkIndex = new int[boundaryCount];
               for (int b = 0; b < boundaryCount; b++) {
                  boundaryChunkIndex[b] = buf.readVarInt();
                  buf.readUtf(); // actionName — resolved server-side from pendingSegmentActions; not needed client-side
               }

               CompletableFuture.runAsync(
                     () -> playChunksSequentially(clientId, token, speed, voiceIds, botUuid, chunks, boundaryChunkIndex));
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
               // Bodylang gesture playback tuning (append-only — read in the same order the server wrote).
               int markerPauseMs = buf.readVarInt();
               boolean gaplessPrefetch = buf.readBoolean();
               PlayerEngineClientConfigCache.applyFromSync(dedicated, payerMode, ownerOffline, hbId,
                     markerPauseMs, gaplessPrefetch);
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

   // Bodylang marker pause beat (ms) and gapless-prefetch toggle are server runtime config
   // (Player2ServerRuntimeConfig#bodylangMarkerPauseMs / #bodylangGaplessPrefetch), synced to the client
   // via SYNC_SERVER_PLAYER2 and read from PlayerEngineClientConfigCache at the pause site below.

   /**
    * Sequential per-chunk TTS playback on a single {@link CompletableFuture} worker thread (Workstream 3).
    *
    * <p>Per-request constants ({@code clientId}, resolved {@code tokenToUse}, {@code speed},
    * {@code voiceIds}) are hoisted out of the loop — only the chunk text varies. For chunk {@code k}:
    * synthesize+play it synchronously via {@link AudioUtils#streamAudio} (empty chunk = a leading marker,
    * skip synthesis); then, if a boundary follows chunk {@code k}, emit {@code segment_done(botUuid,
    * segIndex)} so the server fires that boundary's gesture. A short pause beat is inserted at each marker
    * by default (suppressed when gapless prefetch is on). After the final chunk, emit
    * {@code message_done(botUuid, degraded)}.
    *
    * <p>The client sends {@code segment_done}/{@code message_done} UNCONDITIONALLY — it has no knowledge
    * of who the prompter is; "exactly once" authority is enforced server-side by the prompter identity
    * check. On a chunk failure: log, STILL emit that boundary's {@code segment_done} (so the gesture
    * fires), skip to {@code k+1}, and set {@code degraded=true} for the trailing {@code message_done}.
    * A single chunk failure never aborts the loop.
    */
   private static void playChunksSequentially(String clientId, String token, double speed, String[] voiceIds,
         String botUuid, String[] chunks, int[] boundaryChunkIndex) {
      // Resolve the token ONCE for the whole message (per-request constant), as the legacy single-shot
      // handler did — not per chunk.
      String tokenToUse;
      try {
         tokenToUse = token == null || token.isBlank()
               ? Player2HTTPUtils.awaitToken(Minecraft.getInstance().player, clientId)
               : token;
      } catch (Exception e) {
         LOGGER.warn("Client: TTS token resolution failed for clientId={}: {}", clientId, e.getMessage());
         // No audio can play; still signal end-of-message (degraded) so the server clears the cooldown
         // and reports the partial outcome. Boundaries are driven by the server fallback timer.
         sendTtsMessageDone(botUuid, true);
         return;
      }

      boolean degraded = false;
      // GAPLESS PLAYBACK (always): synthesize chunk k+1 on a background thread WHILE chunk k is still
      // playing, so the instant chunk k's audio ends, chunk k+1's audio is already in memory and plays
      // immediately — no synthesis/network stall and no pause beat (matching the chat message, which shows
      // no pause). This is unconditional: there is no config gate, so no persisted setting can reintroduce
      // an inter-chunk gap. The gesture's segment_done fires at the boundary and the animation runs
      // concurrently with the next chunk's speech.
      final String tokenFinal = tokenToUse;
      CompletableFuture<byte[]> nextAudio = chunks.length > 0
            ? fetchChunkAsync(clientId, tokenFinal, chunks[0], speed, voiceIds)
            : null;
      for (int k = 0; k < chunks.length; k++) {
         byte[] audio = null;
         try {
            audio = nextAudio != null ? nextAudio.get() : null;
         } catch (Exception e) {
            // Synthesis failed: skip this chunk's audio, but still fire its boundary gesture below and
            // flag the trailing message_done degraded. Never abort the loop.
            degraded = true;
            LOGGER.warn("Client: TTS chunk {} fetch failed for clientId={}: {}", k, clientId, e.getMessage());
         }
         // Prefetch chunk k+1 NOW (before playing k) so it synthesizes during k's playback — gapless.
         if (k + 1 < chunks.length) {
            nextAudio = fetchChunkAsync(clientId, tokenFinal, chunks[k + 1], speed, voiceIds);
         } else {
            nextAudio = null;
         }
         if (audio != null && audio.length > 0) {
            try {
               AudioUtils.playAudioBytes(audio); // synchronous — returns when the clip finishes
            } catch (Exception e) {
               degraded = true;
               LOGGER.warn("Client: TTS chunk {} playback failed for clientId={}: {}", k, clientId, e.getMessage());
            }
         }
         // If any boundaries follow chunk k, emit segment_done for each in order — fires the gesture(s).
         // No pause: the next chunk (already prefetched) plays immediately; the gesture runs concurrently.
         for (int b = 0; b < boundaryChunkIndex.length; b++) {
            if (boundaryChunkIndex[b] == k) {
               sendTtsSegmentDone(botUuid, b);
            }
         }
      }

      // End-of-message: the sole client-driven cooldown clear. degraded reflects any chunk failure.
      sendTtsMessageDone(botUuid, degraded);
   }

   /**
    * Asynchronously synthesize one chunk's audio bytes so it can be prefetched during the previous
    * chunk's playback (gapless). An empty chunk (a leading marker) resolves immediately to an empty
    * array. Returns {@code null} bytes on synthesis failure (handled by the caller as a skipped chunk).
    */
   private static CompletableFuture<byte[]> fetchChunkAsync(String clientId, String token, String text,
         double speed, String[] voiceIds) {
      if (text == null || text.isEmpty()) {
         return CompletableFuture.completedFuture(new byte[0]);
      }
      return CompletableFuture.supplyAsync(() -> AudioUtils.fetchAudioBytes(clientId, token, text, speed, voiceIds));
   }

   /**
    * Emit the per-boundary {@code segment_done} signal. Sent unconditionally; the server honors it only
    * from the bot's prompter client. Payload: {@code writeUtf(botUuid); writeVarInt(segIndex)}.
    */
   private static void sendTtsSegmentDone(String botUuid, int segIndex) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.getConnection() == null || mc.player == null) {
         return;
      }
      mc.execute(() -> {
         if (mc.getConnection() == null || mc.player == null) {
            return;
         }
         RegistryFriendlyByteBuf segBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.player.registryAccess());
         segBuf.writeUtf(botUuid);
         segBuf.writeVarInt(segIndex);
         mc.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
               PlayerEngine.TTS_SEGMENT_DONE_PACKET_ID, segBuf));
      });
   }

   /**
    * Emit the end-of-message {@code message_done} signal (replaces the retired {@code tts_playback_done}).
    * Sent unconditionally; the server honors it only from the bot's prompter client. Payload:
    * {@code writeUtf(botUuid); writeBoolean(degraded)} — {@code degraded=false} on a clean run.
    */
   private static void sendTtsMessageDone(String botUuid, boolean degraded) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.getConnection() == null || mc.player == null) {
         return;
      }
      mc.execute(() -> {
         if (mc.getConnection() == null || mc.player == null) {
            return;
         }
         RegistryFriendlyByteBuf doneBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.player.registryAccess());
         doneBuf.writeUtf(botUuid);
         doneBuf.writeBoolean(degraded);
         mc.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
               PlayerEngine.TTS_MESSAGE_DONE_PACKET_ID, doneBuf));
      });
   }

   private static void sendProxyResponse(String requestId, boolean success, String payloadText) {
      Minecraft client = Minecraft.getInstance();
      client.execute(() -> {
         if (client.player == null || client.getConnection() == null) {
            return;
         }
         byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
         RegistryFriendlyByteBuf responseBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
               client.player.registryAccess());
         responseBuf.writeUtf(requestId);
         responseBuf.writeBoolean(success);
         responseBuf.writeByteArray(payload);
         client.getConnection().send(NetworkManager.toPacket(NetworkManager.Side.C2S,
               PlayerEngine.CLIENT_PLAYER2_PROXY_RESPONSE_PACKET_ID, responseBuf));
      });
   }
}
