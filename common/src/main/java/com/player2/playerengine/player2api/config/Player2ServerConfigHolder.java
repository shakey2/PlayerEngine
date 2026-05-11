package com.player2.playerengine.player2api.config;

import com.player2.playerengine.util.helpers.ConfigHelper;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Loads {@code config/playerengine/server_player2.json}. Thread-safe reads.
 */
public final class Player2ServerConfigHolder {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String CONFIG_PATH = "server_player2.json";

    private static volatile Player2ServerRuntimeConfig cached = defaultConfig();

    private Player2ServerConfigHolder() {
    }

    private static Player2ServerRuntimeConfig defaultConfig() {
        return new Player2ServerRuntimeConfig();
    }

    public static Player2ServerRuntimeConfig get() {
        return cached;
    }

    public static void load() {
        Player2ServerRuntimeConfig next = ConfigHelper.getConfig(CONFIG_PATH, Player2ServerConfigHolder::defaultConfig,
                Player2ServerRuntimeConfig.class);
        validateAndFix(next);
        cached = next;
        LOGGER.info("Player2 server config: payerMode={} dedicated={} ownerOfflineContinue={}",
                cached.getPayerMode(), cached.isDedicatedClientProxy(), cached.isOwnerOfflineServerContinuation());
    }

    public static void save() {
        validateAndFix(cached);
        ConfigHelper.saveConfig(CONFIG_PATH, cached);
    }

    public static void setAndSave(Player2ServerRuntimeConfig cfg) {
        validateAndFix(cfg);
        cached = cfg;
        save();
    }

    public static void validateAndFix(Player2ServerRuntimeConfig c) {
        if (c == null) {
            return;
        }
        if (c.isDedicatedClientProxy() && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: dedicatedClientProxy and ownerOfflineServerContinuation are mutually exclusive; disabling ownerOfflineServerContinuation.");
            c.setOwnerOfflineServerContinuation(false);
        }
        if (c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS && c.isOwnerOfflineServerContinuation()) {
            LOGGER.warn("Player2 config: ownerOfflineServerContinuation only applies to OWNER_PAYS_ALL; disabling.");
            c.setOwnerOfflineServerContinuation(false);
        }
    }

    /** When true, companion controller tick may send /v1/health from the server for owner-paid mode. */
    public static boolean shouldSendServerControllerHeartbeat(Player2ServerRuntimeConfig c,
            boolean ownerTokenPresent, boolean ownerClientOnline) {
        if (c.getPayerMode() != Player2PayerMode.OWNER_PAYS_ALL) {
            return false;
        }
        if (c.isDedicatedClientProxy()) {
            return false;
        }
        if (ownerClientOnline) {
            return true;
        }
        return c.isOwnerOfflineServerContinuation() && ownerTokenPresent;
    }

    /** True when each player's client should periodically send /v1/health for usage attribution. */
    public static boolean shouldClientSendPlayerHeartbeat(Player2ServerRuntimeConfig c) {
        return c.isDedicatedClientProxy() || c.getPayerMode() == Player2PayerMode.PROMPTER_PAYS;
    }

    public static boolean serverHasPlayer2ConnectivityGuess(MinecraftServer server) {
        return com.player2.playerengine.player2api.utils.LocalAPIDiscovery.getLocalApiUrl() != null
                || true;
    }
}
