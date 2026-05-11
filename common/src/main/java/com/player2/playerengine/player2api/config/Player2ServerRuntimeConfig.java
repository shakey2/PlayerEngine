package com.player2.playerengine.player2api.config;

/**
 * Persisted server-side settings for Player2 API routing (see DESIGN.md §6).
 */
public class Player2ServerRuntimeConfig {
    private Player2PayerMode payerMode = Player2PayerMode.PROMPTER_PAYS;
    private boolean dedicatedClientProxy = false;
    private boolean ownerOfflineServerContinuation = false;
    /** When true, automatons only receive chat that opens with their character name or short name. */
    private boolean callByNameChat = true;
    /** Client id used for optional client-side heartbeat when enabled (e.g. NPC game id). */
    private String heartbeatClientId = "player2-ai-npc-minecraft";

    public Player2PayerMode getPayerMode() {
        return payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public void setPayerMode(Player2PayerMode payerMode) {
        this.payerMode = payerMode == null ? Player2PayerMode.PROMPTER_PAYS : payerMode;
    }

    public boolean isDedicatedClientProxy() {
        return dedicatedClientProxy;
    }

    public void setDedicatedClientProxy(boolean dedicatedClientProxy) {
        this.dedicatedClientProxy = dedicatedClientProxy;
    }

    public boolean isOwnerOfflineServerContinuation() {
        return ownerOfflineServerContinuation;
    }

    public void setOwnerOfflineServerContinuation(boolean ownerOfflineServerContinuation) {
        this.ownerOfflineServerContinuation = ownerOfflineServerContinuation;
    }

    public boolean isCallByNameChat() {
        return callByNameChat;
    }

    public void setCallByNameChat(boolean callByNameChat) {
        this.callByNameChat = callByNameChat;
    }

    public String getHeartbeatClientId() {
        return heartbeatClientId == null || heartbeatClientId.isBlank()
                ? "player2-ai-npc-minecraft"
                : heartbeatClientId;
    }

    public void setHeartbeatClientId(String heartbeatClientId) {
        this.heartbeatClientId = heartbeatClientId;
    }
}
