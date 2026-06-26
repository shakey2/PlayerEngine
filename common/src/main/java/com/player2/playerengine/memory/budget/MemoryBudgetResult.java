package com.player2.playerengine.memory.budget;

/**
 * Outcome of a memory-pipeline windowed-cap check (clone of
 * {@code retrieval.learning.DeepCheckBudgetResult}; the memory pipeline keeps its own
 * per-billing-key ceiling, independent of chat/DeepCheck).
 */
public enum MemoryBudgetResult {
    OK,
    CAP_REACHED
}
