package com.player2.playerengine.modintelligence.capability;

import java.util.ArrayList;
import java.util.List;

public final class EnrichmentSummary {
    private int enrichmentSchemaVersion;
    private String promptVersion;
    private String modelRoute;
    private String shortDescription;
    private List<String> aliases = new ArrayList<>();
    private List<String> likelyUses = new ArrayList<>();
    private List<String> uncertaintyNotes = new ArrayList<>();
    private double confidence;
    private long updatedAtEpochMillis;

    public int getEnrichmentSchemaVersion() { return enrichmentSchemaVersion; }
    public void setEnrichmentSchemaVersion(int enrichmentSchemaVersion) {
        this.enrichmentSchemaVersion = enrichmentSchemaVersion;
    }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getModelRoute() { return modelRoute; }
    public void setModelRoute(String modelRoute) { this.modelRoute = modelRoute; }

    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String shortDescription) { this.shortDescription = shortDescription; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) {
        this.aliases = aliases == null ? new ArrayList<>() : new ArrayList<>(aliases);
    }

    public List<String> getLikelyUses() { return likelyUses; }
    public void setLikelyUses(List<String> likelyUses) {
        this.likelyUses = likelyUses == null ? new ArrayList<>() : new ArrayList<>(likelyUses);
    }

    public List<String> getUncertaintyNotes() { return uncertaintyNotes; }
    public void setUncertaintyNotes(List<String> uncertaintyNotes) {
        this.uncertaintyNotes = uncertaintyNotes == null ? new ArrayList<>() : new ArrayList<>(uncertaintyNotes);
    }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }
    public void setUpdatedAtEpochMillis(long updatedAtEpochMillis) {
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }
}
