package com.player2.playerengine.retrieval;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Facade for on-device NPC command retrieval.
 *
 * <p>Combines {@link LexicalIndex} (BM25) and {@link MinHashIndex} (character 4-gram
 * MinHash/LSH), fusing results with {@link RrfFusion} (k=60). Category post-filtering
 * is available via {@link #retrieve(String, int, Set)}.
 *
 * <p><b>No Player2 API call is made at any point.</b> Index build, load, and retrieval
 * are entirely on-device.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Call {@link #loadOrRebuild(ToolMetadataRegistry, Path)} at mod init (after
 *       {@code PlayerEngineCommands.init}). This checks {@code indexDir/version.txt};
 *       if the token matches the registry, the pre-built index is loaded from
 *       {@code indexDir/tools.bin}. If the token is absent or mismatched, or if the
 *       serialized state cannot be read, the index is rebuilt and persisted.</li>
 *   <li>Call {@link #retrieve} to query the index.</li>
 * </ol>
 */
public final class ToolRetriever {

    private static final Logger LOGGER = LogManager.getLogger(ToolRetriever.class);

    private static final int    CANDIDATE_MULTIPLIER = 3;
    private static final double RRF_K                = 60.0;

    private final ToolMetadataRegistry registry;
    private final LexicalIndex         lexicalIndex;
    private final MinHashIndex         minHashIndex;

    private ToolRetriever(ToolMetadataRegistry registry,
                          LexicalIndex lexicalIndex,
                          MinHashIndex minHashIndex) {
        this.registry     = registry;
        this.lexicalIndex = lexicalIndex;
        this.minHashIndex = minHashIndex;
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Builds an in-memory retriever for {@code registry} without touching disk.
     *
     * <p>Used by {@code RagIndex} for per-owner retrievers whose document sets differ from
     * the global retriever. The global retriever still uses {@link #loadOrRebuild} so its
     * index survives restarts without a rebuild cost.
     *
     * <p>Caller note: this method returns immediately; build time at ~30 documents is
     * sub-millisecond. At thousand-document scales (Phase D territory) consider off-thread.
     */
    public static ToolRetriever buildInMemory(ToolMetadataRegistry registry) {
        long start = System.currentTimeMillis();
        LexicalIndex lex = LexicalIndex.build(registry.values());
        MinHashIndex mh  = MinHashIndex.build(registry.values());
        LOGGER.debug("RAG: in-memory retriever built in {}ms ({} documents).",
                System.currentTimeMillis() - start, registry.documents().size());
        return new ToolRetriever(registry, lex, mh);
    }

    /**
     * Loads the index from {@code indexDir} when the stored version token matches the
     * registry, otherwise rebuilds and persists. Falls back to rebuild if the persisted
     * state cannot be deserialized.
     */
    public static ToolRetriever loadOrRebuild(ToolMetadataRegistry registry, Path indexDir) {
        Path versionFile = indexDir.resolve("version.txt");
        Path toolsBin    = indexDir.resolve("tools.bin");
        String current   = registry.getVersionToken();

        if (Files.exists(versionFile) && Files.exists(toolsBin)) {
            try {
                String stored = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                if (current.equals(stored)) {
                    LOGGER.info("RAG: version token matches ({}...), loading index from disk.",
                            current.substring(0, 8));
                    ToolRetriever loaded = tryLoad(registry, toolsBin);
                    if (loaded != null) return loaded;
                    LOGGER.warn("RAG: failed to deserialize tools.bin — rebuilding.");
                } else {
                    LOGGER.info("RAG: version token changed ({}... → {}...) — rebuilding.",
                            stored.substring(0, Math.min(8, stored.length())),
                            current.substring(0, 8));
                }
            } catch (IOException e) {
                LOGGER.warn("RAG: could not read version.txt — rebuilding: {}", e.getMessage());
            }
        } else {
            LOGGER.info("RAG: no persisted index found — building for the first time.");
        }

        return buildAndPersist(registry, indexDir);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Retrieves up to {@code topK} tools relevant to {@code goal} using BM25 + MinHash/LSH
     * fused via RRF. No category filter is applied.
     */
    public List<RetrievalHit> retrieve(String goal, int topK) {
        return retrieve(goal, topK, null);
    }

    /**
     * Retrieves up to {@code topK} tools relevant to {@code goal}.
     * If {@code categoryFilter} is non-null and non-empty, only tools tagged with at least
     * one matching tag are included (post-filter over the RRF result set).
     */
    public List<RetrievalHit> retrieve(String goal, int topK, Set<String> categoryFilter) {
        int candidateK = topK * CANDIDATE_MULTIPLIER;
        List<RetrievalHit> bm25    = lexicalIndex.query(goal, candidateK);
        List<RetrievalHit> minHash = minHashIndex.query(goal, candidateK);
        List<RetrievalHit> fused   = RrfFusion.fuse(bm25, minHash, candidateK, RRF_K);

        if (categoryFilter != null && !categoryFilter.isEmpty()) {
            List<RetrievalHit> filtered = new ArrayList<>();
            for (RetrievalHit hit : fused) {
                ToolDocument doc = registry.getDocument(hit.toolId());
                if (doc != null && !Collections.disjoint(doc.categoryTags(), categoryFilter)) {
                    filtered.add(hit);
                }
                if (filtered.size() >= topK) break;
            }
            return filtered;
        }

        return fused.size() <= topK ? fused : fused.subList(0, topK);
    }

    /** Returns the number of documents registered. */
    public int documentCount() { return registry.documents().size(); }

    /** Returns the underlying {@link ToolMetadataRegistry} for this retriever. */
    public ToolMetadataRegistry getRegistry() { return registry; }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static ToolRetriever tryLoad(ToolMetadataRegistry registry, Path toolsBin) {
        try (ObjectInputStream ois =
                     new ObjectInputStream(Files.newInputStream(toolsBin))) {
            PersistedState ps  = (PersistedState) ois.readObject();
            LexicalIndex   lex = LexicalIndex.fromState(ps.lexState);
            MinHashIndex   mh  = MinHashIndex.fromState(ps.mhState);
            LOGGER.info("RAG: loaded index from disk ({} documents).", ps.lexState.docIds.length);
            return new ToolRetriever(registry, lex, mh);
        } catch (Exception e) {
            LOGGER.warn("RAG: load failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static ToolRetriever buildAndPersist(ToolMetadataRegistry registry, Path indexDir) {
        long start = System.currentTimeMillis();
        LexicalIndex lex = LexicalIndex.build(registry.values());
        MinHashIndex mh  = MinHashIndex.build(registry.values());
        LOGGER.info("RAG: index built in {}ms ({} documents).",
                System.currentTimeMillis() - start, registry.documents().size());

        try {
            Files.createDirectories(indexDir);
            try (ObjectOutputStream oos =
                         new ObjectOutputStream(Files.newOutputStream(indexDir.resolve("tools.bin")))) {
                oos.writeObject(new PersistedState(lex.getState(), mh.getState()));
            }
            Files.writeString(indexDir.resolve("version.txt"),
                    registry.getVersionToken(), StandardCharsets.UTF_8);
            LOGGER.info("RAG: index persisted to {}.", indexDir);
        } catch (IOException e) {
            LOGGER.warn("RAG: could not persist index (will rebuild on next launch): {}",
                    e.getMessage());
        }

        return new ToolRetriever(registry, lex, mh);
    }

    // -------------------------------------------------------------------------
    // Serializable state wrapper
    // -------------------------------------------------------------------------

    /** Wrapper persisted to {@code tools.bin}. Holds the state of both sub-indexes. */
    private static final class PersistedState implements Serializable {
        private static final long serialVersionUID = 1L;
        final LexicalIndex.State lexState;
        final MinHashIndex.State mhState;

        PersistedState(LexicalIndex.State lexState, MinHashIndex.State mhState) {
            this.lexState = lexState;
            this.mhState  = mhState;
        }
    }
}
