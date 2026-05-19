package com.player2.playerengine.player2api;

/**
 * Result of {@link ModelTierRouter#resolveWithRule}, including which decision rule matched.
 */
public record RoutingResult(RoutingDecision decision, int matchedRule) {}
