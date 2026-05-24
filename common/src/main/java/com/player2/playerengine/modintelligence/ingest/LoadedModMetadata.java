package com.player2.playerengine.modintelligence.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoadedModMetadata {
    private String modId;
    private String displayName;
    private String version;
    private String sourceHash;
    private List<String> namespaces = new ArrayList<>();

    public LoadedModMetadata() {}

    public LoadedModMetadata(String modId, String displayName, String version,
                             String sourceHash, List<String> namespaces) {
        this.modId = modId;
        this.displayName = displayName;
        this.version = version;
        this.sourceHash = sourceHash;
        this.namespaces = namespaces == null ? new ArrayList<>() : new ArrayList<>(namespaces);
        Collections.sort(this.namespaces);
    }

    public String getModId() { return modId; }
    public String getDisplayName() { return displayName; }
    public String getVersion() { return version; }
    public String getSourceHash() { return sourceHash; }
    public List<String> getNamespaces() { return namespaces; }
}
