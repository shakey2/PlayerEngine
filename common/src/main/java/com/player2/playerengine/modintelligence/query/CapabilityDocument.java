package com.player2.playerengine.modintelligence.query;

import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.retrieval.ToolDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class CapabilityDocument {
    private final String id;
    private final String subjectId;
    private final CapabilitySubjectKind kind;
    private final CapabilityStatus status;
    private final List<String> capabilityIds;
    private final double bestConfidence;
    private final String indexedText;
    private final List<String> filterTags;
    private final CapabilityMap source;

    public CapabilityDocument(CapabilityMap map) {
        this.source = map;
        this.kind = map.getSubjectKind();
        this.subjectId = map.getSubjectId();
        this.id = map.documentId();
        this.status = map.getStatus();
        this.capabilityIds = map.getCapabilities().stream().map(CapabilityDecl::getId).collect(Collectors.toList());
        this.bestConfidence = map.bestCapabilityConfidence();
        this.indexedText = buildIndexedText(map);
        this.filterTags = buildFilterTags(map);
    }

    public String id() { return id; }
    public String subjectId() { return subjectId; }
    public CapabilitySubjectKind kind() { return kind; }
    public CapabilityStatus status() { return status; }
    public List<String> capabilityIds() { return capabilityIds; }
    public double bestConfidence() { return bestConfidence; }
    public String indexedText() { return indexedText; }
    public List<String> filterTags() { return filterTags; }
    public CapabilityMap source() { return source; }

    public ToolDocument toToolDocument() {
        return new ToolDocument(id, subjectId, indexedText, "", List.of(), List.of(), filterTags);
    }

    private static String buildIndexedText(CapabilityMap map) {
        StringBuilder sb = new StringBuilder();
        sb.append(map.getSubjectId().replace(':', ' ')).append(' ');
        for (String cap : map.getCapabilities().stream().map(CapabilityDecl::getId).toList()) {
            sb.append(cap).append(' ');
        }
        for (String tag : map.getTags()) {
            sb.append(tag.replace(':', ' ')).append(' ');
        }
        for (String k : map.getStableProperties().keySet()) {
            sb.append(k).append(' ');
            sb.append(map.getStableProperties().get(k)).append(' ');
        }
        if (map.getEnrichment() != null) {
            if (map.getEnrichment().getShortDescription() != null) {
                sb.append(map.getEnrichment().getShortDescription()).append(' ');
            }
            for (String alias : map.getEnrichment().getAliases()) {
                sb.append(alias).append(' ');
            }
            for (String use : map.getEnrichment().getLikelyUses()) {
                sb.append(use).append(' ');
            }
        }
        return sb.toString();
    }

    private static List<String> buildFilterTags(CapabilityMap map) {
        List<String> tags = new ArrayList<>();
        tags.add(map.getSubjectKind().name().toLowerCase());
        tags.add(map.getSourceModId());
        tags.addAll(map.getCapabilities().stream().map(CapabilityDecl::getId).toList());
        return tags;
    }
}
