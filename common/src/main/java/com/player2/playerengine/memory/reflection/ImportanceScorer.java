package com.player2.playerengine.memory.reflection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.memory.budget.Layer3Context;
import com.player2.playerengine.memory.budget.MemoryGate;
import com.player2.playerengine.memory.budget.MemoryLlmClient;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2PayerResolution;
import net.minecraft.server.MinecraftServer;

/**
 * Write-time, patron-gated importance scorer (Phase D, W6).
 *
 * <p><b>Prefer folding importance into the W3 extraction JSON.</b> This standalone scorer is the
 * documented <em>fallback only</em>: it fires when the extraction JSON lacked a valid
 * {@code importance} after the clamp attempt (parse failure / missing / out-of-range). Each fallback
 * call is a real memory completion and is therefore <b>counted against {@code memoryCallsPerWindow}</b>
 * like any other — a parse failure costs {@code +1} call that batch. The happy path pays no extra
 * call.
 *
 * <p><b>Anchored rubric</b> (anti-clustering): {@code 1 = mundane}, {@code 5 = shared personal info},
 * {@code 8 = promise / betrayal / real gift}, {@code 10 = saved / endangered a life}; the prompt
 * explicitly instructs the model to <em>avoid clustering at 7–10</em>. The response schema is
 * {@code {"importance": <int>}}, clamped to {@code [1,10]}.
 *
 * <h3>Gating, routing, egress (release-blocker invariants)</h3>
 * <ul>
 *   <li><b>Patron gate.</b> {@link #scoreImportance} is a no-op (returns the
 *       {@code UNSCORED} sentinel) unless the supplied {@link Layer3Context} reports
 *       {@link Layer3Context#layer3Enabled()} — i.e. {@link MemoryGate#preflight} already returned
 *       {@code allowed} for the OWNER's billing context. A non-patron / null / owner-offline context
 *       never reaches the LLM call (fail-closed).</li>
 *   <li><b>Routing.</b> The call goes through {@link MemoryLlmClient#completeConversationToString}
 *       ({@code SUMMARIZATION} → Default/cheapest, never the patron's named profile) which also
 *       re-applies {@code LogEgressGuard.cappedMessage} to every history element.</li>
 *   <li><b>Egress.</b> Only a short, curated rubric prompt + the bounded candidate {@code content}
 *       (already write-capped) reach the model. No log/stack/Throwable/unbounded string is ever
 *       concatenated into a model-facing surface (DESIGN.md §3).</li>
 *   <li><b>Threading.</b> Intended to run on {@link MemoryLlmClient#MEMORY_EXECUTOR} (off-tick), like
 *       the rest of the W6 LLM path. Never call on the server tick.</li>
 * </ul>
 */
public final class ImportanceScorer {

    /** Returned when scoring did not (or could not) run — caller leaves the node unscored (0). */
    public static final int UNSCORED = 0;

    /** Rubric clamp bounds. */
    public static final int MIN_IMPORTANCE = 1;
    public static final int MAX_IMPORTANCE = 10;

    /**
     * Rubric version embedded in the prompt; bump (and {@code MemoryStore.setImportanceRubricVersion})
     * when the anchors change so re-scoring is possible. Playtest-tunable placeholder.
     */
    public static final int RUBRIC_VERSION = 1;

    private static final String SYSTEM_PROMPT =
            "You rate how emotionally or relationally significant a single remembered fact is to an "
                    + "ongoing companionship, on a 1-10 scale. Anchors: 1 = mundane/forgettable; "
                    + "5 = shared personal information; 8 = a promise, betrayal, or a real gift; "
                    + "10 = saved or endangered a life. Most facts are low. AVOID clustering scores at "
                    + "7-10 — reserve those for genuinely high-stakes facts. Reply with ONLY a JSON "
                    + "object of the exact form {\"importance\": <integer 1-10>} and nothing else.";

    private ImportanceScorer() {}

    /**
     * Scores the importance of one already-extracted, write-capped fact. Returns {@link #UNSCORED}
     * (0) if the gate is closed, the input is blank, or the model reply could not be parsed into a
     * valid {@code [1,10]} integer. On success returns the clamped importance.
     *
     * @param server        the server (threshold resolution for the gated LLM call)
     * @param layer3Context the W7 gate carrier; the call runs ONLY if {@code layer3Enabled()} is true
     * @param factContent   the candidate fact text (already capped at write time by the ingest path)
     * @return clamped importance in {@code [1,10]}, or {@link #UNSCORED} on skip/parse-failure
     */
    public static int scoreImportance(MinecraftServer server,
                                      Layer3Context layer3Context,
                                      String factContent) {
        // Fail-closed patron gate: no LLM call unless the owner-scoped gate allowed it.
        if (layer3Context == null || !layer3Context.layer3Enabled()) {
            return UNSCORED;
        }
        Player2PayerResolution.ApiBillingContext ownerBilling = layer3Context.ownerBilling();
        if (ownerBilling == null || ownerBilling.billingKey() == null) {
            return UNSCORED;
        }
        if (factContent == null || factContent.isBlank()) {
            return UNSCORED;
        }

        // Reserve a memory-pipeline slot at fire time (two-phase: gate peeked, this records).
        if (!MemoryGate.reserveSlot(ownerBilling)) {
            return UNSCORED;
        }

        try {
            ConversationHistory history = new ConversationHistory(SYSTEM_PROMPT);
            // addUserMessage applies LogEgressGuard.capForModel itself; null api service is safe here
            // (doCutOff=false path never touches it). The content is already write-capped.
            history.addUserMessage("Fact: " + factContent, null);

            String reply = MemoryLlmClient.completeConversationToString(
                    server, ownerBilling, history, AiTaskClass.SUMMARIZATION);

            return parseAndClamp(reply);
        } catch (Exception e) {
            // Bounded, templated WARN — class name only, never message/stack (egress rule).
            com.player2.playerengine.PlayerEngine.LOGGER.warn(
                    "Memory importance scoring failed (reason: {}); leaving node unscored.",
                    e.getClass().getSimpleName());
            return UNSCORED;
        }
    }

    /**
     * Parses {@code {"importance": <int>}} and clamps to {@code [1,10]}. Returns {@link #UNSCORED}
     * (0) on any parse failure / missing field / non-numeric value — the documented "no valid
     * importance" outcome the caller treats as unscored.
     */
    public static int parseAndClamp(String reply) {
        if (reply == null || reply.isBlank()) {
            return UNSCORED;
        }
        try {
            JsonObject obj = JsonParser.parseString(reply).getAsJsonObject();
            if (obj == null || !obj.has("importance") || !obj.get("importance").isJsonPrimitive()) {
                return UNSCORED;
            }
            int raw = obj.get("importance").getAsInt();
            return clamp(raw);
        } catch (Exception parseEx) {
            return UNSCORED;
        }
    }

    /** Clamps to {@code [1,10]}. (0 stays the unscored sentinel; sub-1 raw clamps up to 1.) */
    private static int clamp(int v) {
        if (v < MIN_IMPORTANCE) return MIN_IMPORTANCE;
        if (v > MAX_IMPORTANCE) return MAX_IMPORTANCE;
        return v;
    }
}
