package com.player2.playerengine.memory.ingest;

import com.player2.playerengine.memory.MemoryCaps;
import com.player2.playerengine.memory.MemoryNodeType;
import com.player2.playerengine.memory.MergePlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a validated + enriched {@link MemoryExtractionResponse} into a {@link MergePlan} for the
 * store to apply (Phase D, W3).
 *
 * <p><b>Scope note.</b> Full multi-pass entity resolution (alias collapse, cross-graph canonical
 * de-duplication, LLM layer-3 disambiguation) is W4's {@code NodeMerger}/{@code EntityResolver}.
 * Until that lands, this builder is the deterministic, on-device v1 path: it mints a stable id per
 * extraction-local entity NAME (set-union semantics already live in {@code MemoryGraph.mergeNode},
 * keyed on canonical name, so a repeat of a known name reinforces the existing node rather than
 * duplicating it). When W4 is wired, the ingestion service swaps this single call for
 * {@code EntityResolver.mergeCandidates(...)} — the {@link MergePlan} contract is unchanged.
 *
 * <p>Each batch produces exactly ONE <b>episodic provenance vertex</b> (type
 * {@link MemoryNodeType#EVENT}) that {@code mentions} every entity the batch produced (the
 * provenance link required by the plan §597). Relations from the batch become {@link
 * MergePlan.EdgeUpsert}s keyed on the same minted ids.
 *
 * <p>No Minecraft, loader, network, log, or stack-trace dependency. Ids are derived only from the
 * (already capped) entity names + a SHA-256, so the same name always maps to the same id.
 */
public final class MemoryMergePlanBuilder {

    /** Default reinforcement increment for a freshly extracted edge. */
    private static final double DEFAULT_EDGE_WEIGHT = 1.0;
    /** Relation token attaching the episodic vertex to each entity it mentions. */
    private static final String PROVENANCE_RELATION = "mentions";

    private MemoryMergePlanBuilder() {}

    /**
     * Builds the merge plan for one batch. Returns {@code null} when the response is null/empty.
     *
     * @param response the enriched extraction result (entities/relations/keywords/importance)
     * @param nowTick  the server game tick captured before leaving the server thread
     */
    public static MergePlan build(MemoryExtractionResponse response, long nowTick) {
        if (response == null || response.entities().isEmpty()) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        MergePlan.Builder builder = MergePlan.builder();

        // Map extraction-local entity name (lower-cased) → minted node id, for edge wiring.
        Map<String, String> nameToId = new HashMap<>();
        List<String> entityIds = new ArrayList<>();

        for (MemoryExtractionResponse.Entity e : response.entities()) {
            String name = MemoryCaps.capName(e.name);
            if (name == null || name.isEmpty()) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (nameToId.containsKey(key)) continue; // dedupe within the batch
            String id = nodeId(name);
            nameToId.put(key, id);
            entityIds.add(id);

            String type = MemoryNodeType.isKnown(e.type)
                    ? MemoryNodeType.fromWire(e.type).wire()
                    : e.type; // lenient: keep raw (graph excludes unknown types from typed retrieval)

            builder.upsert(new MergePlan.NodeUpsert(
                    id,
                    MemoryCaps.capContent(e.content),
                    type,
                    name,
                    e.aliases,
                    e.tags,
                    e.importance,
                    nowMs,
                    nowTick));
        }

        if (entityIds.isEmpty()) {
            return null;
        }

        // Relations → edges (keyed on minted ids; both endpoints must have been minted).
        for (MemoryExtractionResponse.Relation r : response.relations()) {
            String fromId = nameToId.get(safeLower(r.from));
            String toId = nameToId.get(safeLower(r.to));
            if (fromId == null || toId == null) continue; // dangling — validator should have pruned
            builder.edge(new MergePlan.EdgeUpsert(
                    fromId, toId, MemoryCaps.capRelation(r.relation), DEFAULT_EDGE_WEIGHT, nowTick));
        }

        // One episodic provenance vertex linking the batch's entities (plan §597).
        String episodicId = episodicId(nowMs, entityIds);
        String episodicContent = MemoryCaps.capContent(buildEpisodicContent(response));
        builder.upsert(new MergePlan.NodeUpsert(
                episodicId,
                episodicContent,
                MemoryNodeType.EVENT.wire(),
                MemoryCaps.capName("conversation memory " + episodicId.substring(0, Math.min(8, episodicId.length()))),
                List.of(),
                response.keywords(), // batch keywords as tags on the episodic vertex
                response.importance(),
                nowMs,
                nowTick));
        for (String entId : entityIds) {
            builder.edge(new MergePlan.EdgeUpsert(
                    episodicId, entId, PROVENANCE_RELATION, DEFAULT_EDGE_WEIGHT, nowTick));
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A short, bounded one-line content string for the episodic vertex (keywords only). */
    private static String buildEpisodicContent(MemoryExtractionResponse response) {
        if (response.keywords().isEmpty()) {
            return "conversation episode";
        }
        return "conversation episode: " + String.join(", ", response.keywords());
    }

    /** Stable id for an entity node, derived from its canonical name. */
    private static String nodeId(String canonicalName) {
        return "n_" + sha256Hex(canonicalName.toLowerCase(Locale.ROOT)).substring(0, 16);
    }

    /** Unique id for one episodic vertex (time + member set make it batch-unique). */
    private static String episodicId(long nowMs, List<String> entityIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(nowMs);
        for (String id : entityIds) sb.append('|').append(id);
        return "e_" + sha256Hex(sb.toString()).substring(0, 16);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 is guaranteed present on every JVM; fall back to a bounded hashCode hex.
            return Integer.toHexString(s.hashCode());
        }
    }
}
