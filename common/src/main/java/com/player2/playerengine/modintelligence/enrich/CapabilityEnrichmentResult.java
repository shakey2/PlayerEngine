package com.player2.playerengine.modintelligence.enrich;

import java.util.ArrayList;
import java.util.List;

public final class CapabilityEnrichmentResult {
    private int schemaVersion;
    private String shortDescription;
    private List<String> aliases = new ArrayList<>();
    private List<String> likelyUses = new ArrayList<>();
    private List<CapabilityHint> capabilityHints = new ArrayList<>();
    private List<String> uncertaintyNotes = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public String getShortDescription() { return shortDescription; }
    public List<String> getAliases() { return aliases; }
    public List<String> getLikelyUses() { return likelyUses; }
    public List<CapabilityHint> getCapabilityHints() { return capabilityHints; }
    public List<String> getUncertaintyNotes() { return uncertaintyNotes; }

    public static final class CapabilityHint {
        private String id;
        private double confidence;
        private String reason;

        public String getId() { return id; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }
    }
}
