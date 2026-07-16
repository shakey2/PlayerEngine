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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Rebuildable local retrieval index over supported inventory and farm waypoint documents. */
public final class EllieGPSWaypointIndex {

    private static final String BIN_FILE = "waypoints.bin";
    private static final String VERSION_FILE = "version.txt";
    private static final double RRF_K = 60.0;

    private static volatile EllieGPSWaypointIndex current;

    private static final WaypointIndexBackend PRODUCTION_BACKEND = new WaypointIndexBackend() {
        @Override
        public boolean isHealthyFor(String expectedVersionToken) {
            EllieGPSWaypointIndex active = current;
            return active != null
                    && active.healthy
                    && active.versionToken.equals(expectedVersionToken);
        }

        @Override
        public WaypointIndexUpdateStatus synchronize(EllieGPSStore store) {
            return rebuildChecked(store);
        }

        @Override
        public List<RetrievalHit> query(String query, int limit) {
            EllieGPSWaypointIndex active = current;
            if (active == null || !active.healthy) {
                throw new IllegalStateException("EllieGPS waypoint index is not synchronized");
            }
            return active.query(query, limit);
        }
    };

    private final LexicalIndex lexical;
    private final MinHashIndex minHash;
    private final boolean empty;
    private final String versionToken;
    private final boolean healthy;
    private final int documentCount;

    private EllieGPSWaypointIndex(
            List<ToolDocument> documents,
            String versionToken,
            boolean healthy) {
        this.empty = documents.isEmpty();
        this.lexical = LexicalIndex.build(documents);
        this.minHash = MinHashIndex.build(documents);
        this.versionToken = versionToken == null ? "" : versionToken;
        this.healthy = healthy;
        this.documentCount = documents.size();
    }

    static WaypointIndexBackend productionBackend() {
        return PRODUCTION_BACKEND;
    }

    public static EllieGPSWaypointIndex getCurrent() {
        return current;
    }

    /** Existing lifecycle API retained for shutdown wiring. */
    public static void setCurrent(EllieGPSWaypointIndex index) {
        current = index;
    }

    /** Loads a token-matched persisted index or performs a checked rebuild. Never returns null. */
    public static EllieGPSWaypointIndex loadOrRebuild(EllieGPSStore store) {
        String expectedToken = store.versionToken();
        try {
            Path indexDir = Player2NpcPersistencePaths.ellieGpsIndexDir(store.worldRoot());
            Path binary = indexDir.resolve(BIN_FILE);
            Path version = indexDir.resolve(VERSION_FILE);
            if (Files.exists(binary) && Files.exists(version)) {
                String persistedToken = Files.readString(version, StandardCharsets.UTF_8).trim();
                if (expectedToken.equals(persistedToken)) {
                    EllieGPSWaypointIndex loaded = tryLoad(binary, expectedToken);
                    if (loaded != null) {
                        current = loaded;
                        PlayerEngine.LOGGER.info(
                                "EllieGPS index: loaded token-matched index ({} documents).",
                                loaded.documentCountHint());
                        return loaded;
                    }
                }
            }
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS index: persisted load failed; rebuilding: {}", e.getMessage());
        }

        WaypointIndexUpdateStatus status = rebuildChecked(store);
        if (status == WaypointIndexUpdateStatus.INDEX_FAILED
                && (current == null || !current.versionToken.equals(expectedToken))) {
            current = unhealthyEmpty(expectedToken);
        }
        return current;
    }

    /** Existing compatibility API; callers needing truth use {@link WaypointIndexBackend}. */
    public static EllieGPSWaypointIndex rebuildFrom(EllieGPSStore store) {
        String expectedToken = store.versionToken();
        WaypointIndexUpdateStatus status = rebuildChecked(store);
        if (status == WaypointIndexUpdateStatus.INDEX_FAILED
                && (current == null || !current.versionToken.equals(expectedToken))) {
            current = unhealthyEmpty(expectedToken);
        }
        return current;
    }

    /** Queries this immutable index. A known-unhealthy instance never serves stale documents. */
    public List<RetrievalHit> query(String query, int limit) {
        if (!healthy || empty || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            int candidateLimit = Math.max(limit, Math.multiplyExact(limit, 3));
            List<RetrievalHit> lexicalHits = lexical.query(query, candidateLimit);
            List<RetrievalHit> minHashHits = minHash.query(query, candidateLimit);
            return RrfFusion.fuse(lexicalHits, minHashHits, limit, RRF_K);
        } catch (Exception e) {
            throw new IllegalStateException("EllieGPS waypoint index query failed", e);
        }
    }

