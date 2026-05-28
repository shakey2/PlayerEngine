package com.player2.playerengine.retrieval.learning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class AliasLearningAudit {

    public static final int SCHEMA_VERSION = 1;
    private static final Logger LOGGER = LogManager.getLogger(AliasLearningAudit.class);
    private static final Gson GSON = new GsonBuilder().create();

    private AliasLearningAudit() {}

    public static void append(
            MinecraftServer server,
            UUID ownerUuid,
            UUID botUuid,
            String utterance,
            String toolId,
            List<String> keywords,
            List<String> examples,
            double confidence,
            String triggerReason,
            AliasLearningOutcome outcome,
            String details) {
        Path file = Player2NpcPersistencePaths.learnAuditFile(server);
        try {
            Files.createDirectories(file.getParent());
            AuditRow row = new AuditRow(
                    SCHEMA_VERSION,
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    ownerUuid != null ? ownerUuid.toString() : "",
                    botUuid != null ? botUuid.toString() : "",
                    utteranceHash(utterance),
                    toolId,
                    keywords,
                    examples,
                    confidence,
                    triggerReason,
                    outcome.name(),
                    details != null && details.length() > 200 ? details.substring(0, 200) : details);
            try (BufferedWriter w = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(GSON.toJson(row));
                w.newLine();
            }
        } catch (IOException e) {
            LOGGER.warn("[B5] audit append failed: {}", e.getMessage());
        }
    }

    public static String utteranceHash(String utterance) {
        if (utterance == null || utterance.isBlank()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(utterance.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "hash_error";
        }
    }

  @SuppressWarnings("unused")
  private static final class AuditRow {
        final int schemaVersion;
        final String eventId;
        final long timestampEpochMillis;
        final String ownerUuid;
        final String botUuid;
        final String utteranceHash;
        final String toolId;
        final List<String> keywords;
        final List<String> examples;
        final double confidence;
        final String triggerReason;
        final String outcome;
        final String details;

        AuditRow(int schemaVersion, String eventId, long timestampEpochMillis, String ownerUuid,
                 String botUuid, String utteranceHash, String toolId, List<String> keywords,
                 List<String> examples, double confidence, String triggerReason, String outcome, String details) {
            this.schemaVersion = schemaVersion;
            this.eventId = eventId;
            this.timestampEpochMillis = timestampEpochMillis;
            this.ownerUuid = ownerUuid;
            this.botUuid = botUuid;
            this.utteranceHash = utteranceHash;
            this.toolId = toolId;
            this.keywords = keywords;
            this.examples = examples;
            this.confidence = confidence;
            this.triggerReason = triggerReason;
            this.outcome = outcome;
            this.details = details;
        }
    }
}
