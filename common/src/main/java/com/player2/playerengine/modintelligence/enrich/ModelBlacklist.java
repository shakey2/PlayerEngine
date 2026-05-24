package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.ingest.ModIntelligencePaths;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Operator-managed blacklist of Default-profile completion model names for enrichment.
 */
public final class ModelBlacklist {
    private static final Logger LOGGER = PlayerEngine.LOGGER;
    private static final ModelBlacklistSnapshot EMPTY_VALID =
            new ModelBlacklistSnapshot(Set.of(), true, null, false);

    private static volatile ModelBlacklistSnapshot batchSnapshot = EMPTY_VALID;
    private static volatile boolean loggedMissingModelOnce;

    private ModelBlacklist() {}

    public record ModelBlacklistSnapshot(
            Set<String> normalizedModels,
            boolean valid,
            String error,
            boolean requireModelInResponse) {}

    public static ModelBlacklistSnapshot load() {
        try {
            Files.createDirectories(ModIntelligencePaths.modelBlacklistFile().getParentFile().toPath());
        } catch (IOException e) {
            return invalid("could not create mod_intelligence directory: " + e.getMessage());
        }

        if (!ModIntelligencePaths.modelBlacklistFile().exists()) {
            try {
                Files.writeString(
                        ModIntelligencePaths.modelBlacklistFile().toPath(),
                        "[]\n",
                        StandardCharsets.UTF_8);
                LOGGER.info("ModIntelligence: created empty model_blacklist.json at {}",
                        ModIntelligencePaths.modelBlacklistFile().getAbsolutePath());
            } catch (IOException e) {
                return invalid("could not create model_blacklist.json: " + e.getMessage());
            }
            return EMPTY_VALID;
        }

        String raw;
        try {
            raw = Files.readString(ModIntelligencePaths.modelBlacklistFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return invalid("could not read model_blacklist.json: " + e.getMessage());
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (Exception e) {
            return quarantineAndInvalid("malformed JSON: " + e.getMessage());
        }

        if (!parsed.isJsonArray()) {
            return quarantineAndInvalid("model_blacklist.json must be a JSON array");
        }

        Set<String> models = new HashSet<>();
        for (JsonElement entry : parsed.getAsJsonArray()) {
            if (!entry.isJsonPrimitive()) {
                continue;
            }
            String normalized = normalize(entry.getAsString());
            if (!normalized.isEmpty()) {
                models.add(normalized);
            }
        }
        return new ModelBlacklistSnapshot(
                Set.copyOf(models),
                true,
                null,
                !models.isEmpty());
    }

    public static void setBatchSnapshot(ModelBlacklistSnapshot snapshot) {
        batchSnapshot = snapshot == null ? EMPTY_VALID : snapshot;
    }

    public static void clearBatchSnapshot() {
        batchSnapshot = EMPTY_VALID;
        loggedMissingModelOnce = false;
    }

    public static boolean isBlacklisted(String model) {
        if (model == null) {
            return false;
        }
        return batchSnapshot.normalizedModels().contains(normalize(model));
    }

    public static boolean requireModelInResponse() {
        return batchSnapshot.requireModelInResponse();
    }

    public static void logMissingModelOnce() {
        if (!loggedMissingModelOnce) {
            loggedMissingModelOnce = true;
            LOGGER.debug("ModIntelligence enrichment: completion response has no model field (blacklist empty)");
        }
    }

    private static String normalize(String model) {
        if (model == null) {
            return "";
        }
        return model.trim().toLowerCase(Locale.ROOT);
    }

    private static ModelBlacklistSnapshot invalid(String error) {
        return new ModelBlacklistSnapshot(Set.of(), false, error, true);
    }

    private static ModelBlacklistSnapshot quarantineAndInvalid(String reason) {
        try {
            String corruptName = "model_blacklist.json.corrupt."
                    + Instant.now().toEpochMilli();
            Files.move(
                    ModIntelligencePaths.modelBlacklistFile().toPath(),
                    ModIntelligencePaths.modelBlacklistFile().getParentFile().toPath().resolve(corruptName));
            Files.writeString(
                    ModIntelligencePaths.modelBlacklistFile().toPath(),
                    "[]\n",
                    StandardCharsets.UTF_8);
            LOGGER.warn(
                    "ModIntelligence: quarantined corrupt model_blacklist.json to {} ({})",
                    corruptName,
                    reason);
        } catch (IOException e) {
            LOGGER.warn("ModIntelligence: could not quarantine corrupt model_blacklist.json ({}): {}",
                    reason, e.getMessage());
            return invalid(reason + "; quarantine failed: " + e.getMessage());
        }
        return invalid(reason);
    }
}
