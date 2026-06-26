package com.player2.playerengine.memory.reflection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.MemoryNodeType;
import com.player2.playerengine.memory.MemoryStore;
import com.player2.playerengine.memory.MergePlan;
import com.player2.playerengine.memory.budget.Layer3Context;
import com.player2.playerengine.memory.budget.MemoryGate;
import com.player2.playerengine.memory.budget.MemoryGateDecision;
import com.player2.playerengine.memory.budget.MemoryLlmClient;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2PayerResolution;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The async, off-tick reflection loop (Phase D, W6). Mirrors
 * {@code WaypointIngestionService}'s threading model: all LLM work runs on
 * {@link MemoryLlmClient#MEMORY_EXECUTOR} (never the server tick) and every graph/store mutation
 * marshals back onto the server thread via {@code server.execute(...)}.
 *
 * <h3>What a reflection does</h3>
 * On a fired trigger (cumulative-importance crossing or session-end), with a patron-confirmed,
 * budgeted owner context, the service makes 2–3 {@code SUMMARIZATION}-routed, W7-gated/budgeted calls:
 * <ol>
 *   <li><b>(A) Salient questions.</b> One completion → 3 short questions worth answering about the
 *       relationship (driven only by the bounded relationship summary + a small recent-node digest).</li>
 *   <li><b>(B) Retrieve per question.</b> Each question is answered from the existing graph via the
 *       W5 retriever (supplied as {@link Retriever}); deterministic, no LLM.</li>
 *   <li><b>(C) Synthesize insights.</b> One completion → 3–5 cited insights; each becomes a
 *       {@code REFLECTION} {@link MemoryNode} with {@code reflected_about → entity} edges (entity ids
 *       come from the retrieved context). Written via a {@link MergePlan} applied on the server thread.</li>
 *   <li><b>(D) Relationship summary.</b> One completion (or folded into C) → a one/two-sentence
 *       summary, hard-capped via {@link RelationshipSummary}, pushed into the store
 *       ({@code setRelationshipSummary} resets the counter + bumps {@code summaryVersion}) and then
 *       into the system block by the caller-supplied {@code onSummaryUpdated} hook.</li>
 * </ol>
 * Budget-exhausted / gate-closed → the reflection is skipped and retried on the next trigger; the
 * degradation is surfaced to BOTH the player and the model via the caller-supplied
 * {@link DegradationSink} (DESIGN.md §3 both-audiences truthfulness rule).
 *
 * <h3>Egress (release-blocker invariants)</h3>
 * Every LLM call is downstream of the {@link Layer3Context} gate (non-patron/null/owner-offline →
 * zero calls), routes {@code SUMMARIZATION} → cheapest, and flows through {@link MemoryLlmClient}
 * which re-caps every history element. Only bounded, write-capped graph strings + curated prompts
 * reach the model — no log/stack/{@code Throwable}/unbounded string is ever concatenated into a
 * model-facing surface. Caught exceptions distil to {@code getClass().getSimpleName()}.
 *
 * <p>This service is NOT on the per-turn hot path; the per-turn scorer is {@link MemoryScorer}.
 */
public final class ReflectionService {

    /** Number of REFLECTION nodes a single reflection may write (plan 3–5). */
    public static final int MAX_INSIGHTS = 5;
    private static final int MIN_INSIGHTS = 3;

    /** Number of salient questions step (A) asks for. */
    public static final int QUESTION_COUNT = 3;

    /** The edge relation linking a REFLECTION node to the entity it is about. */
    public static final String RELATION_REFLECTED_ABOUT = "reflected_about";

    /** Per-store in-flight latch: prevents overlapping reflections (one at a time per store). */
    // The caller owns one latch per store; this service honors it.

    private ReflectionService() {}

    // -------------------------------------------------------------------------
    // Collaborator seams (supplied by the integration pass; not stubbed here)
    // -------------------------------------------------------------------------

    /**
     * W5 retrieval seam: answers a reflection question against the published snapshot, returning the
     * relevant nodes (deterministic, no LLM). The integration pass wires this to
     * {@code MemoryRetriever}. Must be callable off-tick on an immutable snapshot.
     */
    @FunctionalInterface
    public interface Retriever {
        List<MemoryNode> retrieve(String question, int k);
    }

    /**
     * Both-audiences degradation sink (DESIGN.md §3). When a reflection is skipped (budget/gate), the
     * service hands the bounded player chat line + the distilled model-feedback token to the caller,
     * which delivers each to its audience. Never receives raw enum/stack/unbounded text.
     */
    @FunctionalInterface
    public interface DegradationSink {
        void report(String playerMessage, String modelFeedbackToken);
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Schedules one reflection off-tick. Returns immediately (never blocks the server thread). The
     * caller has ALREADY resolved the owner billing context and run {@link MemoryGate#preflight};
     * pass the resulting {@link Layer3Context}. If the gate is closed this method reports the
     * degradation and schedules nothing.
     *
     * @param server          the server (for the gated LLM calls + the marshal callback)
     * @param store           the companion store (mutated only on the server thread)
     * @param layer3Context   the W7 gate carrier (LLM runs ONLY if {@code layer3Enabled()})
     * @param inFlight        per-store latch; the service sets it on start and clears it on finish,
     *                        skipping if already set (no overlapping reflections)
     * @param retriever       the W5 retrieval seam (step B)
     * @param nowTick         current server game tick (provenance for new REFLECTION nodes)
     * @param onSummaryUpdated server-thread hook invoked with the new capped summary after step D
     *                         persists it (the integration pass pushes it into the system block)
     * @param degradation     both-audiences degradation sink for a skipped reflection
     */
    public static void scheduleReflection(MinecraftServer server,
                                          MemoryStore store,
                                          Layer3Context layer3Context,
                                          AtomicBoolean inFlight,
                                          Retriever retriever,
                                          long nowTick,
                                          Consumer<String> onSummaryUpdated,
                                          DegradationSink degradation) {
        if (server == null || store == null) {
            return;
        }

        // Fail-closed gate: no LLM unless the owner-scoped gate allowed it.
        if (layer3Context == null || !layer3Context.layer3Enabled()) {
            reportClosedGate(layer3Context, degradation);
            return;
        }
        Player2PayerResolution.ApiBillingContext ownerBilling = layer3Context.ownerBilling();
        if (ownerBilling == null || ownerBilling.billingKey() == null) {
            reportClosedGate(layer3Context, degradation);
            return;
        }

        // One reflection at a time per store — drop (retry next trigger) if one is in flight.
        if (inFlight != null && !inFlight.compareAndSet(false, true)) {
            return;
        }

        // Capture the bounded inputs we need off-thread (no graph access on the executor thread).
        final String currentSummary = store.relationshipSummary();
        final String recentDigest = buildRecentDigest(store);

        MemoryLlmClient.MEMORY_EXECUTOR.submit(() -> {
            try {
                runReflection(server, store, ownerBilling, retriever, nowTick,
                        currentSummary, recentDigest, onSummaryUpdated, degradation);
            } catch (Exception e) {
                // Bounded WARN — class name only (egress). A failed reflection simply retries next trigger.
                PlayerEngine.LOGGER.warn("Memory reflection failed (reason: {}); will retry next trigger.",
                        e.getClass().getSimpleName());
            } finally {
                if (inFlight != null) inFlight.set(false);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Off-tick reflection body (runs on MEMORY_EXECUTOR)
    // -------------------------------------------------------------------------

    private static void runReflection(MinecraftServer server,
                                      MemoryStore store,
                                      Player2PayerResolution.ApiBillingContext ownerBilling,
                                      Retriever retriever,
                                      long nowTick,
                                      String currentSummary,
                                      String recentDigest,
                                      Consumer<String> onSummaryUpdated,
                                      DegradationSink degradation) throws Exception {

        // Each LLM step reserves its memory-window slot immediately before its HTTP dispatch (inside
        // the step method), so "slot reserved ⟺ a real completion was dispatched" holds exactly — a
        // budget-capped step consumes NO slot. Steps signal a budget skip by returning null.

        // (A) Salient questions.
        List<String> questions = askSalientQuestions(server, ownerBilling, currentSummary, recentDigest);
        if (questions == null) { // budget cap reached before the call — no slot spent
            reportBudgetSkip(degradation);
            return;
        }
        if (questions.isEmpty()) {
            return; // call made but no usable questions; no summary change, counter left for next trigger
        }

        // (B) Retrieve per question (deterministic, no LLM, no budget).
        List<MemoryNode> retrieved = new ArrayList<>();
        if (retriever != null) {
            for (String q : questions) {
                List<MemoryNode> hits = retriever.retrieve(q, MemoryScorer.DEFAULT_TOP_K);
                if (hits != null) retrieved.addAll(hits);
            }
        }
        String retrievedDigest = buildNodeDigest(retrieved);

        // (C) Synthesize cited insights → REFLECTION nodes + reflected_about edges.
        List<Insight> insights = synthesizeInsights(server, ownerBilling, currentSummary, retrievedDigest);
        if (insights == null) { // budget cap reached before the call — no slot spent
            reportBudgetSkip(degradation);
            return;
        }
        MergePlan plan = buildReflectionPlan(insights, retrieved, nowTick);

        // (D) Relationship summary.
        String rawSummary = synthesizeSummary(server, ownerBilling, currentSummary, retrievedDigest, insights);
        if (rawSummary == null) { // budget cap reached before the call — no slot spent
            reportBudgetSkip(degradation);
            // We may still write the insight nodes even if the summary call is budget-capped.
            applyOnServerThread(server, store, plan, null, onSummaryUpdated);
            return;
        }
        RelationshipSummary summary = RelationshipSummary.of(rawSummary);

        // Marshal all writes back to the server thread (graph + summary + counter reset).
        applyOnServerThread(server, store, plan, summary, onSummaryUpdated);
    }

    // -------------------------------------------------------------------------
    // LLM steps (each via MemoryLlmClient → SUMMARIZATION/cheapest, egress-guarded)
    // -------------------------------------------------------------------------

    /** @return the parsed questions (possibly empty), or {@code null} if the budget slot was capped before the call. */
    private static List<String> askSalientQuestions(MinecraftServer server,
                                                    Player2PayerResolution.ApiBillingContext billing,
                                                    String currentSummary,
                                                    String recentDigest) throws Exception {
        ConversationHistory history = new ConversationHistory(
                "You help a game companion reflect on its relationship with a player. Given the current "
                        + "relationship summary and a digest of recent memories, propose exactly "
                        + QUESTION_COUNT + " short, distinct questions worth answering about the "
                        + "relationship. Reply with ONLY a JSON object {\"questions\": [\"...\", ...]} "
                        + "and nothing else.");
        history.addUserMessage(
                "Current summary: " + nz(currentSummary) + "\nRecent memories: " + nz(recentDigest), null);
        // Reserve the slot immediately before the HTTP dispatch (atomic with the call).
        if (!MemoryGate.reserveSlot(billing)) {
            return null;
        }
        String reply = MemoryLlmClient.completeConversationToString(
                server, billing, history, AiTaskClass.SUMMARIZATION);
        return parseStringArray(reply, "questions", QUESTION_COUNT);
    }

    /** @return the parsed insights (possibly empty), or {@code null} if the budget slot was capped before the call. */
    private static List<Insight> synthesizeInsights(MinecraftServer server,
                                                    Player2PayerResolution.ApiBillingContext billing,
                                                    String currentSummary,
                                                    String retrievedDigest) throws Exception {
        ConversationHistory history = new ConversationHistory(
                "You synthesize durable relationship insights for a game companion. From the retrieved "
                        + "memories, write " + MIN_INSIGHTS + " to " + MAX_INSIGHTS + " concise insights, "
                        + "each grounded ONLY in the provided memories (cite the entity name it is about). "
                        + "Reply with ONLY a JSON object {\"insights\": [{\"text\": \"...\", \"about\": "
                        + "\"<entity name>\"}, ...]} and nothing else.");
        history.addUserMessage(
                "Current summary: " + nz(currentSummary) + "\nRetrieved memories: " + nz(retrievedDigest), null);
        // Reserve the slot immediately before the HTTP dispatch (atomic with the call).
        if (!MemoryGate.reserveSlot(billing)) {
            return null;
        }
        String reply = MemoryLlmClient.completeConversationToString(
                server, billing, history, AiTaskClass.SUMMARIZATION);
        return parseInsights(reply);
    }

    private static String synthesizeSummary(MinecraftServer server,
                                            Player2PayerResolution.ApiBillingContext billing,
                                            String currentSummary,
                                            String retrievedDigest,
                                            List<Insight> insights) throws Exception {
        ConversationHistory history = new ConversationHistory(
                "You write a one or two sentence summary of how a game companion sees its relationship "
                        + "with a player, grounded ONLY in the provided context. Keep it under "
                        + MemoryStore.effectiveSummaryCharCap() + " characters. Reply with ONLY a JSON object "
                        + "{\"summary\": \"...\"} and nothing else.");
        history.addUserMessage(
                "Previous summary: " + nz(currentSummary)
                        + "\nNew insights: " + insightDigest(insights)
                        + "\nRetrieved memories: " + nz(retrievedDigest), null);
        // Reserve the slot immediately before the HTTP dispatch (atomic with the call).
        if (!MemoryGate.reserveSlot(billing)) {
            return null;
        }
        String reply = MemoryLlmClient.completeConversationToString(
                server, billing, history, AiTaskClass.SUMMARIZATION);
        return parseStringField(reply, "summary");
    }

    // -------------------------------------------------------------------------
    // Server-thread marshal (graph + summary mutation)
    // -------------------------------------------------------------------------

    /**
     * Marshals the REFLECTION-node writes and the summary update onto the server thread (the ONLY
     * thread allowed to mutate the store). {@code setRelationshipSummary} resets the cumulative
     * counter + bumps {@code summaryVersion}; {@code onSummaryUpdated} then pushes the new summary
     * into the system block (still on the server thread, so it is byte-stable for that version).
     */
    private static void applyOnServerThread(MinecraftServer server,
                                            MemoryStore store,
                                            MergePlan plan,
                                            RelationshipSummary summary,
                                            Consumer<String> onSummaryUpdated) {
        server.execute(() -> {
            if (plan != null && !plan.isEmpty()) {
                store.mergeCandidates(plan);
            }
            if (summary != null && !summary.isEmpty()) {
                store.setRelationshipSummary(summary.text()); // resets counter + bumps summaryVersion
                if (onSummaryUpdated != null) {
                    onSummaryUpdated.accept(store.relationshipSummary());
                }
            } else {
                // Budget-capped summary step (or empty summary): we still wrote insight nodes above, but
                // the summary — and therefore the system block / prefix cache — is unchanged, so we must
                // NOT bump summaryVersion or re-push the suffix. Reset ONLY the trigger counter so the
                // next reflection fires at the configured cadence instead of immediately re-triggering
                // and recursing into the same budget cap. Prefix-cache stays byte-identical.
                store.resetReflectionCounterOnly();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Plan assembly: REFLECTION nodes + reflected_about edges
    // -------------------------------------------------------------------------

    private static MergePlan buildReflectionPlan(List<Insight> insights,
                                                 List<MemoryNode> retrieved,
                                                 long nowTick) {
        MergePlan.Builder builder = MergePlan.builder();
        long timestampMs = System.currentTimeMillis();
        int written = 0;
        for (Insight insight : insights) {
            if (written >= MAX_INSIGHTS) break;
            if (insight.text == null || insight.text.isBlank()) continue;

            String reflectionId = "reflection:" + UUID.randomUUID();
            String content = MemoryCaps.capContent(insight.text);
            String name = MemoryCaps.capName(
                    insight.about != null && !insight.about.isBlank() ? insight.about : "reflection");

            builder.upsert(new MergePlan.NodeUpsert(
                    reflectionId,
                    content,
                    MemoryNodeType.REFLECTION.wire(),
                    name,
                    List.of(),
                    List.of("reflection"),
                    0,                 // REFLECTION nodes are unscored on the importance rubric
                    timestampMs,
                    nowTick));

            // reflected_about → the entity the insight cites (matched against retrieved nodes).
            String targetId = matchEntityId(insight.about, retrieved);
            if (targetId != null) {
                builder.edge(new MergePlan.EdgeUpsert(
                        reflectionId, targetId, RELATION_REFLECTED_ABOUT, 1.0, nowTick));
            }
            written++;
        }
        return builder.build();
    }

    /** Resolves the cited entity name to an existing retrieved node id (case-insensitive). */
    private static String matchEntityId(String about, List<MemoryNode> retrieved) {
        if (about == null || about.isBlank() || retrieved == null) return null;
        String target = about.trim();
        for (MemoryNode n : retrieved) {
            if (n.canonicalName() != null && n.canonicalName().equalsIgnoreCase(target)) {
                return n.id();
            }
        }
        for (MemoryNode n : retrieved) {
            for (String alias : n.aliases()) {
                if (alias != null && alias.equalsIgnoreCase(target)) {
                    return n.id();
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Digests (bounded, write-capped strings only — never logs/stack/unbounded)
    // -------------------------------------------------------------------------

    /** Small digest of the most recently seen nodes, for the question step. Bounded. */
    private static String buildRecentDigest(MemoryStore store) {
        MemoryStore.Snapshot snap = store.snapshot();
        if (snap == null) return "";
        List<MemoryNode> nodes = new ArrayList<>(snap.graph().nodes());
        nodes.sort((a, b) -> Long.compare(b.lastSeenTick(), a.lastSeenTick()));
        return buildNodeDigest(nodes.size() > 12 ? nodes.subList(0, 12) : nodes);
    }

    /** Joins node (name: content) lines; every field is already write-capped. */
    private static String buildNodeDigest(List<MemoryNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (MemoryNode n : nodes) {
            if (shown >= 24) break;
            if (sb.length() > 0) sb.append("; ");
            sb.append(nz(n.canonicalName())).append(": ").append(nz(n.content()));
            shown++;
        }
        return sb.toString();
    }

    private static String insightDigest(List<Insight> insights) {
        if (insights == null || insights.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Insight i : insights) {
            if (i.text == null || i.text.isBlank()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(i.text);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Degradation reporting (both audiences)
    // -------------------------------------------------------------------------

    private static void reportClosedGate(Layer3Context ctx, DegradationSink sink) {
        if (sink == null) return;
        MemoryGateDecision decision = (ctx != null) ? ctx.gateDecision() : null;
        if (decision == null) {
            decision = MemoryGateDecision.skip(MemoryGateDecision.SkipReason.NOT_PATRON);
        }
        sink.report(decision.playerMessage(), decision.modelFeedbackToken());
    }

    private static void reportBudgetSkip(DegradationSink sink) {
        if (sink == null) return;
        MemoryGateDecision d = MemoryGateDecision.skip(MemoryGateDecision.SkipReason.EXTRACTION_CAP_SKIP);
        sink.report(d.playerMessage(), d.modelFeedbackToken());
    }

    // -------------------------------------------------------------------------
    // Parsing (bounded; failures degrade to empty — never throw into model-facing text)
    // -------------------------------------------------------------------------

    private static List<String> parseStringArray(String reply, String key, int max) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) return out;
        try {
            JsonObject obj = JsonParser.parseString(reply).getAsJsonObject();
            if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return out;
            JsonArray arr = obj.getAsJsonArray(key);
            for (JsonElement el : arr) {
                if (out.size() >= max) break;
                if (el != null && el.isJsonPrimitive()) {
                    String s = el.getAsString();
                    if (s != null && !s.isBlank()) out.add(s.trim());
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return out;
    }

    private static String parseStringField(String reply, String key) {
        if (reply == null || reply.isBlank()) return "";
        try {
            JsonObject obj = JsonParser.parseString(reply).getAsJsonObject();
            if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) return "";
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Insight> parseInsights(String reply) {
        List<Insight> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) return out;
        try {
            JsonObject obj = JsonParser.parseString(reply).getAsJsonObject();
            if (obj == null || !obj.has("insights") || !obj.get("insights").isJsonArray()) return out;
            JsonArray arr = obj.getAsJsonArray("insights");
            for (JsonElement el : arr) {
                if (out.size() >= MAX_INSIGHTS) break;
                if (el == null || !el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String text = o.has("text") && o.get("text").isJsonPrimitive()
                        ? o.get("text").getAsString() : null;
                String about = o.has("about") && o.get("about").isJsonPrimitive()
                        ? o.get("about").getAsString() : null;
                if (text != null && !text.isBlank()) {
                    out.add(new Insight(text.trim(), about != null ? about.trim() : null));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return out;
    }

    /** A synthesized insight: the durable text + the entity name it is about (nullable). */
    private static final class Insight {
        final String text;
        final String about;

        Insight(String text, String about) {
            this.text = text;
            this.about = about;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
