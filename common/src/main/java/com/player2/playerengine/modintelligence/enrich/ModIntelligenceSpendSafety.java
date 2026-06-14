package com.player2.playerengine.modintelligence.enrich;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.enrich.ModelBlacklist.ModelBlacklistSnapshot;
import com.player2.playerengine.player2api.BudgetThresholdsResolver;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * B4.5 enrichment spend-safety gates with optional bypass flags and in-game payer notifications.
 */
public final class ModIntelligenceSpendSafety {

    private static final Logger LOGGER = PlayerEngine.LOGGER;

    public enum DeferReason {
        BLACKLIST_INVALID,
        LARGE_QUEUE_NO_BUDGET
    }

    private ModIntelligenceSpendSafety() {}

    /** Bypass flags in {@code server_player2.json} apply only on integrated singleplayer, not dedicated servers. */
    public static boolean bypassFlagsApply(MinecraftServer server) {
        return BudgetThresholdsResolver.useServerConfigForBudget(server);
    }

    public static boolean isModelBlacklistBypassed(MinecraftServer server) {
        return bypassFlagsApply(server)
                && Player2ServerConfigHolder.get().isModIntelligenceBypassModelBlacklist();
    }

    public static boolean isLargeQueueBudgetGateBypassed(MinecraftServer server) {
        return bypassFlagsApply(server)
                && Player2ServerConfigHolder.get().isModIntelligenceBypassLargeQueueBudgetGate();
    }

    public static Optional<DeferReason> preflight(
            MinecraftServer server, int queueSize, ModelBlacklistSnapshot blacklist) {
        return preflight(server, queueSize, blacklist, false);
    }

    /**
     * @param explicitLimitOverride true when this batch was started by an explicit
     *        {@code /playerengine capability enrich <limit>} invocation — informed consent that
     *        skips the large-queue budget gate (the blacklist check still applies).
     */
    public static Optional<DeferReason> preflight(
            MinecraftServer server, int queueSize, ModelBlacklistSnapshot blacklist,
            boolean explicitLimitOverride) {

        if (!blacklist.valid() && !isModelBlacklistBypassed(server)) {
            LOGGER.warn("ModIntelligence enrichment: stopping batch (model_blacklist_invalid) — {}",
                    blacklist.error());
            return Optional.of(DeferReason.BLACKLIST_INVALID);
        }

        if (queueSize > 20 && !isLargeQueueBudgetGateBypassed(server)) {
            BudgetThresholds thresholds = ModIntelligenceEnrichmentClient.getBudgetThresholds(server);
            if (thresholds == null
                    || ModIntelligenceEnrichmentClient.noBudgetLimitsConfigured(thresholds)) {
                if (explicitLimitOverride) {
                    LOGGER.info(
                            "ModIntelligence enrichment: large-queue budget gate skipped — explicit command limit given (queue={})",
                            queueSize);
                } else {
                    LOGGER.warn(
                            "ModIntelligence enrichment: deferred - queue size is {} (> 20) and no budget limit has been set on spending",
                            queueSize);
                    return Optional.of(DeferReason.LARGE_QUEUE_NO_BUDGET);
                }
            }
        }

        return Optional.empty();
    }

    public static Component messageFor(DeferReason reason, int queueSize, ModelBlacklistSnapshot blacklist) {
        return switch (reason) {
            case BLACKLIST_INVALID -> Component.literal(
                    "[ModIntelligence] Enrichment blocked: model_blacklist.json is invalid ("
                            + (blacklist.error() != null ? blacklist.error() : "unknown error")
                            + "). Fix playerengine/data/mod_intelligence/model_blacklist.json (singleplayer: "
                            + "modIntelligenceBypassModelBlacklist in server_player2.json).");
            case LARGE_QUEUE_NO_BUDGET -> Component.literal(
                    "[ModIntelligence] Enrichment deferred: queue has " + queueSize
                            + " items (>20) but no spending limits are configured. Set at least one of "
                            + "soft/hard call or Joules limits (/playerengine player2 budget …; on dedicated "
                            + "servers use per-player player-budget.json). Singleplayer only: "
                            + "modIntelligenceBypassLargeQueueBudgetGate in server_player2.json.");
        };
    }

    public static void notifyPlayer(MinecraftServer server, Component message) {
        ServerPlayer player = resolveNotifyTarget(server);
        if (player != null) {
            player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
        }
    }

    public static void notifyBatchAbort(MinecraftServer server, String abortCode, String detail) {
        Component msg = switch (abortCode) {
            case "model_blacklisted" -> Component.literal(
                    "[ModIntelligence] Enrichment stopped: model is blacklisted"
                            + (detail != null && !detail.isBlank() ? " (" + detail + ")" : "")
                            + ". Edit playerengine/data/mod_intelligence/model_blacklist.json (singleplayer: "
                            + "modIntelligenceBypassModelBlacklist in server_player2.json).");
            case "model_missing" -> Component.literal(
                    "[ModIntelligence] Enrichment stopped: completion response had no model field "
                            + "(required when blacklist is non-empty).");
            case "model_blacklist_invalid" -> Component.literal(
                    "[ModIntelligence] Enrichment stopped: model blacklist invalid"
                            + (detail != null && !detail.isBlank() ? " (" + detail + ")" : "") + ".");
            default -> Component.literal("[ModIntelligence] Enrichment stopped: " + abortCode);
        };
        LOGGER.warn("ModIntelligence enrichment: stopping batch ({})", abortCode);
        notifyPlayer(server, msg);
    }

    private static ServerPlayer resolveNotifyTarget(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        Player2PayerResolution.ApiBillingContext billing =
                Player2PayerResolution.resolveForServer(server, cfg.getHeartbeatClientId());
        if (billing != null && billing.onlinePayer() != null) {
            return billing.onlinePayer();
        }
        if (BudgetThresholdsResolver.useServerConfigForBudget(server)) {
            var players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                return players.get(0);
            }
        }
        return null;
    }
}
