package com.player2.playerengine.player2api;

import java.util.Optional;

/**
 * Immutable result from {@link ModelTierRouter#resolve}.
 *
 * <p>If {@link #isOnDevice()} is true, the caller should not make a Player2 HTTP call at all.
 * Otherwise, {@link #profileBaseUrlOverride()} is empty for the Default profile or contains
 * the named-profile base URL to set as a thread-local override before the request.
 */
public record RoutingDecision(
        boolean isOnDevice,
        Optional<String> profileBaseUrlOverride) {

    /** On-device — no HTTP call required. */
    public static RoutingDecision onDevice() {
        return new RoutingDecision(true, Optional.empty());
    }

    /** Default Player2 profile (no URL override needed). */
    public static RoutingDecision defaultProfile() {
        return new RoutingDecision(false, Optional.empty());
    }

    /** Named patron profile using the given base URL. */
    public static RoutingDecision namedProfile(String baseUrl) {
        return new RoutingDecision(false, Optional.of(baseUrl));
    }
}
