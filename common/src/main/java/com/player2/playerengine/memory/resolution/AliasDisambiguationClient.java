package com.player2.playerengine.memory.resolution;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.memory.MemoryNode;
import com.player2.playerengine.memory.budget.Layer3Context;
import com.player2.playerengine.memory.budget.MemoryLlmClient;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;

import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase D, W4 <b>layer 3</b>: bounded, patron-gated LLM alias disambiguation. This is the
 * <b>only</b> LLM call site in {@code memory/resolution/}, and it is structurally unreachable
 * unless {@link Layer3Context#layer3Enabled()} is true (W7 already confirmed patron + budget +
 * reserved slot). It is a <em>confirm</em> step, never a guess: it can only promote a mention to an
 * <em>existing</em> candidate node; on any uncertainty, null, malformed/timeout, or budget skip it
 * returns "mint new" so the caller creates a fresh node.
 *
 * <h3>Egress / routing invariants</h3>
 * <ul>
 *   <li>Routes through {@link MemoryLlmClient} (SUMMARIZATION → Default/cheapest, never the patron's
 *       named profile) which re-caps every history element via {@code LogEgressGuard}.</li>
 *   <li>The prompt contains <b>only</b> the bounded mention string and a small list of already-capped
 *       candidate names/aliases/types — no conversation history, no logs, no game state, no raw
 *       Throwable. The model's {@code reason} is consumed for the decision and <b>never re-injected</b>
 *       into any model-facing surface.</li>
 *   <li>No loader imports; the single Minecraft type is {@link MinecraftServer} (threaded straight to
 *       {@link MemoryLlmClient}).</li>
 * </ul>
 */
public final class AliasDisambiguationClient {

    private AliasDisambiguationClient() {}

    /** Max candidates offered to the model (plan §W4 LAYER3_MAX_CANDIDATES). */
    public static final int MAX_CANDIDATES = 5;

    /** Minimum model confidence to accept a confirm (plan §W4). */
    public static final double CONFIRM_THRESHOLD = 0.7;

    /** Max chars of the model's free-text reason parsed (defense-in-depth; reason is never re-injected). */
    private static final int REASON_MAX = 120;

    /**
     * Small role-lexicon of common nouns that frequently appear as referential aliases of a known
     * character (plan §W4 line 528). Compared against the fully normalized mention. Lowercase.
     */
    public static final Set<String> ROLE_LEXICON = Set.of(
            "blacksmith", "smith", "guard", "merchant", "trader", "friend", "king", "queen",
            "elder", "chief", "priest", "healer", "innkeeper", "farmer", "miner", "hunter",
            "captain", "mayor", "wizard", "witch", "shopkeeper");

