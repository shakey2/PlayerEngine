package com.player2.playerengine.player2api.manager;


import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.Player2APIService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Submits TTS broadcasts on a dedicated worker thread.
 *
 * <p>Previously this class also held a server-wide {@code TTSLocked} flag that gated every bot's
 * LLM dispatch while any one of them was speaking. That has moved to per-bot pacing on
 * {@link com.player2.playerengine.player2api.AgentConversationData#markSpeakingFor(String)} so
 * one bot's audio playback no longer freezes conversation for the rest of the server.
 */
public class TTSManager {
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Not final: lifecycle-managed via {@link #shutdownAndReset()} so an integrated-server stop
     * followed by a restart in the same JVM gets a fresh executor (mirrors the per-bucket
     * LLMCompleter reset behaviour).
     */
    private static volatile ExecutorService ttsThread = Executors.newSingleThreadExecutor();

    public static ExecutorService getExecutor(){
        return ttsThread;
    }

    /**
     * Shut down the current executor and replace it with a fresh one so the next session in the
     * same JVM (e.g. integrated server restart) is not left with a terminated thread.
     */
    public static synchronized void shutdownAndReset() {
        ExecutorService prev = ttsThread;
        if (prev != null && !prev.isShutdown()) {
            com.player2.playerengine.util.ExecutorShutdown.shutdownNowAwait("TTSManager", prev);
        }
        ttsThread = Executors.newSingleThreadExecutor();
    }

    public static void TTS(String message, Character character, Player2APIService player2apiService, UUID botUuid) {
        TTS(message, java.util.List.of(message == null ? "" : message), java.util.List.of(), character, player2apiService, botUuid);
    }

    /**
     * TTS dispatch with TTS-timed gesture chunks/boundaries (Workstream 2). The chunk list and the
     * VALID-only boundary list are read off the bot's {@code AgentConversationData} at the
     * {@code AgentSideEffects.onEntityMessage} call site and forwarded to the
     * {@link Player2APIService#textToSpeech} overload that writes them onto the {@code stream_tts}
     * payload. Submitted on the dedicated TTS thread exactly as the single-string path.
     */
    public static void TTS(String message, java.util.List<String> chunks,
            java.util.List<com.player2.playerengine.player2api.MarkerParser.SegmentBoundary> validBoundaries,
            Character character, Player2APIService player2apiService, UUID botUuid) {
        if (message == null) {
            return;
        }
        LOGGER.info("TTSManager.TTS submitting broadcast for msg.len={} chunks={} boundaries={}",
                message.length(), chunks == null ? 0 : chunks.size(),
                validBoundaries == null ? 0 : validBoundaries.size());
        ttsThread.submit(() -> {
            player2apiService.textToSpeech(message, chunks, validBoundaries, character, botUuid, (_unusedMap) -> {
                // Per-bot pacing is set at the AgentSideEffects.onEntityMessage call site so the
                // dispatcher can defer just the speaking bot until its audio is done playing.
            });
        });
    }
}
