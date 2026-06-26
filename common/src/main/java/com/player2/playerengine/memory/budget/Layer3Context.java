package com.player2.playerengine.memory.budget;

import com.player2.playerengine.player2api.Player2PayerResolution;

/**
 * Immutable carrier threaded from the W7 gate down into W4's layer-3 (LLM alias disambiguation)
 * resolution. It bundles the already-resolved OWNER billing context with the gate's verdict so
 * layer 3 never re-resolves billing or re-checks patron status independently — it simply honors
 * {@link #layer3Enabled()} (default {@code false}, fail-closed) and reuses {@link #ownerBilling()}
 * for the {@link MemoryLlmClient#complete} call.
 *
 * <p>{@code layer3Enabled} is {@code false} unless the gate produced an {@code allowed} decision
 * AND the resolution path explicitly opted layer 3 in (layer 3 is the only LLM step in entity
 * resolution; layers 1–2 are deterministic on-device and never gated). A default-constructed /
 * {@link #disabled} context never permits an LLM call.
 *
 * @param ownerBilling  the COMPANION OWNER's resolved billing context (same account the gate keyed
 *                      on; never the prompter's), or {@code null} when disabled
 * @param gateDecision  the {@link MemoryGate#preflight} verdict that produced this context, or
 *                      {@code null} when disabled
 * @param layer3Enabled whether layer-3 LLM disambiguation may fire (default {@code false})
 */
public record Layer3Context(
        Player2PayerResolution.ApiBillingContext ownerBilling,
        MemoryGateDecision gateDecision,
        boolean layer3Enabled) {

    /** Fail-closed default: no billing, no decision, layer 3 off. */
    public static Layer3Context disabled() {
        return new Layer3Context(null, null, false);
    }

    /**
     * Builds an enabled context ONLY when the gate decision is {@code allowed} and the owner
     * billing is usable; otherwise returns {@link #disabled()} (fail-closed).
     */
    public static Layer3Context fromGate(Player2PayerResolution.ApiBillingContext ownerBilling,
            MemoryGateDecision gateDecision) {
        boolean enabled = gateDecision != null
                && gateDecision.allowed()
                && ownerBilling != null
                && ownerBilling.billingKey() != null;
        if (!enabled) {
            return disabled();
        }
        return new Layer3Context(ownerBilling, gateDecision, true);
    }
}
