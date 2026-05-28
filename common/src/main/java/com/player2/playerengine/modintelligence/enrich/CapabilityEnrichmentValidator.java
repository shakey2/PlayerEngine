package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CapabilityEnrichmentValidator {
    private static final Gson GSON = new Gson();

    private CapabilityEnrichmentValidator() {}

    public static CapabilityEnrichmentResult parseAndValidate(String json, CapabilitySubjectKind kind)
            throws IllegalArgumentException {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty_response");
        }
        String trimmed = json.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
        CapabilityEnrichmentResult result = GSON.fromJson(root, CapabilityEnrichmentResult.class);
        if (result == null) {
            throw new IllegalArgumentException("null_result");
        }
        if (result.getSchemaVersion() != CapabilityEnrichment.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schema_version:got=" + result.getSchemaVersion()
                            + ":expected=" + CapabilityEnrichment.SCHEMA_VERSION);
        }
        validateShortDescription(result.getShortDescription());
        validateStringList(
                "aliases",
                result.getAliases(),
                CapabilityEnrichment.MAX_ALIASES,
                CapabilityEnrichment.MAX_ALIAS_LEN);
        validateStringList(
                "likelyUses",
                result.getLikelyUses(),
                CapabilityEnrichment.MAX_LIKELY_USES,
                CapabilityEnrichment.MAX_LIKELY_USE_LEN);
        validateStringList(
                "uncertaintyNotes",
                result.getUncertaintyNotes(),
                CapabilityEnrichment.MAX_UNCERTAINTY_NOTES,
                CapabilityEnrichment.MAX_UNCERTAINTY_NOTE_LEN);
        Set<String> allowed = CapabilitySchema.allowedFor(kind);
        if (result.getCapabilityHints() != null) {
            if (result.getCapabilityHints().size() > CapabilityEnrichment.MAX_CAPABILITY_HINTS) {
                throw new IllegalArgumentException(
                        "capabilityHints_cap:size=" + result.getCapabilityHints().size()
                                + ":max=" + CapabilityEnrichment.MAX_CAPABILITY_HINTS);
            }
            for (int i = 0; i < result.getCapabilityHints().size(); i++) {
                CapabilityEnrichmentResult.CapabilityHint hint = result.getCapabilityHints().get(i);
                if (hint.getId() == null || !allowed.contains(hint.getId())) {
                    throw new IllegalArgumentException(
                            "unknown_capability:capabilityHints[" + i + "]:id="
                                    + (hint.getId() == null ? "null" : hint.getId()));
                }
                if (hint.getConfidence() < 0 || hint.getConfidence() > 1 || Double.isNaN(hint.getConfidence())) {
                    throw new IllegalArgumentException(
                            "confidence_range:capabilityHints[" + i + "]:id=" + hint.getId()
                                    + ":confidence=" + hint.getConfidence());
                }
            }
        }
        return result;
    }

    private static void validateShortDescription(String desc) {
        if (desc == null || desc.isBlank()) {
            throw new IllegalArgumentException("shortDescription:blank");
        }
        if (desc.contains("\n") || desc.contains("\r")) {
            throw new IllegalArgumentException("shortDescription:newline");
        }
        if (desc.length() > CapabilityEnrichment.MAX_SHORT_DESCRIPTION_LEN) {
            throw new IllegalArgumentException(
                    "shortDescription:len=" + desc.length()
                            + ":max=" + CapabilityEnrichment.MAX_SHORT_DESCRIPTION_LEN);
        }
    }

    private static void validateStringList(String field, List<String> list, int maxItems, int maxLen) {
        if (list == null) {
            return;
        }
        if (list.size() > maxItems) {
            throw new IllegalArgumentException("list_cap:" + field + ":size=" + list.size() + ":max=" + maxItems);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s == null) {
                throw new IllegalArgumentException("string_bounds:" + field + "[" + i + "]:null:maxLen=" + maxLen);
            }
            if (s.isBlank()) {
                throw new IllegalArgumentException("string_bounds:" + field + "[" + i + "]:blank:maxLen=" + maxLen);
            }
            if (s.length() > maxLen) {
                throw new IllegalArgumentException(
                        "string_bounds:" + field + "[" + i + "]:len=" + s.length() + ":max=" + maxLen);
            }
            for (int c = 0; c < s.length(); c++) {
                if (Character.isISOControl(s.charAt(c))) {
                    throw new IllegalArgumentException(
                            "control_char:" + field + "[" + i + "]:code=" + (int) s.charAt(c));
                }
            }
            seen.add(s.trim().toLowerCase());
        }
    }
}
