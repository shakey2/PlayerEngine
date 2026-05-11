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

    private PlayerEngineClientConfigCache() {
    }

    public static void applyFromSync(boolean dedicated, String payerModeName, boolean ownerOffline, String hbClientId) {
        dedicatedClientProxy = dedicated;
        ownerOfflineServerContinuation = ownerOffline;
        heartbeatClientId = hbClientId == null || hbClientId.isBlank() ? "player2-ai-npc-minecraft" : hbClientId;
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
}
