package com.player2.playerengine.memory.budget;

/**
 * Result of {@link MemoryGate#preflight}. {@code allowed} is the single fail-closed verdict for
 * the entire memory LLM pipeline; {@code reason} carries the bounded, enumerated cause (never a
 * stack trace, log line, or unbounded string — see DESIGN.md §3 data-egress rules).
 *
 * <p>The {@code reason} is intentionally a closed enum so the both-audiences degradation surfacing
 * (player chat line + distilled model feedback token) can be templated from it without ever
 * concatenating unbounded text.
 */
public record MemoryGateDecision(boolean allowed, SkipReason reason) {

    /** Enumerated, bounded cause for a memory pipeline skip. NONE = allowed. */
    public enum SkipReason {
        /** Allowed — no skip. */
        NONE,
        /** {@code enableGraphRagMemory} master switch is off. */
        MEMORY_DISABLED,
        /** {@code dedicatedClientProxy} mode: server has no outbound path for a background memory job. */
        CLIENT_PROXY_UNSUPPORTED,
        /** Owner billing context / billingKey is null (e.g. PROMPTER_PAYS + owner offline, no stored token). */
        NO_BILLING,
        /** Owner Joules snapshot is null or non-patron (fail-closed). */
        NOT_PATRON,
        /** A4 hard budget / hard Joules threshold reached. */
        BUDGET_HARD_SKIP,
        /** A4 soft budget / soft Joules threshold reached. */
        BUDGET_SOFT_SKIP,
        /** Memory-pipeline windowed cap ({@code memoryCallsPerWindow}) reached. */
        EXTRACTION_CAP_SKIP
    }

    public static MemoryGateDecision allow() {
        return new MemoryGateDecision(true, SkipReason.NONE);
    }

    public static MemoryGateDecision skip(SkipReason reason) {
        return new MemoryGateDecision(false, reason);
    }

    /**
     * Bounded, templated chat line for the PLAYER (DESIGN.md §3 — never the raw enum/stack/unbounded
     * text). Exact owner-facing copy is a product decision (plan open item 5); these are safe defaults.
     */
    public String playerMessage() {
        return switch (reason) {
            case NOT_PATRON, NO_BILLING ->
                    "Long-term memory is a Patron feature.";
            case BUDGET_HARD_SKIP, BUDGET_SOFT_SKIP, EXTRACTION_CAP_SKIP ->
                    "Long-term memory is paused for now (budget reached).";
            case MEMORY_DISABLED, CLIENT_PROXY_UNSUPPORTED, NONE ->
                    "";
        };
    }

    /**
     * Distilled, bounded feedback TOKEN for the MODEL's command-completion feedback (DESIGN.md §3 —
     * a short curated phrase, never the raw enum text, a stack, or unbounded content).
     */
    public String modelFeedbackToken() {
        return switch (reason) {
            case NOT_PATRON -> "memory_unavailable:not_patron";
            case NO_BILLING -> "memory_unavailable:no_billing";
            case BUDGET_HARD_SKIP -> "memory_unavailable:budget_hard";
            case BUDGET_SOFT_SKIP -> "memory_unavailable:budget_soft";
            case EXTRACTION_CAP_SKIP -> "memory_unavailable:rate_capped";
            case MEMORY_DISABLED -> "memory_unavailable:disabled";
            case CLIENT_PROXY_UNSUPPORTED -> "memory_unavailable:proxy_mode";
            case NONE -> "";
        };
    }
}
