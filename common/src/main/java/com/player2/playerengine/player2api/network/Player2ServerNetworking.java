package com.player2.playerengine.player2api.network;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class Player2ServerNetworking {
    public static final ResourceLocation SYNC_SERVER_PLAYER2 = new ResourceLocation(PlayerEngine.MOD_ID,
            "sync_server_player2");

    private Player2ServerNetworking() {
    }

    public static void sendConfigSync(ServerPlayer player) {
        var cfg = Player2ServerConfigHolder.get();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(cfg.isDedicatedClientProxy());
        buf.writeUtf(cfg.getPayerMode().name());
        buf.writeBoolean(cfg.isOwnerOfflineServerContinuation());
        buf.writeUtf(cfg.getHeartbeatClientId());
        // Bodylang gesture playback tuning (append-only — never reorder existing fields).
        buf.writeVarInt(cfg.getBodylangMarkerPauseMs());
        buf.writeBoolean(cfg.isBodylangGaplessPrefetch());
        player.connection.send(NetworkManager.toPacket(NetworkManager.Side.S2C, SYNC_SERVER_PLAYER2, buf));
    }
}
