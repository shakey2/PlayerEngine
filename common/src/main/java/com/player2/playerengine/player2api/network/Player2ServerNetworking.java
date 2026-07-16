package com.player2.playerengine.player2api.network;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public final class Player2ServerNetworking {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final ResourceLocation SYNC_SERVER_PLAYER2 = new ResourceLocation(PlayerEngine.MOD_ID,
            "sync_server_player2");

    private Player2ServerNetworking() {
    }

    /** Immediate aggregate outcome; scheduled retries are intentionally reported as incomplete. */
    public record ConfigSyncResult(
            boolean playerSnapshotSucceeded,
            int targetCount,
            int deliveredCount,
            int retryScheduledCount,
            int failedCount) {

        public boolean fullySynchronized() {
            return playerSnapshotSucceeded
                    && deliveredCount == targetCount
                    && retryScheduledCount == 0
                    && failedCount == 0;
        }
    }

    public static void sendConfigSync(ServerPlayer player) {
        trySendConfigSync(player);
    }

    private static boolean trySendConfigSync(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            sendConfigSyncUnchecked(player);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to send the Player2 server-config sync to one player; continuing.", e);
            return false;
        }
    }

    private static void sendConfigSyncUnchecked(ServerPlayer player) {
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

    /** Re-syncs the routing fields cached by every connected client after a live server-config edit. */
    public static ConfigSyncResult sendConfigSyncToAll(MinecraftServer server) {
        if (server == null) {
            return new ConfigSyncResult(false, 0, 0, 0, 0);
        }
        List<ServerPlayer> players;
        try {
            players = List.copyOf(server.getPlayerList().getPlayers());
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to snapshot connected players for a Player2 server-config sync.", e);
            return new ConfigSyncResult(false, 0, 0, 0, 0);
        }
        int delivered = 0;
        int retryScheduled = 0;
        int failed = 0;
        for (ServerPlayer player : players) {
            if (trySendConfigSync(player)) {
                delivered++;
            } else {
                try {
                    server.tell(new TickTask(server.getTickCount() + 1, () -> {
                        trySendConfigSync(player);
                    }));
                    retryScheduled++;
                } catch (RuntimeException retryScheduleFailure) {
                    LOGGER.warn("Unable to schedule one Player2 server-config sync retry.", retryScheduleFailure);
                    failed++;
                }
            }
        }
        return new ConfigSyncResult(true, players.size(), delivered, retryScheduled, failed);
    }
}
