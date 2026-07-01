package com.player2.playerengine.memory.patron;

/**
 * Compile-time toggle preserving the pre-W9 patron-wall behavior of the Phase D memory gate as a
 * MANUAL-ONLY restore fallback (never an automatic fallback).
 *
 * <p><strong>What this is.</strong> As of Phase D W9 (see
 * {@code masterplan/phase-d-w8-embeddings-build-plan.md} §B.2/§B.3), the GraphRAG memory feature is
 * the PRIMARY, free path and is available to NON-PATRON users: real {@code /v1/embeddings} +
 * retrieval carry no patron check, and extraction spend is governed by the memory window cap rather
 * than a patron wall. The old patron gate (a non-patron / empty-Joules-cache owner returned
 * {@code NOT_PATRON} before ever reaching the budget check) is NOT deleted — it is preserved as the
 * patron arm of a live {@code if (MEMORY_PATRON_FALLBACK) { ... } else { ... }} branch in
 * {@code MemoryGate.preflight}.
 *
 * <p><strong>Why a compiled toggle and not a comment or a runtime config.</strong> BOTH arms of the
 * {@code if (MEMORY_PATRON_FALLBACK)} branch stay COMPILED (the JIT dead-code-eliminates the unused
 * arm on a {@code static final} constant), which means:
 * <ul>
 *   <li><b>Cannot silently auto-activate</b> — the toggle is a {@code static final false}; no config,
 *       no error, no missing endpoint can flip it at runtime. Only a human edit + rebuild can.</li>
 *   <li><b>Cannot bit-rot</b> — because the preserved patron arm is real, compiled code, any refactor
 *       of {@code MemoryGate.preflight} (renamed params, changed patron-predicate API, reordered
 *       checks) that breaks the patron arm fails {@code build.ps1}. A sentinel-commented block would
 *       have silently referenced stale symbols across such a refactor; this cannot.</li>
 *   <li><b>Cleanly restorable</b> — no uncommenting, no stale-symbol reconciliation.</li>
 * </ul>
 *
 * <p><strong>MANUAL RESTORE RECIPE (do NOT auto-enable).</strong> If Player2 embeddings develop a
 * NON-short-term problem and the pre-W9 patron-walling behavior must be restored:
 * <ol>
 *   <li>Set {@link #MEMORY_PATRON_FALLBACK} = {@code true} below.</li>
 *   <li>Rebuild via {@code script-tools/build.ps1} (no other code change is needed — the patron arm
 *       of {@code MemoryGate.preflight} is already compiled).</li>
 *   <li>The gate then returns {@code SkipReason.NOT_PATRON} for a non-patron or null-snapshot owner,
 *       exactly as before W9 (memory walled off from non-patrons).</li>
 *   <li>To find every touchpoint: {@code grep MEMORY_PATRON_FALLBACK} surfaces this constant and the
 *       single branch in {@code MemoryGate.preflight}.</li>
 * </ol>
 * Keep this constant {@code false} in shipped builds.
 */
public final class MemoryPatronFallback {

    private MemoryPatronFallback() {}

    /**
     * MANUAL RESTORE ONLY — DO NOT AUTO-ENABLE.
     *
     * <p>{@code false} (shipped): memory is the primary free path, available to non-patrons
     * (§B.2). {@code true}: restores the pre-W9 patron wall (non-patron / null-snapshot owner →
     * {@code NOT_PATRON}). Both states compile because both arms of the guarded branch in
     * {@code MemoryGate.preflight} are live code. Flip + rebuild to restore; see the class Javadoc.
     */
    public static final boolean MEMORY_PATRON_FALLBACK = false;
}
