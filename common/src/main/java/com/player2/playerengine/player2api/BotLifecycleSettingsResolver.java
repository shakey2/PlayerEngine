package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Central resolver for a bot's effective {@code (autoRespawn, botPermadeath)} lifecycle settings for a
 * {@code (server, ownerUuid)} pair. Enforcement ({@code die()} / {@code denial()}) and the
 * {@code /player2npc} status/show output must all read through this resolver so they can never disagree.
 *
 * <p><b>Routing.</b> Singleplayer / LAN host / any integrated (non-dedicated) server uses the shared
 * server config ({@code server_player2.json}); a dedicated server uses the server config only when
 * {@code serverOverridesPlayerConfig} is ON, otherwise the per-player config.
 *
 * <p><b>Borrowed detection only.</b> Only the {@code !server.isDedicatedServer()} SP-vs-dedicated
 * detection is mirrored from {@code BudgetThresholdsResolver}. The {@code serverOverridesPlayerConfig}
 * precedence is net-new here, and the budget resolver's {@code payerMode}
 * ({@code PROMPTER_PAYS}/{@code OWNER_PAYS_ALL}) clause is irrelevant and deliberately NOT copied.
 *
 * <p><b>Model A (hardcore default).</b> {@code botPermadeath} is a nullable tri-state end-to-end. The
 * resolver computes {@code chosen != null ? chosen : world.isHardcore()} — hardcore is the fallback for
 * an UNSET ({@code null}) value only, never OR-ed with a stored value. An explicit {@code false} is
 * honored even in hardcore (the OFF toggle must work); the resolver must NOT compute
 * {@code stored || hardcore} (Model B, rejected).
 */
public final class BotLifecycleSettingsResolver {

    private BotLifecycleSettingsResolver() {}

    /**
     * True  => use the server config (singleplayer / LAN host / integrated, OR dedicated + override ON).
     * False => use the per-player config (dedicated + override OFF).
     */
    public static boolean usesServerConfig(MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            return true; // SP / LAN host / integrated -> shared server/local config
        }
        return Player2ServerConfigHolder.get().isServerOverridesPlayerConfig(); // dedicated: server wins iff override on
    }

    /**
     * Model A: hardcore is the fallback ONLY when {@code chosen} is unset ({@code null}). An explicit
     * {@code false} is honored even in hardcore. We never compute {@code (stored || hardcore)}.
     */
    private static boolean resolvePermadeath(Boolean chosen, boolean hardcore) {
        return chosen != null ? chosen : hardcore;
    }

    /**
     * Resolve the effective lifecycle settings for an owner. The per-player values are supplied by the
     * caller (Player2NPC reads them from {@code OwnerUserSettingsStorage}); they are only consulted when
     * routing selects the per-player config.
     *
     * @param perPlayerAutoRespawn    the owner's per-player auto-respawn value (default true)
     * @param perPlayerBotPermadeath  the owner's per-player permadeath value, nullable tri-state
     *                                ({@code null} = unset -> hardcore fallback)
     */
    public static BotLifecycleSettings resolve(MinecraftServer server, UUID ownerUuid,
                                               boolean perPlayerAutoRespawn, Boolean perPlayerBotPermadeath) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        boolean hardcore = server != null && server.isHardcore();
        if (usesServerConfig(server)) {
            return new BotLifecycleSettings(
                    cfg.isServerAutoRespawn(),
                    resolvePermadeath(cfg.getServerBotPermadeath(), hardcore));
        }
        return new BotLifecycleSettings(
                perPlayerAutoRespawn,
                resolvePermadeath(perPlayerBotPermadeath, hardcore));
    }
}
