package com.player2.playerengine.modintelligence.enrich;

import com.player2.playerengine.modintelligence.capability.CapabilityGson;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.ingest.ModIntelligencePaths;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class CapabilityEnrichmentQueue {
    private CapabilityEnrichmentQueue() {}

    public static List<CapabilityMap> loadQueued() {
        List<CapabilityMap> out = new ArrayList<>();
        if (!ModIntelligencePaths.enrichmentQueueFile().exists()) {
            return out;
        }
        try (BufferedReader reader = Files.newBufferedReader(
                ModIntelligencePaths.enrichmentQueueFile().toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                CapabilityMap map = CapabilityGson.instance().fromJson(line, CapabilityMap.class);
                if (map != null) {
                    out.add(map);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static int countQueuedLines() {
        return loadQueued().size();
    }

    /** Cumulative enrichment attempt failures logged to {@code enrichment_failures.jsonl}. */
    public static int countFailureLogLines() {
        if (!ModIntelligencePaths.enrichmentFailuresFile().exists()) {
            return 0;
        }
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(
                ModIntelligencePaths.enrichmentFailuresFile().toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    public static void saveQueued(List<CapabilityMap> maps) throws java.io.IOException {
        if (maps == null || maps.isEmpty()) {
            if (ModIntelligencePaths.enrichmentQueueFile().exists()) {
                ModIntelligencePaths.enrichmentQueueFile().delete();
            }
            return;
        }
        com.player2.playerengine.modintelligence.ingest.CapabilityStoreWriter.writeJsonl(
                ModIntelligencePaths.enrichmentQueueFile(), maps);
    }
}
