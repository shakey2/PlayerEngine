package com.player2.playerengine.agentic.elliegps;

import java.io.IOException;
import java.nio.file.Path;

/** Injectable authoritative JSON commit seam used by the store and deterministic tests. */
@FunctionalInterface
public interface WaypointPersistenceBackend {
    void persist(Path storeFile, String serializedStore) throws IOException;
}
