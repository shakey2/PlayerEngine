package com.player2.playerengine.player2api;

/**
 * Classifies an AI call for model-tier routing (B3).
 *
 * <p>Used by {@link ModelTierRouter} to decide which Player2 profile base URL applies.
 * Callers must pass the correct class; {@link #DECISION} is the safe default for NPC chat.
 */
public enum AiTaskClass {
    /** On-device retrieval only — never reaches a Player2 HTTP call. */
    RETRIEVAL,

    /** Rephrase / re-rank pass (Default profile; cheapest). */
    RERANKING,

    /** History summarization (Default profile; cheapest). */
    SUMMARIZATION,

    /** Planner / goal-decomposition step (named profile when available). */
    PLANNING,

    /** NPC chat completion / command pick — routes the same as PLANNING. */
    DECISION
}
