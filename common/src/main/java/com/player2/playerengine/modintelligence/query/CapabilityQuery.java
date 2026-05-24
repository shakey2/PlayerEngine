package com.player2.playerengine.modintelligence.query;

import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class CapabilityQuery {
    private String text = "";
    private Set<CapabilitySubjectKind> subjectKinds = EnumSet.allOf(CapabilitySubjectKind.class);
    private Set<String> requiredCapabilities = new HashSet<>();
    private Set<String> excludedCapabilities = new HashSet<>();
    private double minConfidence = 0.5;
    private Set<CapabilityStatus> allowedStatuses = EnumSet.of(
            CapabilityStatus.READY, CapabilityStatus.PARTIAL);
    private boolean requireDeterministicEvidence = false;
    private boolean includePartial = true;
    private boolean includeUnknown = false;
    private boolean includeTombstoned = false;

    public static CapabilityQuery defaults() {
        return new CapabilityQuery();
    }

    public String getText() { return text; }
    public CapabilityQuery setText(String text) {
        this.text = text == null ? "" : text;
        return this;
    }

    public Set<CapabilitySubjectKind> getSubjectKinds() { return subjectKinds; }
    public CapabilityQuery setSubjectKinds(Set<CapabilitySubjectKind> subjectKinds) {
        this.subjectKinds = subjectKinds == null ? EnumSet.allOf(CapabilitySubjectKind.class) : subjectKinds;
        return this;
    }

    public Set<String> getRequiredCapabilities() { return requiredCapabilities; }
    public CapabilityQuery setRequiredCapabilities(Set<String> requiredCapabilities) {
        this.requiredCapabilities = requiredCapabilities == null ? new HashSet<>() : requiredCapabilities;
        return this;
    }

    public Set<String> getExcludedCapabilities() { return excludedCapabilities; }
    public CapabilityQuery setExcludedCapabilities(Set<String> excludedCapabilities) {
        this.excludedCapabilities = excludedCapabilities == null ? new HashSet<>() : excludedCapabilities;
        return this;
    }

    public double getMinConfidence() { return minConfidence; }
    public CapabilityQuery setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
        return this;
    }

    public Set<CapabilityStatus> getAllowedStatuses() { return allowedStatuses; }
    public CapabilityQuery setAllowedStatuses(Set<CapabilityStatus> allowedStatuses) {
        this.allowedStatuses = allowedStatuses;
        return this;
    }

    public boolean isRequireDeterministicEvidence() { return requireDeterministicEvidence; }
    public CapabilityQuery setRequireDeterministicEvidence(boolean requireDeterministicEvidence) {
        this.requireDeterministicEvidence = requireDeterministicEvidence;
        return this;
    }

    public boolean isIncludePartial() { return includePartial; }
    public boolean isIncludeUnknown() { return includeUnknown; }
    public boolean isIncludeTombstoned() { return includeTombstoned; }

    public CapabilityQuery setIncludeUnknown(boolean includeUnknown) {
        this.includeUnknown = includeUnknown;
        return this;
    }

    public CapabilityQuery setIncludeTombstoned(boolean includeTombstoned) {
        this.includeTombstoned = includeTombstoned;
        return this;
    }
}
