package com.player2.playerengine.modintelligence.ingest;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityGson;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Map;

public final class CapabilityStoreWriter {
    private CapabilityStoreWriter() {}

    public static void writeJsonl(File target, Collection<CapabilityMap> maps) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8))) {
            for (CapabilityMap map : maps) {
                w.write(CapabilityGson.instance().toJson(map));
                w.newLine();
            }
        }
        atomicReplace(tmp, target);
    }

    public static void writeJson(File target, Object value) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileWriter w = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            CapabilityGson.instance().toJson(value, w);
        }
        atomicReplace(tmp, target);
    }

    public static void atomicReplace(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            PlayerEngine.LOGGER.debug("ModIntelligence: atomic move unavailable for {}", target.getName());
        }
    }

    public static void promoteStaging(String runId, ModIntelligenceManifest manifest) throws IOException {
        File staging = ModIntelligencePaths.stagingDir(runId);
        File maps = new File(staging, "capability_maps.jsonl");
        File deleted = new File(staging, "deleted_entries.jsonl");
        ModIntelligencePaths.rootDir().mkdirs();
        if (maps.exists()) {
            atomicReplace(maps, ModIntelligencePaths.capabilityMapsFile());
        }
        if (deleted.exists()) {
            atomicReplace(deleted, ModIntelligencePaths.deletedEntriesFile());
        }
        writeJson(ModIntelligencePaths.manifestFile(), manifest);
        deleteRecursive(staging);
    }

    public static void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    deleteRecursive(c);
                } else {
                    c.delete();
                }
            }
        }
        dir.delete();
    }
}