    /**
     * Decides whether the normalized mention is a <em>referential</em> description (definite
     * description / common noun / known role) worth an LLM confirm, versus a never-seen capitalized
     * proper noun (which must mint a new node with NO LLM call — plan §W4 lines 527-528).
     *
     * <p>Heuristics, on the ORIGINAL (pre-lowercase) and normalized forms:
     * <ul>
     *   <li>a definite description — original starts with "the " (a leading "the" was stripped during
     *       normalization, so detect it on the raw mention);</li>
     *   <li>the normalized form (or its head/last token) is in {@link #ROLE_LEXICON}; or</li>
     *   <li>the original mention is entirely lowercase (a common noun, not a proper name).</li>
     * </ul>
     * A capitalized token not covered above is treated as a proper noun → returns {@code false}.
     */
    public static boolean isReferentialAlias(String rawMention, String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        String raw = rawMention == null ? "" : rawMention.trim();
        // Definite description: "the blacksmith", "the old man", ...
        if (raw.toLowerCase(Locale.ROOT).startsWith("the ")) {
            return true;
        }
        // Known role term anywhere in the normalized tokens.
        for (String tok : normalized.split("\\s+")) {
            if (ROLE_LEXICON.contains(tok)) {
                return true;
            }
        }
        // Entirely lowercase raw mention → common noun, not a proper name.
        if (!raw.isEmpty() && raw.equals(raw.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // Otherwise: a capitalized, never-seen proper noun → mint new, no LLM.
        return false;
    }

    /**
     * Attempts a layer-3 confirm. Returns a matched {@link ResolutionResult} ONLY when the model
     * confirms an existing candidate with confidence ≥ {@link #CONFIRM_THRESHOLD}; otherwise returns
     * {@link ResolutionResult.ResolutionLayer#NEW_NODE} (or {@code SKIPPED_BUDGET} when the gate is
     * not enabled). Never throws.
     *
     * @param server     the server (threaded to {@link MemoryLlmClient}); {@code null} → mint new
     * @param ctx        the W7 layer-3 context; LLM fires only when {@link Layer3Context#layer3Enabled()}
     * @param rawMention the original mention text (for the referential pre-filter)
     * @param normalized the normalized mention (offered to the model as the term to resolve)
     * @param hintType   the extractor's type hint wire string, or {@code null}
     * @param candidates the pre-selected candidate nodes (≤ {@link #MAX_CANDIDATES})
     */
    public static ResolutionResult confirm(MinecraftServer server,
                                           Layer3Context ctx,
                                           String rawMention,
                                           String normalized,
                                           String hintType,
                                           List<MemoryNode> candidates) {
        // Fail-closed: no gate, no LLM. Caller treats this as "mint new".
        if (ctx == null || !ctx.layer3Enabled() || server == null) {
            return ResolutionResult.skippedBudget();
        }
        if (candidates == null || candidates.isEmpty()) {
            return ResolutionResult.newNode();
        }
        // Pre-filter: only referential aliases reach the model; proper nouns mint new with no call.
        if (!isReferentialAlias(rawMention, normalized)) {
            return ResolutionResult.newNode();
        }

        List<MemoryNode> offered = candidates.size() > MAX_CANDIDATES
                ? candidates.subList(0, MAX_CANDIDATES) : candidates;

        try {
            ConversationHistory history = buildPrompt(normalized, hintType, offered);
            String raw = MemoryLlmClient.completeConversationToString(
                    server, ctx.ownerBilling(), history, AiTaskClass.SUMMARIZATION);
            return parseDecision(raw, offered);
        } catch (Exception e) {
            // Timeout / budget-hard / dispatch failure → mint new. Distilled, bounded reason only;
            // never the message/stack (DESIGN.md §3 data-egress).
            PlayerEngine.LOGGER.debug("[memory] layer-3 disambiguation failed ({}); minting new node",
                    e.getClass().getSimpleName());
            return ResolutionResult.newNode();
        }
    }

    /**
     * Builds the schema-constrained disambiguation prompt. Contains only the bounded mention and the
     * already-capped candidate {id, name, type, aliases} — nothing else. {@link MemoryLlmClient}
     * re-caps every element at the egress edge regardless.
     */
    private static ConversationHistory buildPrompt(String normalized, String hintType,
                                                   List<MemoryNode> candidates) {
        StringBuilder sys = new StringBuilder();
        sys.append("You disambiguate a referring expression to a known entity. ");
        sys.append("Given a MENTION and a list of CANDIDATE entities, decide whether the mention ")
           .append("refers to exactly one candidate. Only confirm when you are confident the mention ")
           .append("denotes that specific candidate; otherwise return a null match. ");
        sys.append("Respond with a single JSON object and nothing else: ")
           .append("{\"match_id\": <candidate id string or null>, ")
           .append("\"confidence\": <number 0..1>, ")
           .append("\"reason\": <short string, <= ").append(REASON_MAX).append(" chars>}.");

        StringBuilder user = new StringBuilder();
        user.append("MENTION: ").append(normalized);
        if (hintType != null && !hintType.isEmpty()) {
            user.append(" (type hint: ").append(hintType).append(')');
        }
        user.append('\n').append("CANDIDATES:");
        for (MemoryNode c : candidates) {
            user.append("\n- id=").append(c.id())
                .append(" name=\"").append(c.canonicalName() == null ? "" : c.canonicalName())
                .append('"');
            if (c.type() != null && !c.type().isEmpty()) {
                user.append(" type=").append(c.type());
            }
            List<String> aliases = c.aliases();
            if (!aliases.isEmpty()) {
                user.append(" aliases=").append(aliases);
            }
        }

        ConversationHistory history = new ConversationHistory(sys.toString());
        // null Player2APIService is safe: addUserMessage uses it only on the size>64 cutoff path,
        // which a two-message history never hits.
        history.addUserMessage(user.toString(), null);
        return history;
    }

    /**
     * Parses the model's JSON object. Confirms only when {@code match_id} is a non-null id present in
     * the offered candidate set AND {@code confidence ≥ CONFIRM_THRESHOLD}. Any malformed/missing/low
     * outcome → mint new. The {@code reason} is parsed defensively but never re-injected anywhere.
     */
    private static ResolutionResult parseDecision(String raw, List<MemoryNode> candidates) {
        if (raw == null || raw.isBlank()) {
            return ResolutionResult.newNode();
        }
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            if (!obj.has("match_id") || obj.get("match_id").isJsonNull()) {
                return ResolutionResult.newNode();
            }
            String matchId = obj.get("match_id").getAsString();
            if (matchId == null || matchId.isBlank()) {
                return ResolutionResult.newNode();
            }
            double confidence = obj.has("confidence") && !obj.get("confidence").isJsonNull()
                    ? obj.get("confidence").getAsDouble() : 0.0;
            if (confidence < CONFIRM_THRESHOLD) {
                return ResolutionResult.newNode();
            }
            // The id must be one we actually offered (never trust a hallucinated id).
            for (MemoryNode c : candidates) {
                if (matchId.equals(c.id())) {
                    return ResolutionResult.llmConfirmed(c.id(), confidence, c.canonicalName());
                }
            }
            return ResolutionResult.newNode();
        } catch (RuntimeException parseEx) {
            // Malformed JSON / wrong shape → mint new.
            return ResolutionResult.newNode();
        }
    }
}
