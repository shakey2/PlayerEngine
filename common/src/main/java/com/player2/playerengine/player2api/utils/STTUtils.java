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
import net.minecraft.network.RegistryFriendlyByteBuf;
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
                return;
            }
            if (pushToTalkActive) {
                return;
            }
            pushToTalkActive = true;
            isListening = true;
            stopPendingAfterStart = false;
            sttThread.execute(STTUtils::startSession);
        } else {
            if (!pushToTalkActive) {
                return;
            }
            pushToTalkActive = false;
            isListening = false;
            sttThread.execute(STTUtils::stopSession);
        }
    }

    /** Force-stop an active session and clear state (consent revoke / disable). */
    public static void abortSession() {
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
            resetPushToTalkState();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || clientId == null || clientId.isEmpty()) {
            LOGGER.warn("STT: cannot start session (player or clientId missing)");
            resetPushToTalkState();
            return;
        }
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("timeout", STT_TIMEOUT_SECONDS);
            Player2HTTPUtils.sendRequest(mc.player, clientId, "/v1/stt/start", true, requestBody);
            sessionStarted = true;
            LOGGER.info("STT: session started via /v1/stt/start");
            if (stopPendingAfterStart) {
                stopPendingAfterStart = false;
                stopSessionInternal(true);
            }
        } catch (Exception e) {
            LOGGER.error("STT: failed to start session", e);
            sessionStarted = false;
            resetPushToTalkState();
        }
    }

    private static void stopSession() {
        if (!sessionStarted) {
            stopPendingAfterStart = true;
            return;
        }
        stopSessionInternal(true);
    }

    private static void stopSessionInternal(boolean deliverTranscript) {
        sessionStarted = false;
        stopPendingAfterStart = false;

        if (!ChatclefConfigPersistantState.canUseStt()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || clientId == null || clientId.isEmpty()) {
            LOGGER.warn("STT: cannot stop session (player or clientId missing)");
            return;
        }
        try {
            Map<String, JsonElement> responseMap = Player2HTTPUtils.sendRequest(mc.player, clientId, "/v1/stt/stop", true, null);
            if (!deliverTranscript) {
                return;
            }
            if (responseMap.containsKey("text")) {
                String text = responseMap.get("text").getAsString();
                if (text != null && !text.isBlank()) {
                    onSTTMessageGenerated(text);
                }
            } else {
                LOGGER.warn("STT: /v1/stt/stop response missing 'text' key");
            }
        } catch (Exception e) {
            LOGGER.error("STT: failed to stop session", e);
        }
    }

    private static void resetPushToTalkState() {
        pushToTalkActive = false;
        isListening = false;
    }

    private static void onSTTMessageGenerated(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mc.player.registryAccess());
        buf.writeUtf(message);
        mc.getConnection().send(
                NetworkManager.toPacket(
                        NetworkManager.Side.C2S,
                        ResourceLocation.fromNamespaceAndPath("playerengine", "user_message"),
                        buf));
    }
}
