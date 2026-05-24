package com.player2.playerengine.modintelligence.enrich;

import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidence;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidenceKind;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.EnrichmentSummary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CapabilityEnrichmentMerger {
    private CapabilityEnrichmentMerger() {}

    public static void merge(CapabilityMap map, CapabilityEnrichmentResult result) {
        EnrichmentSummary summary = new EnrichmentSummary();
        summary.setEnrichmentSchemaVersion(CapabilityEnrichment.SCHEMA_VERSION);
        summary.setPromptVersion(CapabilityEnrichment.PROMPT_VERSION);
        summary.setModelRoute("default");
        summary.setShortDescription(result.getShortDescription());
        summary.setAliases(result.getAliases());
        summary.setLikelyUses(result.getLikelyUses());
        summary.setUncertaintyNotes(result.getUncertaintyNotes());
        summary.setConfidence(0.5);
        summary.setUpdatedAtEpochMillis(System.currentTimeMillis());
        map.setEnrichment(summary);

        Set<String> existing = new HashSet<>();
        for (CapabilityDecl decl : map.getCapabilities()) {
            existing.add(decl.getId());
        }

        if (result.getCapabilityHints() == null) {
            return;
        }

        for (CapabilityEnrichmentResult.CapabilityHint hint : result.getCapabilityHints()) {
            double clamped = Math.min(hint.getConfidence(), 0.7);
            if (CapabilitySchema.SAFETY_SENSITIVE.contains(hint.getId()) && !existing.contains(hint.getId())) {
                map.getWarnings().add("enrichment_safety_hint_only:" + hint.getId());
                continue;
            }
            if (existing.contains(hint.getId())) {
                for (CapabilityDecl decl : map.getCapabilities()) {
                    if (decl.getId().equals(hint.getId())) {
                        decl.getEvidence().add(new CapabilityEvidence(
                                CapabilityEvidenceKind.ENRICHMENT, "player2", hint.getReason(),
                                clamped, "Enrichment agrees with existing capability"));
                    }
                }
                continue;
            }
            List<CapabilityEvidence> evidence = new ArrayList<>();
            evidence.add(new CapabilityEvidence(CapabilityEvidenceKind.ENRICHMENT, "player2",
                    hint.getReason(), clamped, "Enrichment-only hint"));
            map.getCapabilities().add(new CapabilityDecl(hint.getId(), "enrichment", clamped,
                    java.util.Map.of(), evidence));
        }
    }
}
