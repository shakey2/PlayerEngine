package com.player2.playerengine.player2api;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.auth.TokenStorage;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Resolves which Player2 account bills an API call for a controller.
 */
public final class Player2PayerResolution {

    public record ApiBillingContext(ServerPlayer onlinePayer, String storedTokenUsername) {
        /** Use server-stored token for HTTP when non-null and {@code onlinePayer} is null. */
        public boolean useStoredToken() {
            return onlinePayer == null && storedTokenUsername != null && !storedTokenUsername.isEmpty();
        }
    }

    private Player2PayerResolution() {
    }

    public static ApiBillingContext resolve(PlayerEngineController mod, String chainInitiatorUsername,
            String clientId) {
        Player2ServerConfigHolder.get(); // ensure loaded
        var cfg = Player2ServerConfigHolder.get();
        MinecraftServer server = mod.getPlayer() != null ? mod.getPlayer().level().getServer() : null;

        Player owner = mod.getOwner();
        String ownerName = owner != null ? owner.getName().getString() : "";

        if (cfg.getPayerMode() == Player2PayerMode.OWNER_PAYS_ALL) {
            ServerPlayer ownerSp = owner instanceof ServerPlayer o ? o : null;
            if (ownerSp != null && server != null && server.getPlayerList().getPlayer(ownerSp.getUUID()) != null) {
                return new ApiBillingContext(ownerSp, null);
            }
            if (cfg.isOwnerOfflineServerContinuation() && !cfg.isDedicatedClientProxy()
                    && owner != null
                    && !TokenStorage.getToken(ownerName, clientId).isEmpty()) {
                return new ApiBillingContext(null, ownerName);
            }
            return new ApiBillingContext(null, null);
        }

        String billName = chainInitiatorUsername != null && !chainInitiatorUsername.isBlank()
                ? chainInitiatorUsername
                : ownerName;
        ServerPlayer bill = findByName(server, billName);
        return new ApiBillingContext(bill, null);
    }

    private static ServerPlayer findByName(MinecraftServer server, String name) {
        if (server == null || name == null) {
            return null;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getName().getString().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
