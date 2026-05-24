package com.player2.playerengine.modintelligence.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapabilityDecl {
    private String id;
    private String category;
    private double confidence;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private List<CapabilityEvidence> evidence = new ArrayList<>();

    public CapabilityDecl() {}

    public CapabilityDecl(String id, String category, double confidence,
                          Map<String, String> attributes, List<CapabilityEvidence> evidence) {
        this.id = id;
        this.category = category;
        this.confidence = confidence;
        if (attributes != null) {
            this.attributes = new LinkedHashMap<>(attributes);
        }
        if (evidence != null) {
            this.evidence = new ArrayList<>(evidence);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    public List<CapabilityEvidence> getEvidence() { return evidence; }
    public void setEvidence(List<CapabilityEvidence> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }
}
