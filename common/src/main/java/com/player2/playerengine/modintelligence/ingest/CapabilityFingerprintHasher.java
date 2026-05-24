package com.player2.playerengine.modintelligence.ingest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichment;
import com.player2.playerengine.modintelligence.inspect.CapabilityHeuristics;
import com.player2.playerengine.modintelligence.inspect.CapabilityInspectors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CapabilityFingerprintHasher {
    private static final Gson GSON = new Gson();

    private CapabilityFingerprintHasher() {}

    public static String sha256(String prefix, JsonObject canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((prefix + ":" + GSON.toJson(canonical)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:").append(prefix).append(":");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256:" + prefix + ":error";
        }
    }

    public static String deterministicInputHash(CapabilitySubjectKind kind, String subjectId, String sourceModId,
                                                String modSourceHash, String minecraftVersion, String loader,
                                                List<String> tags, Map<String, String> stableProperties,
                                                Map<String, String> sanitizedNbt) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", CapabilitySchema.VERSION);
        o.addProperty("inspector", CapabilityInspectors.INSPECTOR_VERSION);
        o.addProperty("heuristic", CapabilityHeuristics.HEURISTIC_VERSION);
        o.addProperty("mc", minecraftVersion);
        o.addProperty("loader", loader);
        o.addProperty("kind", kind.name());
        o.addProperty("subjectId", subjectId.toLowerCase());
        o.addProperty("sourceModId", sourceModId.toLowerCase());
        o.addProperty("modHash", modSourceHash);
        o.add("tags", sortedArray(tags));
        o.add("props", sortedObject(stableProperties));
        o.add("nbt", sortedObject(sanitizedNbt));
        return sha256("b4-entry-det-v1", o);
    }

    public static String enrichmentInputHash(String deterministicHash, CapabilityMap map) {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", CapabilityEnrichment.PROMPT_VERSION);
        o.addProperty("deterministic", deterministicHash);
        o.add("tags", sortedArray(map.getTags()));
        o.add("props", sortedObject(map.getStableProperties()));
        o.add("capabilities", capabilitySummaries(map));
        return sha256("b4-entry-enr-v1", o);
    }

    public static String entryFingerprint(String deterministicHash, String enrichmentHash) {
        JsonObject o = new JsonObject();
        o.addProperty("det", deterministicHash);
        o.addProperty("enr", enrichmentHash);
        return sha256("b4-entry-v1", o);
    }

    public static String packFingerprint(String minecraftVersion, String loader,
                                         Map<String, ModFingerprint> mods) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", CapabilitySchema.VERSION);
        o.addProperty("mc", minecraftVersion);
        o.addProperty("loader", loader);
        JsonObject modsObj = new JsonObject();
        List<String> modIds = new ArrayList<>(mods.keySet());
        Collections.sort(modIds);
        for (String modId : modIds) {
            ModFingerprint m = mods.get(modId);
            JsonObject mo = new JsonObject();
            mo.addProperty("version", m.getVersion());
            mo.addProperty("sourceHash", m.getSourceHash());
            modsObj.add(modId, mo);
        }
        o.add("mods", modsObj);
        return sha256("b4-pack-v1", o);
    }

    private static JsonArray capabilitySummaries(CapabilityMap map) {
        JsonArray arr = new JsonArray();
        List<CapabilityDecl> caps = new ArrayList<>(map.getCapabilities());
        caps.sort((a, b) -> a.getId().compareTo(b.getId()));
        for (CapabilityDecl c : caps) {
            JsonObject co = new JsonObject();
            co.addProperty("id", c.getId());
            co.addProperty("confidence", c.getConfidence());
            arr.add(co);
        }
        return arr;
    }

    private static JsonArray sortedArray(List<String> values) {
        JsonArray arr = new JsonArray();
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        Collections.sort(copy);
        for (String v : copy) {
            arr.add(v.toLowerCase());
        }
        return arr;
    }

    private static JsonObject sortedObject(Map<String, String> map) {
        JsonObject o = new JsonObject();
        TreeMap<String, String> sorted = new TreeMap<>();
        if (map != null) {
            sorted.putAll(map);
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }
}
