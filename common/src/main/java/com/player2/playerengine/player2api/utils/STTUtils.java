package com.player2.playerengine.player2api.utils;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.player2api.ChatclefConfigPersistantState;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class STTUtils {
    public static final Logger LOGGER = LogManager.getLogger();

    private static final ExecutorService sttThread = Executors.newSingleThreadExecutor();

    /** Matches {@link com.player2.playerengine.player2api.Player2APIService#startSTT()}. */
    private static final int STT_TIMEOUT_SECONDS = 180;

    public static volatile boolean isListening = false;
    private static volatile boolean pushToTalkActive = false;
    private static volatile boolean sessionStarted = false;
    private static volatile boolean stopPendingAfterStart = false;

    public static String clientId;

    public static void update() {
    }

    public static void onInitialize() {
        LOGGER.info("STTUtils initialized (session-based /v1/stt/start and /v1/stt/stop).");
    }

    /**
     * Push-to-talk: call with {@code true} once when the key is pressed, {@code false} once when released.
     * Must not be called every tick while the key is held.
     */
    public static void setIsListening(boolean v, String clientId) {
        STTUtils.clientId = clientId;
        if (v) {
            if (!ChatclefConfigPersistantState.canUseStt()) {
                LOGGER.warn("STT: push-to-talk ignored (consent not granted or STT disabled in settings)");
                return;
            }
            if (pushToTalkActive) {
                LOGGER.debug("STT: push-to-talk start ignored (session already active)");
                return;
            }
            pushToTalkActive = true;
            isListening = true;
            stopPendingAfterStart = false;
            LOGGER.info("STT: push-to-talk pressed (clientId={})", clientId);
            sttThread.execute(STTUtils::startSession);
        } else {
            if (!pushToTalkActive) {
                LOGGER.debug("STT: push-to-talk release ignored (no active session)");
                return;
            }
            pushToTalkActive = false;
            isListening = false;
            LOGGER.info("STT: push-to-talk released");
            sttThread.execute(STTUtils::stopSession);
        }
    }

    /** Force-stop an active session and clear state (consent revoke / disable). */
    public static void abortSession() {
        LOGGER.info("STT: abortSession requested");
        pushToTalkActive = false;
        isListening = false;
        stopPendingAfterStart = false;
        sttThread.execute(() -> {
            if (sessionStarted) {
                stopSessionInternal(false);
            }
            sessionStarted = false;
        });
    }

    public static void shutdown() {
        abortSession();
        sttThread.shutdownNow();
        LOGGER.info("STTUtils shutdown complete.");
    }

    private static void startSession() {
        if (!ChatclefConfigPersistantState.canUseStt()) {
            LOGGER.warn("STT: cannot start session (consent revoked or STT disabled before start completed)");
            resetPushToTalkState();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || clientId == null || clientId.isEmpty()) {
            LOGGER.warn("STT: cannot start session (player={}, clientId={})",
                    mc.player != null, clientId == null ? "<null>" : (clientId.isEmpty() ? "<empty>" : clientId));
            resetPushToTalkState();
            return;
        }
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("timeout", STT_TIMEOUT_SECONDS);
            Player2HTTPUtils.sendRequest(mc.player, clientId, "/v1/stt/start", true, requestBody);
            sessionStarted = true;
            LOGGER.info("STT: session started via /v1/stt/start (clientId={})", clientId);
            if (stopPendingAfterStart) {
                LOGGER.info("STT: stop was queued while start was in flight; stopping now");
                stopPendingAfterStart = false;
                stopSessionInternal(true);
            }
        } catch (Exception e) {
            LOGGER.error("STT: failed to start session via /v1/stt/start", e);
            sessionStarted = false;
            resetPushToTalkState();
        }
    }

    private static void stopSession() {
        if (!sessionStarted) {
            stopPendingAfterStart = true;
            LOGGER.info("STT: stop requested before session started; will stop after /v1/stt/start completes");
            return;
        }
        stopSessionInternal(true);
    }

    private static void stopSessionInternal(boolean deliverTranscript) {
        sessionStarted = false;
        stopPendingAfterStart = false;

        if (!ChatclefConfigPersistantState.canUseStt()) {
            LOGGER.warn("STT: stop skipped (consent revoked or STT disabled, deliverTranscript={})", deliverTranscript);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || clientId == null || clientId.isEmpty()) {
            LOGGER.warn("STT: cannot stop session (player={}, clientId={}, deliverTranscript={})",
                    mc.player != null, clientId != null && !clientId.isEmpty(), deliverTranscript);
            return;
        }
        try {
            Map<String, JsonElement> responseMap = Player2HTTPUtils.sendRequest(mc.player, clientId, "/v1/stt/stop", true, null);
            if (!deliverTranscript) {
                LOGGER.info("STT: /v1/stt/stop completed (transcript not delivered, keys={})",
                        SttLogging.jsonResponseKeys(responseMap));
                return;
            }
            deliverTranscriptFromStopResponse(responseMap);
        } catch (Exception e) {
            LOGGER.error("STT: failed to stop session via /v1/stt/stop", e);
        }
    }

    private static void deliverTranscriptFromStopResponse(Map<String, JsonElement> responseMap) {
        String text = extractTranscriptText(responseMap);
        if (text == null) {
            LOGGER.warn("STT: /v1/stt/stop response has no usable transcript (keys={})",
                    SttLogging.jsonResponseKeys(responseMap));
            return;
        }
        if (text.isBlank()) {
            LOGGER.warn("STT: /v1/stt/stop returned empty transcript (keys={})",
                    SttLogging.jsonResponseKeys(responseMap));
            return;
        }
        LOGGER.info("STT: transcript received (len={}, preview=\"{}\")", text.length(), SttLogging.messagePreview(text));
        onSTTMessageGenerated(text);
    }

    /**
     * Accepts {@code text} or legacy {@code transcript} keys from the stop response.
     */
    private static String extractTranscriptText(Map<String, JsonElement> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return null;
        }
        if (responseMap.containsKey("text")) {
            JsonElement el = responseMap.get("text");
            if (el != null && el.isJsonPrimitive()) {
                return el.getAsString();
            }
            LOGGER.warn("STT: 'text' field is not a string (type={})", el == null ? "null" : el.getClass().getSimpleName());
        }
        if (responseMap.containsKey("transcript")) {
            JsonElement el = responseMap.get("transcript");
            if (el != null && el.isJsonPrimitive()) {
                LOGGER.info("STT: using legacy 'transcript' key from stop response");
                return el.getAsString();
            }
            LOGGER.warn("STT: 'transcript' field is not a string (type={})", el == null ? "null" : el.getClass().getSimpleName());
        }
        return null;
    }

    private static void resetPushToTalkState() {
        pushToTalkActive = false;
        isListening = false;
    }

    private static void onSTTMessageGenerated(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.error("STT: cannot send user_message packet (local player is null); transcript lost: \"{}\"",
                    SttLogging.messagePreview(message));
            return;
        }
        if (mc.getConnection() == null) {
            LOGGER.error("STT: cannot send user_message packet (not connected to server); transcript lost: \"{}\"",
                    SttLogging.messagePreview(message));
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(message);
        mc.getConnection().send(
                NetworkManager.toPacket(
                        NetworkManager.Side.C2S,
                        new ResourceLocation("playerengine", "user_message"),
                        buf));
        LOGGER.info("STT: sent user_message packet to server (len={}, preview=\"{}\")",
                message.length(), SttLogging.messagePreview(message));
    }
}
