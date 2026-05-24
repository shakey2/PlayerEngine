package com.player2.playerengine.modintelligence.ingest;

public final class EntryFingerprint {
    private String subjectKind;
    private String subjectId;
    private String sourceModId;
    private String fingerprint;
    private String deterministicInputHash;
    private String enrichmentInputHash;
    private String capabilityStatus;
    private boolean enriched;

    public String getSubjectKind() { return subjectKind; }
    public void setSubjectKind(String subjectKind) { this.subjectKind = subjectKind; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }

    public String getSourceModId() { return sourceModId; }
    public void setSourceModId(String sourceModId) { this.sourceModId = sourceModId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getDeterministicInputHash() { return deterministicInputHash; }
    public void setDeterministicInputHash(String deterministicInputHash) {
        this.deterministicInputHash = deterministicInputHash;
    }

    public String getEnrichmentInputHash() { return enrichmentInputHash; }
    public void setEnrichmentInputHash(String enrichmentInputHash) {
        this.enrichmentInputHash = enrichmentInputHash;
    }

    public String getCapabilityStatus() { return capabilityStatus; }
    public void setCapabilityStatus(String capabilityStatus) {
        this.capabilityStatus = capabilityStatus;
    }

    public boolean isEnriched() { return enriched; }
    public void setEnriched(boolean enriched) { this.enriched = enriched; }

    public String entryKey() {
        return subjectKind + "|" + subjectId;
    }
}
