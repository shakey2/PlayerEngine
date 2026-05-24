package com.player2.playerengine.modintelligence.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModIntelligenceManifest {
    private int schemaVersion;
    private int inspectorVersion;
    private int heuristicVersion;
    private String minecraftVersion;
    private String loader;
    private String packFingerprint;
    private Map<String, ModFingerprint> mods = new LinkedHashMap<>();
    private Map<String, EntryFingerprint> entries = new LinkedHashMap<>();
    private long updatedAtEpochMillis;

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public int getInspectorVersion() { return inspectorVersion; }
    public void setInspectorVersion(int inspectorVersion) { this.inspectorVersion = inspectorVersion; }

    public int getHeuristicVersion() { return heuristicVersion; }
    public void setHeuristicVersion(int heuristicVersion) { this.heuristicVersion = heuristicVersion; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { this.minecraftVersion = minecraftVersion; }

    public String getLoader() { return loader; }
    public void setLoader(String loader) { this.loader = loader; }

    public String getPackFingerprint() { return packFingerprint; }
    public void setPackFingerprint(String packFingerprint) { this.packFingerprint = packFingerprint; }

    public Map<String, ModFingerprint> getMods() { return mods; }
    public void setMods(Map<String, ModFingerprint> mods) {
        this.mods = mods == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mods);
    }

    public Map<String, EntryFingerprint> getEntries() { return entries; }
    public void setEntries(Map<String, EntryFingerprint> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
    }

    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }
    public void setUpdatedAtEpochMillis(long updatedAtEpochMillis) {
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }
}
