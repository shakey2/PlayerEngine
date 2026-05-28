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

    public static BudgetThresholds resolve(
            MinecraftServer server, Player2PayerResolution.ApiBillingContext billing) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (useServerConfigForBudget(server) || cfg.getPayerMode() != Player2PayerMode.PROMPTER_PAYS) {
            return cfg;
        }
        ServerPlayer payer = billing != null ? billing.onlinePayer() : null;
        if (payer != null) {
            return PlayerBudgetConfigHolder.load(server, payer.getUUID());
        }
        return cfg;
    }
}
