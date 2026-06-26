package com.player2.playerengine.memory.retrieval;

import com.player2.playerengine.memory.budget.MemoryBudgetResult;
import com.player2.playerengine.memory.budget.MemoryExtractionBudgetGate;
import com.player2.playerengine.player2api.Player2PayerResolution;

/**
 * Read-only budget <b>signal</b> helper for the retrieval hot path (Phase D, W5).
 *
 * <p>W5 never fires an LLM completion and never reserves a budget slot. This helper exists solely so
 * the retrieval pass can <em>signal</em> W6 whether a reflection completion could even run under the
 * current window cap — purely a peek of W7's {@link MemoryExtractionBudgetGate#peekCap}. It is a
 * one-line wrapper that makes the "this is read-only, it must not reserve" contract explicit and
 * greppable at the W5 call site.
 *
 * <p><b>Invariant:</b> this class calls only {@code peek*} methods; it MUST NOT call
 * {@code reserveSlot} or any path that records against the window. The actual A4 / patron gate and
 * the slot reservation live in W7 ({@code MemoryGate} / {@code MemoryExtractionBudgetGate}).
 *
 * <p>Zero-LLM. No Minecraft / loader / I/O dependency.
 */
public final class GraphRetrievalBudgetGate {

    private GraphRetrievalBudgetGate() {}

    /**
     * Read-only signal: could a memory completion run under the window cap right now? Never reserves.
     * Used by W5 to tell W6 "reflection is budget-eligible" — W7 still performs the real gate before
     * any completion fires.
     *
     * @param ownerBilling the owner's resolved billing context (may be null → not eligible)
     * @return {@code true} iff a slot is currently available (peek only)
     */
    public static boolean reflectionBudgetAvailable(Player2PayerResolution.ApiBillingContext ownerBilling) {
        if (ownerBilling == null) return false;
        return MemoryExtractionBudgetGate.peekCap(ownerBilling) == MemoryBudgetResult.OK;
    }
}
