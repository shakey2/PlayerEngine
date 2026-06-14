package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import com.player2.playerengine.retrieval.LexicalIndex;
import com.player2.playerengine.retrieval.MinHashIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RrfFusion;
import com.player2.playerengine.retrieval.ToolDocument;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Retrieval index over EllieGPS inventory waypoint documents (Part C5, WS2).
 *
 * <p><b>Ownership:</b> this file is owned and filled by <b>WS2</b>.
 *
 * <p>This index is <b>fully isolated</b> from command RAG: it owns its own persistence under
 * {@code <worldRoot>/player2npc/persistentdata/elliegps/index/} and never references
 * {@code ToolMetadataRegistry}, {@code RagIndex}, the overlay system, or
 * {@code modIntelligenceRoot}.
 *
 * <h3>Persistence layout</h3>
 * <pre>
 *   {@code <worldRoot>/player2npc/persistentdata/elliegps/index/}
 *     {@code waypoints.bin}   — EllieGPS-owned {@link PersistedState} (serialized document list)
 *     {@code version.txt}     — Decision 3 version token from {@link EllieGPSStore#versionToken()}
 * </pre>
 *
 * <p>Both indexes are rebuilt from the serialized document list on load, so the {@code PersistedState}
 * does not need to access the package-private {@code State} inner classes of {@code LexicalIndex} /
 * {@code MinHashIndex}. The corpus is tiny; a rebuild from document data is sub-millisecond.
 *
 * <h3>Static current-instance holder</h3>
 * A {@code volatile} static holds the current in-memory index. The counting service reads
 * it lock-free. Cleared ({@code null}) when no world is loaded.
 *
 * <h3>Threading contract</h3>
 * {@link #loadOrRebuild(EllieGPSStore)} and {@link #rebuildFrom(EllieGPSStore)} are called on
 * the server thread. {@link #query(String, int)} may be called from any thread (reads
 * immutable state).
 */
public final class EllieGPSWaypointIndex {

    private static final String BIN_FILE     = "waypoints.bin";
    private static final String VERSION_FILE = "version.txt";
    private static final double RRF_K        = 60.0;

    // -------------------------------------------------------------------------
    // Static current-instance holder (Decision 11)
    // -------------------------------------------------------------------------

    private static volatile EllieGPSWaypointIndex current;

    /** Returns the current active index, or {@code null} when no world is loaded. */
    public static EllieGPSWaypointIndex getCurrent() {
        return current;
    }

    /** Sets the current active index. Called from lifecycle wiring and after rebuilds. */
    public static void setCurrent(EllieGPSWaypointIndex index) {
        current = index;
    }

    // -------------------------------------------------------------------------
    // Instance state (immutable after construction)
    // -------------------------------------------------------------------------

    private final LexicalIndex lexical;
    private final MinHashIndex minHash;
    private final boolean      empty;

    private EllieGPSWaypointIndex(List<ToolDocument> docs) {
        this.empty   = docs.isEmpty();
        this.lexical = LexicalIndex.build(docs);
        this.minHash = MinHashIndex.build(docs);
    }

    // -------------------------------------------------------------------------
    // Pinned public surface
    // -------------------------------------------------------------------------

    /**
     * Loads the index from the persisted bin + version.txt when the token matches the store's
     * current token, otherwise rebuilds from the store's records and persists.
     *
     * <p>Called at world load ({@code SERVER_STARTING}) after the store has been populated.
     * Never throws; failures fall back to an empty index (safe, the store is the source of truth).
     *
     * @param store the loaded {@link EllieGPSStore} for the current world
     * @return the loaded-or-rebuilt index; never null
     */
    public static EllieGPSWaypointIndex loadOrRebuild(EllieGPSStore store) {
        try {
            Path indexDir    = Player2NpcPersistencePaths.ellieGpsIndexDir(store.worldRoot());
            Path binFile     = indexDir.resolve(BIN_FILE);
            Path versionFile = indexDir.resolve(VERSION_FILE);
            String currentToken = store.versionToken();

            if (Files.exists(versionFile) && Files.exists(binFile)) {
                try {
                    String stored = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                    if (currentToken.equals(stored)) {
                        String tokenPrefix = currentToken.length() > 8
                                ? currentToken.substring(0, 8) : currentToken;
                        PlayerEngine.LOGGER.info(
                            "EllieGPS index: token matches ({}...), loading from disk.", tokenPrefix);
                        EllieGPSWaypointIndex loaded = tryLoad(binFile);
                        if (loaded != null) {
                            current = loaded;
                            return loaded;
                        }
                        PlayerEngine.LOGGER.warn(
                            "EllieGPS index: failed to deserialize waypoints.bin — rebuilding.");
                    } else {
                        PlayerEngine.LOGGER.info("EllieGPS index: token changed — rebuilding.");
                    }
                } catch (Exception e) {
                    PlayerEngine.LOGGER.warn(
                        "EllieGPS index: could not read version.txt — rebuilding: {}", e.getMessage());
                }
            } else {
                PlayerEngine.LOGGER.info("EllieGPS index: no persisted index — building for the first time.");
            }
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn(
                "EllieGPS index: loadOrRebuild pre-check failed: {}", e.getMessage());
        }
        return rebuildFrom(store);
    }

    /**
     * Rebuilds the index entirely from the store's current records and persists
     * bin + version.txt. Called after every store mutation (upsert, delete, mark-stale).
     *
     * <p>The corpus is tiny so a full rebuild is always acceptable. Never throws; failures
     * log WARN and return an empty index as a safe fallback.
     *
     * @param store the {@link EllieGPSStore} whose current records are the input corpus
     * @return the rebuilt index; never null
     */
    public static EllieGPSWaypointIndex rebuildFrom(EllieGPSStore store) {
        try {
            long start = System.currentTimeMillis();

            List<PersistedDoc> persistedDocs = store.all().stream()
                    .filter(r -> WaypointTypes.INVENTORY.equals(r.type))
                    .map(r -> new PersistedDoc(new WaypointDocument(r)))
                    .collect(Collectors.toList());

            List<ToolDocument> toolDocs = persistedDocs.stream()
                    .map(PersistedDoc::toToolDocument)
                    .collect(Collectors.toList());

            EllieGPSWaypointIndex idx = new EllieGPSWaypointIndex(toolDocs);

            PlayerEngine.LOGGER.info(
                "EllieGPS index: rebuilt in {}ms ({} inventory waypoints).",
                System.currentTimeMillis() - start, toolDocs.size());

            // Persist bin + version token
            try {
                Path indexDir    = Player2NpcPersistencePaths.ellieGpsIndexDir(store.worldRoot());
                Path binFile     = indexDir.resolve(BIN_FILE);
                Path versionFile = indexDir.resolve(VERSION_FILE);
                Files.createDirectories(indexDir);
                try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(binFile))) {
                    oos.writeObject(new PersistedState(persistedDocs));
                }
                Files.writeString(versionFile, store.versionToken(), StandardCharsets.UTF_8);
                PlayerEngine.LOGGER.info("EllieGPS index: persisted to {}.", indexDir);
            } catch (Exception e) {
                PlayerEngine.LOGGER.warn(
                    "EllieGPS index: could not persist (will rebuild on next launch): {}", e.getMessage());
            }

            current = idx;
            return idx;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn(
                "EllieGPS index: rebuild failed ({}): {} — returning empty index.",
                e.getClass().getSimpleName(), e.getMessage());
            EllieGPSWaypointIndex empty = new EllieGPSWaypointIndex(List.of());
            current = empty;
            return empty;
        }
    }

    /**
     * Queries the index for waypoints matching {@code query} and returns up to {@code limit}
     * results ranked by RRF-fused BM25 + MinHash score.
     *
     * <p>Each {@link RetrievalHit#toolId()} is the waypoint id (i.e. {@link WaypointRecord#id}).
     * Callers filter by dimension, staleness, type, etc.
     *
     * <p>May be called from any thread. Returns an empty list when the index is empty.
     *
     * @param query free-text query string (e.g. the locate_waypoints terms)
     * @param limit maximum number of hits to return
     * @return ranked list of hits; never null
     */
    public List<RetrievalHit> query(String query, int limit) {
        if (empty || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int candidateK = limit * 3;
            List<RetrievalHit> bm25Hits    = lexical.query(query, candidateK);
            List<RetrievalHit> minHashHits = minHash.query(query, candidateK);
            return RrfFusion.fuse(bm25Hits, minHashHits, limit, RRF_K);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS index: query failed: {}", e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static EllieGPSWaypointIndex tryLoad(Path binFile) {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(binFile))) {
            PersistedState ps  = (PersistedState) ois.readObject();
            List<ToolDocument> toolDocs = new ArrayList<>(ps.docs.size());
            for (PersistedDoc pd : ps.docs) {
                toolDocs.add(pd.toToolDocument());
            }
            EllieGPSWaypointIndex idx = new EllieGPSWaypointIndex(toolDocs);
            PlayerEngine.LOGGER.info(
                "EllieGPS index: loaded from disk ({} documents).", toolDocs.size());
            return idx;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS index: load failed ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Serializable state (EllieGPS-owned; NOT shared with ToolRetriever)
    // -------------------------------------------------------------------------

    /**
     * One serialized document entry. Stores only the fields needed to reconstruct a
     * {@link ToolDocument}; the indexes themselves are rebuilt from this data.
     */
    private static final class PersistedDoc implements Serializable {
        private static final long serialVersionUID = 1L;

        final String       id;
        final String       name;
        final String       description;
        final List<String> keywords;
        final List<String> categoryTags;

        PersistedDoc(WaypointDocument doc) {
            this.id           = doc.id();
            this.name         = doc.name();
            this.description  = doc.description();
            this.keywords     = new ArrayList<>(doc.keywords());
            this.categoryTags = new ArrayList<>(doc.categoryTags());
        }

        ToolDocument toToolDocument() {
            return new ToolDocument(id, name, description, "", List.of(), keywords, categoryTags);
        }
    }

    /**
     * Top-level serialized state wrapper written to {@code waypoints.bin}.
     * Holds the list of persisted document entries; indexes are rebuilt on load.
     * This type is EllieGPS-private and must NOT be reused by {@code ToolRetriever}.
     */
    private static final class PersistedState implements Serializable {
        private static final long serialVersionUID = 1L;
        final List<PersistedDoc> docs;

        PersistedState(List<PersistedDoc> docs) {
            this.docs = new ArrayList<>(docs);
        }
    }
}