    private static synchronized WaypointIndexUpdateStatus rebuildChecked(EllieGPSStore store) {
        String expectedToken = store.versionToken();
        try {
            long started = System.currentTimeMillis();
            ArrayList<PersistedDoc> persistedDocuments = new ArrayList<>();
            ArrayList<ToolDocument> toolDocuments = new ArrayList<>();
            for (WaypointRecord record : store.all()) {
                if (!isSupportedIndexable(record)) {
                    continue;
                }
                WaypointDocument document = new WaypointDocument(record);
                PersistedDoc persisted = new PersistedDoc(document);
                persistedDocuments.add(persisted);
                toolDocuments.add(persisted.toToolDocument());
            }

            EllieGPSWaypointIndex candidate =
                    new EllieGPSWaypointIndex(toolDocuments, expectedToken, true);
            persistCandidate(store.worldRoot(), persistedDocuments, expectedToken);
            current = candidate;
            PlayerEngine.LOGGER.info(
                    "EllieGPS index: synchronized in {}ms ({} waypoint documents).",
                    System.currentTimeMillis() - started,
                    toolDocuments.size());
            return WaypointIndexUpdateStatus.INDEX_COMMITTED;
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn(
                    "EllieGPS index: synchronization failed ({}): {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return WaypointIndexUpdateStatus.INDEX_FAILED;
        }
    }

    private static boolean isSupportedIndexable(WaypointRecord record) {
        if (WaypointTypes.INVENTORY.equals(record.type)) {
            return record.inventoryData() != null;
        }
        if (WaypointTypes.FARM.equals(record.type)) {
            FarmWaypointData farm = record.farmData();
            return farm != null && farm.isSupportedVersion();
        }
        return false;
    }

    private static void persistCandidate(
            Path worldRoot,
            List<PersistedDoc> documents,
            String versionToken) throws Exception {
        Path indexDir = Player2NpcPersistencePaths.ellieGpsIndexDir(worldRoot);
        Path binary = indexDir.resolve(BIN_FILE);
        Path version = indexDir.resolve(VERSION_FILE);
        Path binaryTmp = indexDir.resolve(BIN_FILE + ".tmp");
        Path versionTmp = indexDir.resolve(VERSION_FILE + ".tmp");
        Files.createDirectories(indexDir);

        try (ObjectOutputStream output =
                     new ObjectOutputStream(Files.newOutputStream(binaryTmp))) {
            output.writeObject(new PersistedState(documents));
        }
        Files.writeString(versionTmp, versionToken, StandardCharsets.UTF_8);
        atomicReplace(binaryTmp, binary);
        atomicReplace(versionTmp, version);
    }

    private static EllieGPSWaypointIndex tryLoad(Path binary, String versionToken) {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(binary))) {
            PersistedState persisted = (PersistedState) input.readObject();
            ArrayList<ToolDocument> documents = new ArrayList<>(persisted.docs.size());
            for (PersistedDoc document : persisted.docs) {
                documents.add(document.toToolDocument());
            }
            return new EllieGPSWaypointIndex(documents, versionToken, true);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn(
                    "EllieGPS index: load failed ({}): {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static EllieGPSWaypointIndex unhealthyEmpty(String token) {
        return new EllieGPSWaypointIndex(List.of(), token, false);
    }

    private int documentCountHint() {
        return documentCount;
    }

    private static void atomicReplace(Path temporary, Path target) throws Exception {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class PersistedDoc implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final String description;
        private final List<String> keywords;
        private final List<String> categoryTags;

        private PersistedDoc(WaypointDocument document) {
            this.id = document.id();
            this.name = document.name();
            this.description = document.description();
            this.keywords = new ArrayList<>(document.keywords());
            this.categoryTags = new ArrayList<>(document.categoryTags());
        }

        private ToolDocument toToolDocument() {
            return new ToolDocument(id, name, description, "", List.of(), keywords, categoryTags);
        }
    }

    private static final class PersistedState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final List<PersistedDoc> docs;

        private PersistedState(List<PersistedDoc> documents) {
            this.docs = new ArrayList<>(documents);
        }
    }
}
