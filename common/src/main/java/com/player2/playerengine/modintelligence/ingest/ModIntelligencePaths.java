package com.player2.playerengine.modintelligence.ingest;

import com.player2.playerengine.PlayerEnginePaths;

import java.io.File;
import java.nio.file.Path;

public final class ModIntelligencePaths {
    public static final String ROOT_DIR = "mod_intelligence";

    private ModIntelligencePaths() {}

    public static File rootDir() {
        return PlayerEnginePaths.modIntelligenceRoot().toFile();
    }

    public static File manifestFile() {
        return new File(rootDir(), "manifest.json");
    }

    public static File capabilityMapsFile() {
        return new File(rootDir(), "capability_maps.jsonl");
    }

    public static File deletedEntriesFile() {
        return new File(rootDir(), "deleted_entries.jsonl");
    }

    public static File enrichmentQueueFile() {
        return new File(rootDir(), "enrichment_queue.jsonl");
    }

    public static File enrichmentFailuresFile() {
        return new File(rootDir(), "enrichment_failures.jsonl");
    }

    public static File modelBlacklistFile() {
        return new File(rootDir(), "model_blacklist.json");
    }

    public static File indexDir() {
        return new File(rootDir(), "capability_index");
    }

    public static File indexBinFile() {
        return new File(indexDir(), "capabilities.bin");
    }

    public static File indexVersionFile() {
        return new File(indexDir(), "version.txt");
    }

    public static File ingestLockFile() {
        return new File(rootDir(), ".ingest.lock");
    }

    public static File stagingDir(String runId) {
        return new File(rootDir(), ".staging" + File.separator + runId);
    }

    public static Path rootPath() {
        return rootDir().toPath();
    }
}
