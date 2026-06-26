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

    /**
     * Matches {@link com.player2.playerengine.player2api.Player2APIService#startSTT()}.
     * Capped at the {@code /v1/stt/start} schema maximum ({@code StartSpeechToTextRequest.timeout}
     * max 60s, min 3s); push-to-talk holds are far shorter than this, so the timeout is only the
     * safety ceiling for an abandoned session. A value above 60 would be a spec-invalid request a
     * conformant app may reject with 4xx.
     */
    private static final int STT_TIMEOUT_SECONDS = 60;

    /**
     * Minimum capture window (ms) the local Player2 app is given between {@code /v1/stt/start}
     * completing and {@code /v1/stt/stop} being issued. Without this floor, a quick push-to-talk
     * tap (press and release within the blocking {@code /v1/stt/start} latency) opens the recording
     * window late and immediately closes it, so the app captures near-silence and returns an empty
     * transcript. The window the user actually held (press -> release wall time) does not begin in
     * the app until {@code /v1/stt/start} returns; this floor guarantees the app records for at least
     * this long even on an instant tap. Conservative value: long enough to cover a short word, short
     * enough not to feel laggy. Disk/console reasoning only; never model-facing.
     */
    private static final long MIN_CAPTURE_WINDOW_MS = 600L;

    public static volatile boolean isListening = false;
    private static volatile boolean pushToTalkActive = false;
    private static volatile boolean sessionStarted = false;
    private static volatile boolean stopPendingAfterStart = false;

    // --- Bounded per-cycle diagnostic timing (millis/booleans/counts only — never audio/transcript text).
    // Lets a tester's log conclusively show the press -> start-complete -> release -> stop timeline and
    // whether the dead-window mechanism is firing, without reproducing locally.
    private static final java.util.concurrent.atomic.AtomicLong cycleCounter = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long currentCycleId = 0L;
    private static volatile long pressNanos = 0L;
    private static volatile long startRequestNanos = 0L;
    private static volatile long startCompleteNanos = 0L;
    private static volatile long releaseNanos = 0L;
    private static volatile boolean ttsActiveAtPress = false;

    private static long msSince(long fromNanos) {
        if (fromNanos == 0L) {
            return -1L;
        }
        return (System.nanoTime() - fromNanos) / 1_000_000L;
    }

    private static long deltaMs(long fromNanos, long toNanos) {
        if (fromNanos == 0L || toNanos == 0L) {
            return -1L;
        }
        return (toNanos - fromNanos) / 1_000_000L;
    }

    /**
     * Bounded one-line timeline for the current push-to-talk cycle: numeric latencies + booleans only,
     * never audio/transcript text. Lets a tester log prove press -> start-complete -> release -> stop
     * timing and whether the dead-window mechanism fired.
     */
    private static String cycleTimingSummary() {
        long heldMs = deltaMs(pressNanos, releaseNanos);
        long startLatencyMs = deltaMs(startRequestNanos, startCompleteNanos);
        long pressToStartCompleteMs = deltaMs(pressNanos, startCompleteNanos);
        long captureWindowMs = deltaMs(startCompleteNanos, System.nanoTime());
        return String.format(
                "[cycle=%d heldMs=%d startLatencyMs=%d pressToStartCompleteMs=%d captureWindowSinceStartMs=%d ttsActiveAtPress=%b]",
                currentCycleId, heldMs, startLatencyMs, pressToStartCompleteMs, captureWindowMs, ttsActiveAtPress);
    }

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
            currentCycleId = cycleCounter.incrementAndGet();
            pressNanos = System.nanoTime();
            startRequestNanos = 0L;
            startCompleteNanos = 0L;
            releaseNanos = 0L;
            ttsActiveAtPress = AudioUtils.isPlaybackActive();
            LOGGER.info("STT: push-to-talk pressed (cycle={}, clientId={}, ttsActiveAtPress={})",
                    currentCycleId, clientId, ttsActiveAtPress);
            sttThread.execute(STTUtils::startSession);
        } else {
            if (!pushToTalkActive) {
                LOGGER.debug("STT: push-to-talk release ignored (no active session)");
                return;
            }
            pushToTalkActive = false;
            isListening = false;
            releaseNanos = System.nanoTime();
            LOGGER.info("STT: push-to-talk released (cycle={}, heldMs={})", currentCycleId, msSince(pressNanos));
            sttThread.execute(STTUtils::stopSession);
        }
    }

    /** Force-stop an active session and clear state (consent revoke / disable). */
    public static void abortSession() {
        LOGGER.info("STT: abortSession requested");
        pushToTalkActive = false;
        isListening = false;
        stopPendingAfterStart = false;
        // Clear the aborted cycle's diagnostic timing so a late stop log (or a new cycle's first
        // log before setIsListening overwrites them) does not print stale press/release/TTS values.
        // Diagnostic-only fields; no operational effect.
        pressNanos = 0L;
        startRequestNanos = 0L;
        startCompleteNanos = 0L;
        releaseNanos = 0L;
        ttsActiveAtPress = false;
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
            startRequestNanos = System.nanoTime();
            Map<String, JsonElement> startResponse =
                    Player2HTTPUtils.sendRequest(mc.player, clientId, "/v1/stt/start", true, requestBody);
            startCompleteNanos = System.nanoTime();
            sessionStarted = true;
            LOGGER.info("STT: session started via /v1/stt/start (cycle={}, startLatencyMs={}, startBodyEmpty={}, clientId={})",
                    currentCycleId, msSince(startRequestNanos), startResponse == null || startResponse.isEmpty(), clientId);
            if (stopPendingAfterStart) {
                // Quick-tap path: the user already released before /v1/stt/start returned, so the app's
                // recording window only just opened. Guarantee a minimum capture window before stopping
                // so an instant tap does not yield an empty transcript (the regression's core mechanism).
                stopPendingAfterStart = false;
                long windowSoFarMs = msSince(startCompleteNanos);
                long remainingMs = MIN_CAPTURE_WINDOW_MS - Math.max(0L, windowSoFarMs);
                LOGGER.info("STT: stop was queued while start was in flight (cycle={}); enforcing min capture window (remainingMs={})",
                        currentCycleId, Math.max(0L, remainingMs));
                if (remainingMs > 0L) {
                    try {
                        Thread.sleep(remainingMs);
                    } catch (InterruptedException ie) {
                        // Interrupted only on shutdownNow(); abortSession() has already queued the
                        // session stop, so do NOT fire a second /v1/stt/stop on this interrupted,
                        // shutting-down thread. Re-set the flag and bail.
                        Thread.currentThread().interrupt();
                        LOGGER.info("STT: min-capture-window sleep interrupted (cycle={}); skipping stop (shutdown)", currentCycleId);
                        return;
                    }
                }
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
            LOGGER.info("STT: stop requested before session started (cycle={}); will stop after /v1/stt/start completes",
                    currentCycleId);
            return;
        }
        // Session opened before release. If the recording window since /v1/stt/start completed is still
        // shorter than the floor (short hold), pad it so the app captures a usable clip rather than a
        // sliver of audio that decodes to an empty transcript.
        long windowSoFarMs = msSince(startCompleteNanos);
        long remainingMs = MIN_CAPTURE_WINDOW_MS - Math.max(0L, windowSoFarMs);
        LOGGER.info("STT: stop after started (cycle={}, windowSinceStartMs={}, padMs={})",
                currentCycleId, windowSoFarMs, Math.max(0L, remainingMs));
        if (remainingMs > 0L) {
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException ie) {
                // Interrupted only on shutdownNow(); abortSession() has already queued the session
                // stop, so do NOT fire a second /v1/stt/stop on this interrupted, shutting-down
                // thread. Re-set the flag and bail.
                Thread.currentThread().interrupt();
                LOGGER.info("STT: stop-pad sleep interrupted (cycle={}); skipping stop (shutdown)", currentCycleId);
                return;
            }
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
            LOGGER.warn("STT: /v1/stt/stop response has no usable transcript (cycle={}, keys={}); timeline {}",
                    currentCycleId, SttLogging.jsonResponseKeys(responseMap), cycleTimingSummary());
            return;
        }
        if (text.isBlank()) {
            LOGGER.warn("STT: /v1/stt/stop returned empty transcript (cycle={}, keys={}); timeline {}",
                    currentCycleId, SttLogging.jsonResponseKeys(responseMap), cycleTimingSummary());
            return;
        }
        LOGGER.info("STT: transcript received (cycle={}, len={}, preview=\"{}\"); timeline {}",
                currentCycleId, text.length(), SttLogging.messagePreview(text), cycleTimingSummary());
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
