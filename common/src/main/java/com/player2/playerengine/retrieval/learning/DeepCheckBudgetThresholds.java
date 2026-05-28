package com.player2.playerengine.retrieval.learning;

import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;

public record DeepCheckBudgetThresholds(int callsPerWindow, int windowMinutes) {

    public static DeepCheckBudgetThresholds fromConfig(Player2ServerRuntimeConfig config) {
        return new DeepCheckBudgetThresholds(
                config.getDeepCheckCallsPerWindowClamped(),
                config.getDeepCheckWindowMinutesClamped());
    }
}
