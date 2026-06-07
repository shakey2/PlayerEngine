package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves which budget thresholds apply for a billing context (A4 + B4.5).
 * Integrated singleplayer always uses {@code server_player2.json}.
 */
public final class BudgetThresholdsResolver {

    private BudgetThresholdsResolver() {}

    public static boolean useServerConfigForBudget(MinecraftServer server) {
        return server != null && !server.isDedicatedServer();
    }

    /**
     * Single source of truth for which budget store applies to a server context.
     * True  => the shared server config (server_player2.json): singleplayer/LAN host,
     *          or any OWNER_PAYS_ALL server (one shared payer), or a null server.
     * False => a per-player file (player-budget.json, keyed by payer UUID): only on a
     *          dedicated server in PROMPTER_PAYS, where each player is their own payer.
     * Enforcement (resolve), and the /playerengine and /player2npc budget commands MUST
     * all route through this method so status and enforcement can never disagree.
     */
    public static boolean usesServerBudgetStore(MinecraftServer server) {
        return server == null
                || useServerConfigForBudget(server)
                || Player2ServerConfigHolder.get().getPayerMode() != Player2PayerMode.PROMPTER_PAYS;
    }

    public static BudgetThresholds resolve(
            MinecraftServer server, Player2PayerResolution.ApiBillingContext billing) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (usesServerBudgetStore(server)) {
            return cfg;
        }
        ServerPlayer payer = billing != null ? billing.onlinePayer() : null;
        if (payer != null) {
            return PlayerBudgetConfigHolder.load(server, payer.getUUID());
        }
        return cfg;
    }
}
