package com.player2.playerengine.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of all {@link ToolDocument} instances.
 *
 * <p>Computes a deterministic SHA-256 version token over the full document set
 * (sorted by id). Any change in document content bumps the token, which triggers
 * an index rebuild in {@link ToolRetriever#loadOrRebuild}.
 *
 * <p>The seed set for Phase B1 is provided by {@link SeedToolMetadata}. Full authoring
 * of all commands is deferred to Phase B2.
 */
public final class ToolMetadataRegistry {

    private final Map<String, ToolDocument> documents;
    private final String versionToken;

    private ToolMetadataRegistry(Map<String, ToolDocument> documents, String versionToken) {
        this.documents = Collections.unmodifiableMap(documents);
        this.versionToken = versionToken;
    }

    /** Creates a registry from the given collection of documents. */
    public static ToolMetadataRegistry create(Collection<ToolDocument> docs) {
        Map<String, ToolDocument> map = new LinkedHashMap<>();
        for (ToolDocument doc : docs) {
            map.put(doc.id(), doc);
        }
        return new ToolMetadataRegistry(map, computeToken(map));
    }

    public Map<String, ToolDocument> documents()         { return documents; }
    public Collection<ToolDocument> values()             { return documents.values(); }
    public ToolDocument getDocument(String id)           { return documents.get(id); }

    /**
     * SHA-256 hex token computed over all documents, sorted by id, using a canonical
     * pipe-delimited representation. Changing any field in any document changes the token.
     */
    public String getVersionToken() { return versionToken; }

    private static String computeToken(Map<String, ToolDocument> docs) {
        TreeMap<String, ToolDocument> sorted = new TreeMap<>(docs);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ToolDocument> entry : sorted.entrySet()) {
            ToolDocument d = entry.getValue();
            sb.append(d.id()).append('=');
            sb.append(d.name()).append('|');
            sb.append(d.description()).append('|');
            sb.append(d.whenToUse()).append('|');
            sb.append(String.join(",", d.examples())).append('|');
            sb.append(String.join(",", d.keywords())).append('|');
            sb.append(String.join(",", d.categoryTags())).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always present in standard Java runtimes
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
