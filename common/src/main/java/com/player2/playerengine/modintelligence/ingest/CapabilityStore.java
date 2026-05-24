package com.player2.playerengine.modintelligence.ingest;

import com.google.gson.JsonSyntaxException;
import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityGson;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CapabilityStore {
    private final Map<String, CapabilityMap> activeMaps = new LinkedHashMap<>();
    private ModIntelligenceManifest manifest;

    public Map<String, CapabilityMap> getActiveMaps() {
        return activeMaps;
    }

    public ModIntelligenceManifest getManifest() {
        return manifest;
    }

    public void setManifest(ModIntelligenceManifest manifest) {
        this.manifest = manifest;
    }

    public CapabilityMap get(String entryKey) {
        return activeMaps.get(entryKey);
    }

    public static String entryKey(CapabilityMap map) {
        return map.getSubjectKind().name() + "|" + map.getSubjectId();
    }

    public void loadFromDisk() throws IOException {
        activeMaps.clear();
        manifest = null;
        File manifestFile = ModIntelligencePaths.manifestFile();
        if (manifestFile.exists()) {
            try (FileReader reader = new FileReader(manifestFile, StandardCharsets.UTF_8)) {
                manifest = CapabilityGson.instance().fromJson(reader, ModIntelligenceManifest.class);
            } catch (JsonSyntaxException e) {
                quarantine(manifestFile);
            }
        }
        File mapsFile = ModIntelligencePaths.capabilityMapsFile();
        if (!mapsFile.exists()) {
            return;
        }
        int badLines = 0;
        try (BufferedReader reader = Files.newBufferedReader(mapsFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    CapabilityMap map = CapabilityGson.instance().fromJson(line, CapabilityMap.class);
                    if (map == null || map.getSubjectId() == null) {
                        badLines++;
                        continue;
                    }
                    if (map.getSchemaVersion() > CapabilitySchema.VERSION) {
                        badLines++;
                        continue;
                    }
                    activeMaps.put(entryKey(map), map);
                } catch (Exception e) {
                    badLines++;
                }
            }
        }
        if (badLines > 100) {
            quarantine(mapsFile);
            activeMaps.clear();
        }
    }

    private static void quarantine(File file) throws IOException {
        String name = file.getName() + ".corrupt." + System.currentTimeMillis();
        File dest = new File(file.getParentFile(), name);
        Files.move(file.toPath(), dest.toPath());
        PlayerEngine.LOGGER.warn("ModIntelligence: quarantined corrupt file {}", dest.getName());
    }
}
