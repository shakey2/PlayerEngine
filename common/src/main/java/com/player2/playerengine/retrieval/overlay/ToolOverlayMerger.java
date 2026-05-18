package com.player2.playerengine.retrieval.overlay;

import com.player2.playerengine.retrieval.ToolDocument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-function merger that applies {@link ToolOverlay} patches to a baseline
 * {@link ToolDocument} collection.
 *
 * <p>Merge rules (enforced in order; log-and-skip on violation, never throw):
 * <ol>
 *   <li>Only {@code addKeywords} and {@code addExamples} are applied — name, description,
 *       whenToUse, and categoryTags are immutable.
 *   <li>Unknown tool ids in an overlay are skipped with a WARN.
 *   <li>Keywords are lowercased before comparison and insertion.
 *   <li>Keywords are deduped against the baseline keywords and all earlier overlay layers.
 *   <li>Keyword tokens containing characters outside {@code [a-z0-9 \-_']} are skipped with WARN.
 *   <li>Max keywords per tool (baseline + overlays combined): {@value #MAX_KEYWORDS_PER_TOOL}.
 *       Excess additions are silently dropped with a WARN.
 *   <li>Max new examples added per tool: {@value #MAX_EXAMPLES_PER_TOOL}. Excess additions
 *       are dropped.
 * </ol>
 *
 * <p>Each call to {@link #merge} is a pure function: the baseline collection is never mutated.
 */
public final class ToolOverlayMerger {

    private static final Logger LOGGER = LogManager.getLogger(ToolOverlayMerger.class);

    public static final int MAX_KEYWORDS_PER_TOOL = 200;
    public static final int MAX_EXAMPLES_PER_TOOL = 5;

    /** Tokens must consist only of lowercase letters, digits, spaces, hyphens, underscores, apostrophes. */
    private static final Pattern ALLOWED_KEYWORD = Pattern.compile("[a-z0-9 \\-_']+");

    private ToolOverlayMerger() {}

    /**
     * Applies {@code overlays} (in order) onto {@code baseline} and returns a new list of
     * {@link ToolDocument} instances with merged keywords/examples. Documents not referenced
     * by any overlay are returned unchanged.
     *
     * @param baseline unmodified source documents
     * @param overlays ordered list of overlays; global first, then per-owner
     * @return merged collection (same size as baseline; order preserved)
     */
    public static Collection<ToolDocument> merge(Collection<ToolDocument> baseline,
                                                  List<ToolOverlay> overlays) {
        if (overlays == null || overlays.isEmpty()) {
            return baseline;
        }

        // Build a working map from tool id → mutable keyword/example accumulators
        Map<String, WorkingDoc> working = new LinkedHashMap<>();
        for (ToolDocument doc : baseline) {
            working.put(doc.id(), new WorkingDoc(doc));
        }

        for (ToolOverlay overlay : overlays) {
            if (overlay == null) continue;
            for (Map.Entry<String, OverlayEntry> entry : overlay.safeTools().entrySet()) {
                String toolId = entry.getKey();
                OverlayEntry patch = entry.getValue();
                if (patch == null) continue;

                WorkingDoc wd = working.get(toolId);
                if (wd == null) {
                    LOGGER.warn("RAG overlay: unknown toolId '{}' — skipping.", toolId);
                    continue;
                }

                applyKeywords(wd, patch.safeKeywords(), toolId);
                applyExamples(wd, patch.safeExamples(), toolId);
            }
        }

        // Reconstruct ToolDocuments; documents without changes are kept as-is
        List<ToolDocument> result = new ArrayList<>(working.size());
        for (WorkingDoc wd : working.values()) {
            result.add(wd.build());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void applyKeywords(WorkingDoc wd, List<String> additions, String toolId) {
        for (String raw : additions) {
            if (raw == null || raw.isBlank()) continue;
            String lower = raw.trim().toLowerCase();
            if (!ALLOWED_KEYWORD.matcher(lower).matches()) {
                LOGGER.warn("RAG overlay: keyword '{}' for tool '{}' contains disallowed characters — skipping.",
                        lower, toolId);
                continue;
            }
            if (wd.keywords.size() >= MAX_KEYWORDS_PER_TOOL) {
                LOGGER.warn("RAG overlay: tool '{}' already has {} keywords (cap); "
                                + "further additions dropped.",
                        toolId, MAX_KEYWORDS_PER_TOOL);
                break;
            }
            wd.keywords.add(lower); // LinkedHashSet silently ignores duplicates
        }
    }

    private static void applyExamples(WorkingDoc wd, List<String> additions, String toolId) {
        int added = 0;
        for (String example : additions) {
            if (example == null || example.isBlank()) continue;
            if (added >= MAX_EXAMPLES_PER_TOOL) {
                LOGGER.warn("RAG overlay: tool '{}' has hit per-overlay example cap ({}); "
                                + "remaining additions dropped.",
                        toolId, MAX_EXAMPLES_PER_TOOL);
                break;
            }
            wd.examples.add(example.trim());
            added++;
        }
    }

    // -------------------------------------------------------------------------
    // Working document accumulator
    // -------------------------------------------------------------------------

    private static final class WorkingDoc {
        final ToolDocument original;
        final Set<String> keywords; // preserves insertion order; dedupes via Set
        final List<String> examples;
        boolean dirty = false;

        WorkingDoc(ToolDocument doc) {
            this.original = doc;
            // Seed with existing keywords lowercased (deduplication baseline)
            this.keywords = new LinkedHashSet<>();
            for (String k : doc.keywords()) {
                keywords.add(k.toLowerCase());
            }
            this.examples = new ArrayList<>(doc.examples());
        }

        /**
         * Overrides {@link #keywords} add to track dirtiness.
         * Delegated from {@link #applyKeywords} — we use the Set's return value directly.
         */
        ToolDocument build() {
            // Check if anything actually changed before constructing a new object
            List<String> mergedKeywords = new ArrayList<>(keywords);
            if (mergedKeywords.equals(lowerAll(original.keywords()))
                    && examples.equals(original.examples())) {
                return original;
            }
            return new ToolDocument(
                    original.id(),
                    original.name(),
                    original.description(),
                    original.whenToUse(),
                    new ArrayList<>(examples),
                    mergedKeywords,
                    original.categoryTags());
        }

        private static List<String> lowerAll(List<String> list) {
            List<String> out = new ArrayList<>(list.size());
            for (String s : list) out.add(s.toLowerCase());
            return out;
        }
    }
}
