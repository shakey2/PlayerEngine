package com.player2.playerengine.retrieval.learning;

import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.BudgetThresholdsResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Preflight for B5 deep-check: disabled flag, A4 soft/hard, then deep-check window cap.
 */
public final class DeepCheckBudgetGate {

    private static final Logger LOGGER = LogManager.getLogger(DeepCheckBudgetGate.class);

    public enum SkipReason {
        NONE,
        DEEP_CHECK_DISABLED,
        BUDGET_SOFT_SKIP,
        BUDGET_HARD_SKIP,
        DEEP_CHECK_CAP_SKIP
    }

    private DeepCheckBudgetGate() {}

    public static SkipReason preflight(Player2PayerResolution.ApiBillingContext billing) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isEnableDeepCheckRephrase()) {
            return SkipReason.DEEP_CHECK_DISABLED;
        }
        if (billing == null || billing.billingKey() == null) {
            return SkipReason.BUDGET_HARD_SKIP;
        }

        BudgetThresholds thresholds = thresholdsFor(billing);
        BudgetTracker.BudgetCheckResult callPeek =
                BudgetTracker.peek(billing.billingKey(), thresholds);
        JoulesCache.JoulesSnapshot joulesSnap = JoulesCache.get(billing.billingKey()).orElse(null);
        BudgetTracker.BudgetCheckResult joulesPeek =
                JoulesCache.checkJoulesThreshold(joulesSnap, thresholds);
        BudgetTracker.BudgetCheckResult combined =
                BudgetTracker.stricter(callPeek, joulesPeek);

        if (combined == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            LOGGER.debug("[B5] budget_hard_skip billingKey={}", billing.billingKey());
            return SkipReason.BUDGET_HARD_SKIP;
        }
        if (combined == BudgetTracker.BudgetCheckResult.SOFT_LIMIT) {
            LOGGER.debug("[B5] budget_soft_skip billingKey={}", billing.billingKey());
            return SkipReason.BUDGET_SOFT_SKIP;
        }

        DeepCheckBudgetThresholds deepThresholds = DeepCheckBudgetThresholds.fromConfig(cfg);
        if (DeepCheckBudgetTracker.peekCap(billing.billingKey(), deepThresholds)
                == DeepCheckBudgetResult.CAP_REACHED) {
            return SkipReason.DEEP_CHECK_CAP_SKIP;
        }
        return SkipReason.NONE;
    }

    /** Reserves one deep-check slot (call after A4 preflight passes). */
    public static boolean reserveDeepCheckSlot(Player2PayerResolution.ApiBillingContext billing) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (billing == null || billing.billingKey() == null) {
            return false;
        }
        return DeepCheckBudgetTracker.checkAndRecord(
                billing.billingKey(), DeepCheckBudgetThresholds.fromConfig(cfg))
                == DeepCheckBudgetResult.OK;
    }

    /** Peek A4 only (does not record deep-check cap). */
    public static SkipReason peekA4Only(Player2PayerResolution.ApiBillingContext billing) {
        if (billing == null || billing.billingKey() == null) {
            return SkipReason.BUDGET_HARD_SKIP;
        }
        BudgetThresholds thresholds = thresholdsFor(billing);
        BudgetTracker.BudgetCheckResult callPeek =
                BudgetTracker.peek(billing.billingKey(), thresholds);
        JoulesCache.JoulesSnapshot joulesSnap = JoulesCache.get(billing.billingKey()).orElse(null);
        BudgetTracker.BudgetCheckResult joulesPeek =
                JoulesCache.checkJoulesThreshold(joulesSnap, thresholds);
        BudgetTracker.BudgetCheckResult combined =
                BudgetTracker.stricter(callPeek, joulesPeek);
        if (combined == BudgetTracker.BudgetCheckResult.HARD_LIMIT) {
            return SkipReason.BUDGET_HARD_SKIP;
        }
        if (combined == BudgetTracker.BudgetCheckResult.SOFT_LIMIT) {
            return SkipReason.BUDGET_SOFT_SKIP;
        }
        return SkipReason.NONE;
    }

    private static BudgetThresholds thresholdsFor(Player2PayerResolution.ApiBillingContext billing) {
        ServerPlayer payer = billing != null ? billing.onlinePayer() : null;
        MinecraftServer server = payer != null ? payer.getServer() : null;
        return BudgetThresholdsResolver.resolve(server, billing);
    }
}
