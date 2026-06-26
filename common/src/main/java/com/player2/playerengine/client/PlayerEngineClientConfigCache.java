package com.player2.playerengine.client;

import com.player2.playerengine.player2api.config.Player2PayerMode;

/**
 * Mirrors {@link com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig} after S2C sync.
 */
public final class PlayerEngineClientConfigCache {
    private static volatile boolean dedicatedClientProxy = false;
    private static volatile Player2PayerMode payerMode = Player2PayerMode.PROMPTER_PAYS;
    private static volatile boolean ownerOfflineServerContinuation = false;
    private static volatile String heartbeatClientId = "player2-ai-npc-minecraft";
    // Bodylang TTS-timed gesture playback tuning (mirrors Player2ServerRuntimeConfig); synced via
    // SYNC_SERVER_PLAYER2. Defaults match the server config defaults until the first sync arrives.
    private static volatile int bodylangMarkerPauseMs = 250;
    private static volatile boolean bodylangGaplessPrefetch = true;

    private PlayerEngineClientConfigCache() {
    }

    public static void applyFromSync(boolean dedicated, String payerModeName, boolean ownerOffline, String hbClientId,
            int markerPauseMs, boolean gaplessPrefetch) {
        dedicatedClientProxy = dedicated;
        ownerOfflineServerContinuation = ownerOffline;
        heartbeatClientId = hbClientId == null || hbClientId.isBlank() ? "player2-ai-npc-minecraft" : hbClientId;
        bodylangMarkerPauseMs = markerPauseMs < 0 ? 0 : Math.min(markerPauseMs, 2000); // clamp [0,2000], mirrors server setter
        bodylangGaplessPrefetch = gaplessPrefetch;
        try {
            payerMode = Player2PayerMode.valueOf(payerModeName);
        } catch (Exception e) {
            payerMode = Player2PayerMode.PROMPTER_PAYS;
        }
    }

    public static boolean shouldSendPlayerHeartbeat() {
        return dedicatedClientProxy || payerMode == Player2PayerMode.PROMPTER_PAYS;
    }

    public static String getHeartbeatClientId() {
        return heartbeatClientId;
    }

    public static int getBodylangMarkerPauseMs() {
        return bodylangMarkerPauseMs;
    }

    public static boolean isBodylangGaplessPrefetch() {
        return bodylangGaplessPrefetch;
    }
}
