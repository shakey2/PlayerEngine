package com.player2.playerengine.modintelligence.query;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.ingest.ModIntelligencePaths;
import com.player2.playerengine.retrieval.LexicalIndex;
import com.player2.playerengine.retrieval.MinHashIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RrfFusion;
import com.player2.playerengine.retrieval.ToolDocument;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public final class CapabilityIndex {
    public static final int INDEX_VERSION = 1;

    private static volatile CapabilityIndex active = new CapabilityIndex(List.of(), "");

    private final List<CapabilityDocument> documents;
    private final LexicalIndex lexical;
    private final MinHashIndex minHash;
    private final String versionToken;

    private CapabilityIndex(List<CapabilityDocument> documents, String versionToken) {
        this.documents = List.copyOf(documents);
        this.versionToken = versionToken;
        List<ToolDocument> toolDocs = documents.stream().map(CapabilityDocument::toToolDocument).collect(Collectors.toList());
        this.lexical = LexicalIndex.build(toolDocs);
        this.minHash = MinHashIndex.build(toolDocs);
    }

    public static CapabilityIndex getActive() {
        return active;
    }

    public String getVersionToken() {
        return versionToken;
    }

    public List<CapabilityDocument> getDocuments() {
        return documents;
    }

    public List<RetrievalHit> search(String text, int topK) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<RetrievalHit> bm25 = lexical.query(text, topK * 2);
        List<RetrievalHit> mh = minHash.query(text, topK * 2);
        return RrfFusion.fuse(bm25, mh, topK, 60.0);
    }

    public static void buildAndPersist(Collection<CapabilityMap> maps, String packFingerprint) {
        List<CapabilityDocument> docs = maps.stream()
                .filter(m -> m.getStatus() != CapabilityStatus.TOMBSTONED)
                .map(CapabilityDocument::new)
                .collect(Collectors.toList());
        active = new CapabilityIndex(docs, packFingerprint);
        persistVersionToken(packFingerprint);
    }

    public static void rebuildFromMaps(Collection<CapabilityMap> maps, String packFingerprint) {
        buildAndPersist(maps, packFingerprint);
    }

    public static boolean loadIfMatches(String expectedToken) {
        try {
            if (!ModIntelligencePaths.indexVersionFile().exists()) {
                return false;
            }
            String token = Files.readString(ModIntelligencePaths.indexVersionFile().toPath(),
                    StandardCharsets.UTF_8).trim();
            return expectedToken != null && expectedToken.equals(token);
        } catch (Exception e) {
            PlayerEngine.LOGGER.debug("ModIntelligence index version check failed: {}", e.getMessage());
            return false;
        }
    }

    private static void persistVersionToken(String packFingerprint) {
        try {
            ModIntelligencePaths.indexDir().mkdirs();
            Files.writeString(ModIntelligencePaths.indexVersionFile().toPath(),
                    INDEX_VERSION + ":" + packFingerprint, StandardCharsets.UTF_8);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("ModIntelligence: failed to persist index version: {}", e.getMessage());
        }
    }
}
