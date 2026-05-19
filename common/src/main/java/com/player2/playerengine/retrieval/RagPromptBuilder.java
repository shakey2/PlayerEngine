package com.player2.playerengine.retrieval;

import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Formats RAG retrieval hits into the {@code {{validCommands}}} block for NPC system prompts (B3).
 * Pure functions only — no IO, no Player2 API calls.
 */
public final class RagPromptBuilder {

    private static final Logger LOGGER = LogManager.getLogger(RagPromptBuilder.class);

    /** Control-flow commands required even when retrieval misses them. */
    public static final Set<String> ALWAYS_INCLUDE_IDS = Set.of("idle", "stop", "bodylang");

    private static final int MAX_INJECTED_COMMANDS = 25;

    private static final Set<String> WARNED_MISSING_COMMANDS = new LinkedHashSet<>();

    private RagPromptBuilder() {}

    public static String buildValidCommandsBlock(
            ToolMetadataRegistry registry,
            List<RetrievalHit> hits,
            CommandExecutor executor,
            Set<String> alwaysIncludeIds) {
        if (registry == null || executor == null) {
            return "";
        }

        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        if (alwaysIncludeIds != null) {
            for (String id : alwaysIncludeIds) {
                if (id != null && !id.isBlank()) {
                    orderedIds.add(id.trim());
                }
            }
        }
        if (hits != null) {
            for (RetrievalHit hit : hits) {
                if (hit != null && hit.toolId() != null && !hit.toolId().isBlank()) {
                    orderedIds.add(hit.toolId());
                }
            }
        }

        List<String> limited = new ArrayList<>();
        for (String id : orderedIds) {
            if (limited.size() >= MAX_INJECTED_COMMANDS) {
                break;
            }
            limited.add(id);
        }

        StringBuilder out = new StringBuilder();
        for (String toolId : limited) {
            appendToolBlock(out, toolId, registry, executor);
        }
        return out.toString();
    }

    public static int toolIdSetHash(Set<String> alwaysIncludeIds, List<RetrievalHit> hits) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (alwaysIncludeIds != null) {
            ids.addAll(alwaysIncludeIds);
        }
        if (hits != null) {
            for (RetrievalHit h : hits) {
                if (h != null && h.toolId() != null) {
                    ids.add(h.toolId());
                }
            }
        }
        return ids.hashCode();
    }

    public static boolean hasSubstantiveGoalText(String text, int minAlphanumericChars) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return normalized.length() >= Math.max(1, minAlphanumericChars);
    }

    private static void appendToolBlock(
            StringBuilder out,
            String toolId,
            ToolMetadataRegistry registry,
            CommandExecutor executor) {
        Command cmd = executor.get(toolId);
        ToolDocument doc = registry.getDocument(toolId);
        if (cmd == null) {
            warnMissingCommandOnce(toolId);
            return;
        }
        if (doc == null) {
            appendLegacyLine(out, cmd);
            return;
        }

        out.append(cmd.getName()).append(": ").append(cmd.getDescription()).append('\n');
        if (!doc.whenToUse().isBlank()) {
            out.append("  whenToUse: ").append(doc.whenToUse()).append('\n');
        }
        if (!doc.examples().isEmpty()) {
            out.append("  examples: ").append(String.join("; ", doc.examples())).append('\n');
        }
        if (!doc.keywords().isEmpty()) {
            out.append("  keywords: ").append(String.join(", ", doc.keywords())).append('\n');
        }
        out.append('\n');
    }

    private static void appendLegacyLine(StringBuilder out, Command cmd) {
        int padSize = 10;
        out.append(cmd.getName()).append(": ");
        int pad = padSize - cmd.getName().length();
        out.append(" ".repeat(Math.max(0, pad)));
        out.append(cmd.getDescription()).append('\n');
    }

    private static void warnMissingCommandOnce(String toolId) {
        synchronized (WARNED_MISSING_COMMANDS) {
            if (WARNED_MISSING_COMMANDS.add(toolId)) {
                LOGGER.warn("RAG prompt: tool id '{}' has metadata but no registered Command — skipped", toolId);
            }
        }
    }

    static void clearWarnedMissingCommandsForTests() {
        synchronized (WARNED_MISSING_COMMANDS) {
            WARNED_MISSING_COMMANDS.clear();
        }
    }
}
