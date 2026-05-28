package com.player2.playerengine.modintelligence.enrich;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityEnrichmentPrompt {
    private static final Gson GSON = new Gson();
    private static final int MAX_TAGS = 64;
    private static final int MAX_PROPS = 40;
    private static final int MAX_CAPS = 20;

    private CapabilityEnrichmentPrompt() {}

    public static String buildUserJson(CapabilityMap map) {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", CapabilityEnrichment.SCHEMA_VERSION);
        o.addProperty("promptVersion", CapabilityEnrichment.PROMPT_VERSION);
        o.addProperty("subjectKind", map.getSubjectKind().name());
        o.addProperty("subjectId", map.getSubjectId());
        o.addProperty("sourceModId", map.getSourceModId());
        o.addProperty("minecraftVersion", map.getMinecraftVersion());
        o.addProperty("loader", map.getLoader());

        List<String> tags = new ArrayList<>(map.getTags());
        Collections.sort(tags);
        if (tags.size() > MAX_TAGS) {
            tags = tags.subList(0, MAX_TAGS);
        }
        o.add("tags", GSON.toJsonTree(tags));

        JsonObject props = new JsonObject();
        int propCount = 0;
        for (var e : map.getStableProperties().entrySet()) {
            if (propCount++ >= MAX_PROPS) break;
            props.addProperty(e.getKey(), e.getValue());
        }
        o.add("stableProperties", props);

        List<CapabilityDecl> caps = new ArrayList<>(map.getCapabilities());
        caps.sort((a, b) -> a.getId().compareTo(b.getId()));
        if (caps.size() > MAX_CAPS) {
            caps = caps.subList(0, MAX_CAPS);
        }
        o.add("capabilitiesSoFar", GSON.toJsonTree(caps.stream().map(c -> {
            JsonObject co = new JsonObject();
            co.addProperty("id", c.getId());
            co.addProperty("confidence", c.getConfidence());
            return co;
        }).toList()));

        o.add("warnings", GSON.toJsonTree(map.getWarnings()));
        o.add("allowedCapabilityIds", GSON.toJsonTree(
                new ArrayList<>(CapabilitySchema.allowedFor(map.getSubjectKind()))));

        return GSON.toJson(o);
    }

    public static String systemPrompt() {
        return """
                You classify Minecraft registry entries for a modpack assistant.
                Respond with a single JSON object only (no markdown fences). Required top-level fields:
                schemaVersion (must be %d), shortDescription, aliases, likelyUses, capabilityHints, uncertaintyNotes.
                Strict length limits (responses that exceed any limit are rejected):
                - shortDescription: 1-%d characters, single line, plain text (no markdown).
                - aliases: 0-%d strings, each 1-%d characters (short search phrases, lowercase ok).
                - likelyUses: 0-%d strings, each 1-%d characters (brief phrases, not full sentences).
                - uncertaintyNotes: 0-%d strings, each 1-%d characters.
                - capabilityHints: 0-%d objects with id, confidence (0.0-1.0), optional reason;
                  each id must be from allowedCapabilityIds in the user message.
                Do not invent safety-sensitive capabilities without evidence in the input.
                """
                .formatted(
                        CapabilityEnrichment.SCHEMA_VERSION,
                        CapabilityEnrichment.MAX_SHORT_DESCRIPTION_LEN,
                        CapabilityEnrichment.MAX_ALIASES,
                        CapabilityEnrichment.MAX_ALIAS_LEN,
                        CapabilityEnrichment.MAX_LIKELY_USES,
                        CapabilityEnrichment.MAX_LIKELY_USE_LEN,
                        CapabilityEnrichment.MAX_UNCERTAINTY_NOTES,
                        CapabilityEnrichment.MAX_UNCERTAINTY_NOTE_LEN,
                        CapabilityEnrichment.MAX_CAPABILITY_HINTS)
                .stripIndent();
    }
}
