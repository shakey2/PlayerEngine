package com.player2.playerengine.player2api.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.client.Minecraft;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.player2api.ChatclefConfigPersistantState;

public class STTUtils {
    public static final Logger LOGGER = LogManager.getLogger();

    private static final ExecutorService sttThread = Executors.newSingleThreadExecutor();

    // state
    public static volatile boolean isListening = false;
    private static volatile boolean pushToTalkActive = false;
    private static volatile boolean tokenRequestedForSession = false;
    private static volatile boolean recordingThreadRunning = false;
    private static TargetDataLine line;
    public static String clientId;
    private static ByteArrayOutputStream buffer;

    // audio format
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true,
            false);
    private static final int BYTES_PER_FRAME = AUDIO_FORMAT.getFrameSize();

    // queue for outgoing wav audio blobs
    private static final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>();

    // current token used for sending; guarded by sttThread tasks (single-threaded)
    private static volatile String currentToken = null;

    // HTTP client shared
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /**
     * Mic-captured WAV upload uses the cloud STT endpoint. The local Player2 app API exposes
     * {@code /v1/stt/start} and {@code /v1/stt/stop} (session-based) but not {@code /v1/stt/audio}.
     */
    private static final String WEB_API_URL = "https://api.player2.game";
    private static final String STT_AUDIO_URI = WEB_API_URL + "/v1/stt/audio?encoding=linear16&sample_rate=16000";

    private static final class SttPostResult {
        final Optional<String> transcript;
        final int statusCode;

        SttPostResult(Optional<String> transcript, int statusCode) {
            this.transcript = transcript;
            this.statusCode = statusCode;
        }
    }

    public static void update() {
    }

    /**
     * Called on mod init. No-op: mic line is opened lazily on first use after consent.
     */
    public static void onInitialize() {
        LOGGER.info("STTUtils initialized (mic line will open on first consented use).");
    }

    /**
     * Opens the TargetDataLine if not already open. Must only be called when canUseStt() is true.
     */
    private static synchronized void ensureLineOpen() {
        if (line != null) return;
        try {
            line = AudioSystem.getTargetDataLine(AUDIO_FORMAT);
            line.open(AUDIO_FORMAT);
            LOGGER.info("STT: audio line opened.");
        } catch (Exception e) {
            LOGGER.error("STT: failed to open audio line", e);
            line = null;
        }
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
            ensureLineOpen();
            requestTokenOnce();
            startRecording();
        } else {
            if (!pushToTalkActive) {
                return;
            }
            pushToTalkActive = false;
            isListening = false;
            // Recording thread will finish, enqueue WAV, and upload using currentToken.
        }
    }

    /** Force-stop capture and clear pending uploads (consent revoke / disable). */
    public static void abortSession() {
        pushToTalkActive = false;
        isListening = false;
        tokenRequestedForSession = false;
        sttThread.execute(() -> {
            audioQueue.clear();
            currentToken = null;
        });
    }

    private static void requestTokenOnce() {
        if (tokenRequestedForSession) {
            return;
        }
        tokenRequestedForSession = true;
        initToken();
    }

    private static synchronized void startRecording() {
        if (recordingThreadRunning) {
            return;
        }
        if (line == null) {
            LOGGER.error("STT: audio line not available; cannot record.");
            return;
        }
        recordingThreadRunning = true;
        sttThread.execute(() -> {
            line.start();
            buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (isListening && line != null) {
                int read = line.read(chunk, 0, chunk.length);
                if (read > 0) {
                    buffer.write(chunk, 0, read);
                }
            }

            try {
                line.stop();
            } catch (Exception e) {
                LOGGER.debug("STT: failed to stop line");
            }

            try {
                byte[] pcm = buffer.toByteArray();
                if (pcm.length == 0) {
                    LOGGER.info("STT: no audio captured");
                    return;
                }

                ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
                AudioInputStream ais = new AudioInputStream(bais, AUDIO_FORMAT, pcm.length / BYTES_PER_FRAME);

                ByteArrayOutputStream wavOutBaos = new ByteArrayOutputStream();
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOutBaos);
                byte[] wavBytes = wavOutBaos.toByteArray();

                LOGGER.info("STT: recorded {} bytes PCM -> {} bytes WAV", pcm.length, wavBytes.length);
                sendToApi(wavBytes, null);
            } catch (Exception e) {
                LOGGER.error("STT: failed creating wav bytes", e);
            } finally {
                recordingThreadRunning = false;
                tokenRequestedForSession = false;
            }
        });
    }

    public static void shutdown() {
        isListening = false;
        pushToTalkActive = false;
        try {
            if (line != null) {
                line.stop();
                line.close();
            }
        } catch (Exception ignored) {
        }
        sttThread.shutdownNow();
        LOGGER.info("STTUtils shutdown complete.");
    }

    /**
     * Called when a token arrives from the server. Ignored unless canUseStt().
     */
    public static void connect(String token) {
        if (!ChatclefConfigPersistantState.canUseStt()) {
            return;
        }
        if (token == null || token.isEmpty()) {
            LOGGER.warn("STT: connect called with empty token; ignoring.");
            return;
        }
        sttThread.execute(() -> {
            currentToken = token;
            flushQueue();
        });
    }

    /**
     * Enqueue or send WAV bytes to API. Silently dropped if canUseStt() is false.
     */
    public static void sendToApi(byte[] wavAudio, String token) {
        if (!ChatclefConfigPersistantState.canUseStt()) {
            return;
        }
        if (wavAudio == null || wavAudio.length == 0) {
            LOGGER.warn("STT: sendToApi called with empty wavAudio");
            return;
        }

        if (token != null) {
            sttThread.execute(() -> {
                currentToken = token;
                flushQueue();
                deliverTranscript(doHttpPost(wavAudio, currentToken));
            });
            return;
        }

        boolean offered = audioQueue.offer(wavAudio);
        if (!offered) {
            LOGGER.error("STT: failed to enqueue wavAudio");
        } else {
            LOGGER.info("STT: enqueued wavAudio (queueSize={})", audioQueue.size());
            if (currentToken != null) {
                flushQueue();
            }
        }
    }

    private static void flushQueue() {
        if (currentToken == null) {
            return;
        }
        byte[] item;
        while ((item = audioQueue.poll()) != null) {
            SttPostResult result = doHttpPost(item, currentToken);
            if (result.transcript.isPresent()) {
                deliverTranscript(result);
            } else if (isNonRetryableStatus(result.statusCode)) {
                LOGGER.error("STT: upload failed with HTTP {} (not retrying)", result.statusCode);
                audioQueue.clear();
                break;
            } else {
                audioQueue.offer(item);
                break;
            }
        }
    }

    private static boolean isNonRetryableStatus(int code) {
        return code == 404 || code == 401 || code == 403 || code == 400 || code == 422;
    }

    private static void deliverTranscript(SttPostResult result) {
        if (result == null || result.transcript.isEmpty()) {
            return;
        }
        String msg = result.transcript.get();
        if (!msg.isBlank()) {
            onSTTMessageGenerated(msg);
        }
    }

    private static SttPostResult doHttpPost(byte[] wavBytes, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(STT_AUDIO_URI))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(wavBytes));

            if (token != null && !token.isEmpty()) {
                b.header("Authorization", "Bearer " + token);
            }
            if (clientId != null && !clientId.isEmpty()) {
                b.header("player2-game-key", clientId);
            }

            HttpRequest req = b.build();
            LOGGER.info("STT: sending {} bytes WAV to {}", wavBytes.length, STT_AUDIO_URI);

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            String body = resp.body();

            LOGGER.info("STT API response: {} body-size={}", code, body == null ? 0 : body.length());

            if (code >= 200 && code < 300 && body != null) {
                String transcript = extractTranscript(body);
                return new SttPostResult(Optional.ofNullable(transcript), code);
            }

            LOGGER.warn("STT: non-2xx response from API: {}", code);
            return new SttPostResult(Optional.empty(), code);

        } catch (HttpTimeoutException e) {
            LOGGER.warn("STT: timeout sending to API: {}", e.getMessage());
            return new SttPostResult(Optional.empty(), 0);
        } catch (Exception e) {
            LOGGER.error("STT: exception sending to API", e);
            return new SttPostResult(Optional.empty(), 0);
        }
    }

    private static void initToken() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(clientId);
        mc.getConnection().send(
                NetworkManager.toPacket(
                        NetworkManager.Side.C2S,
                        new ResourceLocation("playerengine", "request_stt"),
                        buf));
    }

    private static void onSTTMessageGenerated(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(message);
        mc.getConnection().send(
                NetworkManager.toPacket(
                        NetworkManager.Side.C2S,
                        new ResourceLocation("playerengine", "user_message"),
                        buf));
    }

    private static String extractTranscript(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("transcript")) {
                return obj.get("transcript").getAsString();
            }
            if (obj.has("text")) {
                return obj.get("text").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
