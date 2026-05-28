package com.player2.playerengine.retrieval.learning;

import java.util.Optional;

public record DeepCheckAttemptResult(
        Optional<DeepCheckResponse> response,
        String skipReason
) {
    public static DeepCheckAttemptResult ok(DeepCheckResponse response) {
        return new DeepCheckAttemptResult(Optional.of(response), "");
    }

    public static DeepCheckAttemptResult skipped(String reason) {
        return new DeepCheckAttemptResult(Optional.empty(), reason);
    }
}
