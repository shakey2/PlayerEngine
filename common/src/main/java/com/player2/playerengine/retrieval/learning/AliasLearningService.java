package com.player2.playerengine.retrieval.learning;

import com.player2.playerengine.commands.base.CommandExecutor;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.overlay.OverlayEntry;
import com.player2.playerengine.retrieval.overlay.ToolOverlay;
import com.player2.playerengine.retrieval.overlay.ToolOverlayLoader;
import com.player2.playerengine.retrieval.overlay.ToolOverlayWriter;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class AliasLearningService {

    private static final Logger LOGGER = LogManager.getLogger(AliasLearningService.class);
    private static final int MAX_KEYWORDS_PER_EVENT = 5;
    private static final int MAX_EXAMPLES_PER_EVENT = 3;
    private static final int MAX_LEARNED_KEYWORDS_PER_TOOL = 200;
    private static final int MAX_LEARNED_EXAMPLES_PER_TOOL = 100;

    private AliasLearningService() {}

    public static void registerPendingFromSuggestion(
            UUID ownerUuid,
            UUID botUuid,
            String utterance,
            AliasLearnSuggestion suggestion,
            String triggerReason) {
        if (suggestion == null) {
            return;
        }
        String candidateId = UUID.randomUUID().toString().substring(0, 8);
        AliasLearningCandidate candidate = new AliasLearningCandidate(
                candidateId,
                ownerUuid,
                botUuid,
                utterance,
                suggestion.toolId(),
                suggestion.addKeywords(),
                suggestion.addExamples(),
                suggestion.confidence(),
                triggerReason,
                System.currentTimeMillis());
        AliasLearningPendingStore.put(candidate);
    }

    public static void onCommandAccepted(
            MinecraftServer server,
            UUID ownerUuid,
            UUID botUuid,
            String acceptedCommandId,
            CommandExecutor commandExecutor) {
        Optional<AliasLearningCandidate> pending =
                AliasLearningPendingStore.findByBotAndTool(botUuid, acceptedCommandId);
        if (pending.isEmpty()) {
            return;
        }
        AliasLearningCandidate candidate = pending.get();
        AliasLearningPendingStore.remove(botUuid, acceptedCommandId, candidate.candidateId());
        commitCandidate(server, candidate, acceptedCommandId, commandExecutor);
    }

    public static void onCommandRejected(
            MinecraftServer server,
            UUID ownerUuid,
            UUID botUuid,
            String toolId,
            String errMsg) {
        Optional<AliasLearningCandidate> pending = AliasLearningPendingStore.findByBotAndTool(botUuid, toolId);
        pending.ifPresent(c -> {
            AliasLearningPendingStore.remove(botUuid, toolId, c.candidateId());
            AliasLearningAudit.append(server, ownerUuid, botUuid, c.ownerUtterance(), toolId,
                    c.addKeywords(), c.addExamples(), c.confidence(), c.triggerReason(),
                    AliasLearningOutcome.SKIPPED_EXECUTION_ERROR, errMsg);
        });
    }

    private static void commitCandidate(
            MinecraftServer server,
            AliasLearningCandidate candidate,
            String acceptedCommandId,
            CommandExecutor commandExecutor) {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isEnableAliasLearning()) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.SKIPPED_DISABLED, null);
            return;
        }
        if (!candidate.toolId().equals(acceptedCommandId)) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.SKIPPED_COMMAND_MISMATCH, acceptedCommandId);
            return;
        }
        if (commandExecutor.get(candidate.toolId()) == null) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.SKIPPED_INVALID_TOOL, null);
            return;
        }

        Path learnedFile = Player2NpcPersistencePaths.toolLearnedOverridesFile(server, candidate.ownerUuid());
        ToolOverlay overlay = ToolOverlayLoader.loadLearnedFromFile(learnedFile).orElse(new ToolOverlay());
        overlay.schemaVersion = ToolOverlay.SUPPORTED_SCHEMA_VERSION;
        if (overlay.tools == null) {
            overlay.tools = new LinkedHashMap<>();
        }

        Set<String> existing = collectExistingKeywords(server, candidate.ownerUuid(), candidate.toolId());
        List<String> newKeywords = filterNew(candidate.addKeywords(), existing, MAX_KEYWORDS_PER_EVENT);
        List<String> newExamples = filterNewExamples(candidate.addExamples(), existing);

        if (newKeywords.isEmpty() && newExamples.isEmpty()) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.SKIPPED_DUPLICATE, null);
            return;
        }

        OverlayEntry entry = overlay.tools.computeIfAbsent(candidate.toolId(), k -> new OverlayEntry());
        if (entry.addKeywords == null) {
            entry.addKeywords = new ArrayList<>();
        }
        if (entry.addExamples == null) {
            entry.addExamples = new ArrayList<>();
        }
        int kwCap = MAX_LEARNED_KEYWORDS_PER_TOOL - entry.addKeywords.size();
        int exCap = MAX_LEARNED_EXAMPLES_PER_TOOL - entry.addExamples.size();
        if (kwCap <= 0 && exCap <= 0) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.SKIPPED_CAP_REACHED, null);
            return;
        }

        for (int i = 0; i < Math.min(newKeywords.size(), kwCap); i++) {
            entry.addKeywords.add(newKeywords.get(i));
        }
        for (int i = 0; i < Math.min(newExamples.size(), exCap); i++) {
            entry.addExamples.add(newExamples.get(i));
        }

        if (!ToolOverlayWriter.writeAtomic(learnedFile, overlay)) {
            AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                    candidate.ownerUtterance(), candidate.toolId(), candidate.addKeywords(),
                    candidate.addExamples(), candidate.confidence(), candidate.triggerReason(),
                    AliasLearningOutcome.FAILED_WRITE, null);
            return;
        }

        RagIndex.invalidateOwner(candidate.ownerUuid());
        AliasLearningAudit.append(server, candidate.ownerUuid(), candidate.botUuid(),
                candidate.ownerUtterance(), candidate.toolId(), newKeywords, newExamples,
                Math.min(candidate.confidence(), 0.7), candidate.triggerReason(),
                AliasLearningOutcome.COMMITTED, null);
        LOGGER.info("[B5] committed learned aliases for tool={} owner={}", candidate.toolId(), candidate.ownerUuid());
    }

    private static Set<String> collectExistingKeywords(MinecraftServer server, UUID ownerUuid, String toolId) {
        Set<String> existing = new HashSet<>();
        ToolDocument seed = SeedToolMetadata.byId(toolId);
        if (seed != null) {
            seed.keywords().forEach(k -> existing.add(k.toLowerCase(Locale.ROOT)));
        }
        ToolOverlayLoader.loadGlobal().ifPresent(o -> addOverlayKeywords(existing, o, toolId));
        ToolOverlayLoader.loadPerOwner(server, ownerUuid).ifPresent(o -> addOverlayKeywords(existing, o, toolId));
        ToolOverlayLoader.loadLearnedPerOwner(server, ownerUuid)
                .ifPresent(o -> addOverlayKeywords(existing, o, toolId));
        return existing;
    }

    private static void addOverlayKeywords(Set<String> existing, ToolOverlay overlay, String toolId) {
        OverlayEntry e = overlay.safeTools().get(toolId);
        if (e != null) {
            e.safeKeywords().forEach(k -> existing.add(k.toLowerCase(Locale.ROOT)));
        }
    }

    private static List<String> filterNew(List<String> tokens, Set<String> existing, int max) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (t == null || t.isBlank()) continue;
            String lower = t.toLowerCase(Locale.ROOT);
            if (existing.contains(lower)) continue;
            out.add(lower);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static List<String> filterNewExamples(List<String> examples, Set<String> existing) {
        List<String> out = new ArrayList<>();
        for (String ex : examples) {
            if (ex == null || ex.isBlank()) continue;
            if (existing.contains(ex.toLowerCase(Locale.ROOT))) continue;
            out.add(ex.trim());
            if (out.size() >= MAX_EXAMPLES_PER_EVENT) break;
        }
        return out;
    }

    public static int resetLearned(MinecraftServer server, UUID ownerUuid, String toolIdOrNull) {
        Path ownersRoot = Player2NpcPersistencePaths.ownersRoot(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        if (!Files.isDirectory(ownersRoot)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> dirs = Files.list(ownersRoot)) {
            for (Path ownerDir : dirs.filter(Files::isDirectory).toList()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(ownerDir.getFileName().toString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (ownerUuid != null && !ownerUuid.equals(uuid)) {
                    continue;
                }
                if (resetOwnerLearned(server, uuid, toolIdOrNull)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[B5] reset scan failed: {}", e.getMessage());
        }
        if (ownerUuid != null) {
            RagIndex.invalidateOwner(ownerUuid);
        } else {
            RagIndex.invalidateOwnerCache();
        }
        return count;
    }

    private static boolean resetOwnerLearned(MinecraftServer server, UUID ownerUuid, String toolIdOrNull) {
        Path file = Player2NpcPersistencePaths.toolLearnedOverridesFile(server, ownerUuid);
        if (!Files.exists(file)) {
            return false;
        }
        if (toolIdOrNull == null || toolIdOrNull.isBlank()) {
            try {
                Files.deleteIfExists(file);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        Optional<ToolOverlay> overlay = ToolOverlayLoader.loadLearnedFromFile(file);
        if (overlay.isEmpty()) {
            return false;
        }
        ToolOverlay o = overlay.get();
        if (o.tools == null || o.tools.remove(toolIdOrNull.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        if (o.tools.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        } else {
            ToolOverlayWriter.writeAtomic(file, o);
        }
        return true;
    }

    public static List<String> auditTail(MinecraftServer server, int limit) {
        Path file = Player2NpcPersistencePaths.learnAuditFile(server);
        if (!Files.exists(file)) {
            return List.of("No audit log at " + file);
        }
        List<String> lines = new ArrayList<>();
        int skipped = 0;
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, all.size() - limit);
            for (int i = start; i < all.size(); i++) {
                String line = all.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    lines.add(formatAuditLine(line));
                } catch (Exception e) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            return List.of("Failed to read audit: " + e.getMessage());
        }
        if (skipped > 0) {
            lines.add("(skipped " + skipped + " unreadable lines)");
        }
        return lines;
    }

    private static String formatAuditLine(String json) {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        return String.format("[%s] %s owner=%s tool=%s kw=%s",
                o.has("outcome") ? o.get("outcome").getAsString() : "?",
                o.has("triggerReason") ? o.get("triggerReason").getAsString() : "",
                shortUuid(o, "ownerUuid"),
                o.has("toolId") ? o.get("toolId").getAsString() : "",
                o.has("keywords") ? o.get("keywords").toString() : "[]");
    }

    private static String shortUuid(com.google.gson.JsonObject o, String field) {
        if (!o.has(field)) return "";
        String u = o.get(field).getAsString();
        return u.length() > 8 ? u.substring(0, 8) + "…" : u;
    }
}
