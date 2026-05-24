package com.player2.playerengine.modintelligence.ingest;

import java.util.ArrayList;
import java.util.List;

public final class ModFingerprint {
    private String modId;
    private String displayName;
    private String version;
    private String sourceHash;
    private String metadataHash;
    private List<String> namespaces = new ArrayList<>();

    public String getModId() { return modId; }
    public void setModId(String modId) { this.modId = modId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    public String getMetadataHash() { return metadataHash; }
    public void setMetadataHash(String metadataHash) { this.metadataHash = metadataHash; }

    public List<String> getNamespaces() { return namespaces; }
    public void setNamespaces(List<String> namespaces) {
        this.namespaces = namespaces == null ? new ArrayList<>() : new ArrayList<>(namespaces);
    }
}
