package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;

import java.util.ArrayList;
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
            throw new IllegalArgumentException("schema_version");
        }
        String desc = result.getShortDescription();
        if (desc == null || desc.isBlank() || desc.length() > 240 || desc.contains("\n")) {
            throw new IllegalArgumentException("shortDescription");
        }
        validateStringList(result.getAliases(), 12, 48, true);
        validateStringList(result.getLikelyUses(), 12, 96, false);
        validateStringList(result.getUncertaintyNotes(), 8, 140, false);
        Set<String> allowed = CapabilitySchema.allowedFor(kind);
        if (result.getCapabilityHints() != null) {
            if (result.getCapabilityHints().size() > 8) {
                throw new IllegalArgumentException("capabilityHints_cap");
            }
            for (CapabilityEnrichmentResult.CapabilityHint hint : result.getCapabilityHints()) {
                if (!allowed.contains(hint.getId())) {
                    throw new IllegalArgumentException("unknown_capability:" + hint.getId());
                }
                if (hint.getConfidence() < 0 || hint.getConfidence() > 1 || Double.isNaN(hint.getConfidence())) {
                    throw new IllegalArgumentException("confidence_range");
                }
            }
        }
        return result;
    }

    private static void validateStringList(List<String> list, int maxItems, int maxLen, boolean lowercase)
            throws IllegalArgumentException {
        if (list == null) {
            return;
        }
        if (list.size() > maxItems) {
            throw new IllegalArgumentException("list_cap");
        }
        Set<String> seen = new HashSet<>();
        for (String s : list) {
            if (s == null || s.isBlank() || s.length() > maxLen) {
                throw new IllegalArgumentException("string_bounds");
            }
            for (int i = 0; i < s.length(); i++) {
                if (Character.isISOControl(s.charAt(i))) {
                    throw new IllegalArgumentException("control_char");
                }
            }
            String key = lowercase ? s.toLowerCase().trim() : s.trim();
            seen.add(key);
        }
    }
}
