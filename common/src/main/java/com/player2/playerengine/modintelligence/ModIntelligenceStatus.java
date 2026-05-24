package com.player2.playerengine.modintelligence;

public final class ModIntelligenceStatus {
    private final boolean enabled;
    private final boolean inspecting;
    private final boolean enriching;
    private final int totalEntries;
    private final int readyEntries;
    private final int partialEntries;
    private final int unknownEntries;
    private final int failedEntries;
    private final int tombstonedEntries;
    private final int queuedEnrichments;
    private final int enrichedEntries;
    private final int enrichmentFailures;
    private final int lastBatchValidated;
    private final int lastBatchFailures;
    private final int lastBatchRemaining;
    private final String activePackFingerprint;
    private final String lastError;

    public ModIntelligenceStatus(boolean enabled, boolean inspecting, boolean enriching,
                                 int totalEntries, int readyEntries, int partialEntries,
                                 int unknownEntries, int failedEntries, int tombstonedEntries,
                                 int queuedEnrichments, int enrichedEntries, int enrichmentFailures,
                                 int lastBatchValidated, int lastBatchFailures, int lastBatchRemaining,
                                 String activePackFingerprint, String lastError) {
        this.enabled = enabled;
        this.inspecting = inspecting;
        this.enriching = enriching;
        this.totalEntries = totalEntries;
        this.readyEntries = readyEntries;
        this.partialEntries = partialEntries;
        this.unknownEntries = unknownEntries;
        this.failedEntries = failedEntries;
        this.tombstonedEntries = tombstonedEntries;
        this.queuedEnrichments = queuedEnrichments;
        this.enrichedEntries = enrichedEntries;
        this.enrichmentFailures = enrichmentFailures;
        this.lastBatchValidated = lastBatchValidated;
        this.lastBatchFailures = lastBatchFailures;
        this.lastBatchRemaining = lastBatchRemaining;
        this.activePackFingerprint = activePackFingerprint;
        this.lastError = lastError;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isInspecting() { return inspecting; }
    public boolean isEnriching() { return enriching; }
    public int getTotalEntries() { return totalEntries; }
    public int getReadyEntries() { return readyEntries; }
    public int getPartialEntries() { return partialEntries; }
    public int getUnknownEntries() { return unknownEntries; }
    public int getFailedEntries() { return failedEntries; }
    public int getTombstonedEntries() { return tombstonedEntries; }
    public int getQueuedEnrichments() { return queuedEnrichments; }
    public int getEnrichedEntries() { return enrichedEntries; }
    public int getEnrichmentFailures() { return enrichmentFailures; }
    public int getLastBatchValidated() { return lastBatchValidated; }
    public int getLastBatchFailures() { return lastBatchFailures; }
    public int getLastBatchRemaining() { return lastBatchRemaining; }
    public String getActivePackFingerprint() { return activePackFingerprint; }
    public String getLastError() { return lastError; }
}
