package com.player2.playerengine.retrieval.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.player2.playerengine.PlayerEnginePaths;
import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads {@link ToolOverlay} objects from disk.
 *
 * <p>Two overlay locations are recognised:
 * <ul>
 *   <li><b>Global</b> ({@code playerengine/tool_overrides.json}) — operator-managed,
 *       never written from chat or the B5 learning path.
 *   <li><b>Per-owner</b> ({@code world/player2npc/persistentdata/owners/<uuid>/tool_overrides.json})
 *       — written by the B5 alias-learning loop (Phase B5, not yet active).
 * </ul>
 *
 * <p>Design invariants:
 * <ul>
 *   <li>Never throws — all errors are caught, logged at WARN, and {@code Optional.empty()} returned.
 *   <li>Missing files return {@code Optional.empty()} silently (INFO, not WARN).
 *   <li>Files with {@code schemaVersion} ≠ 1 are rejected with an operator WARN.
 *   <li>Unknown top-level JSON keys and null/missing arrays are silently tolerated by Gson.
 * </ul>
 */
public final class ToolOverlayLoader {

    private static final Logger LOGGER = LogManager.getLogger(ToolOverlayLoader.class);
    private static final Gson GSON = new GsonBuilder().create();

    private ToolOverlayLoader() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads the global overlay from {@code playerengine/tool_overrides.json}.
     * Returns {@code Optional.empty()} if the file is absent or unreadable.
     */
    public static Optional<ToolOverlay> loadGlobal() {
        Path file = PlayerEnginePaths.userFile(Player2NpcPersistencePaths.TOOL_OVERRIDES_FILE_NAME);
        return loadFromFile(file, "global");
    }

    /**
     * Loads the per-owner overlay for {@code ownerUuid}.
     * Returns {@code Optional.empty()} if the file is absent or unreadable.
     */
    public static Optional<ToolOverlay> loadPerOwner(MinecraftServer server, UUID ownerUuid) {
        Path file = Player2NpcPersistencePaths.toolOverridesFile(server, ownerUuid);
        return loadFromFile(file, "per-owner[" + ownerUuid + "]");
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static Optional<ToolOverlay> loadFromFile(Path file, String label) {
        if (!Files.exists(file)) {
            LOGGER.debug("RAG overlay: no {} overlay file at {}", label, file);
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ToolOverlay overlay = GSON.fromJson(reader, ToolOverlay.class);
            if (overlay == null) {
                LOGGER.warn("RAG overlay: {} file at {} parsed as null — skipping.", label, file);
                return Optional.empty();
            }
            if (overlay.schemaVersion != ToolOverlay.SUPPORTED_SCHEMA_VERSION) {
                LOGGER.warn("RAG overlay: {} file at {} has unsupported schemaVersion={} "
                                + "(expected {}); file rejected.",
                        label, file, overlay.schemaVersion, ToolOverlay.SUPPORTED_SCHEMA_VERSION);
                return Optional.empty();
            }
            LOGGER.info("RAG overlay: loaded {} overlay from {} ({} tool entries).",
                    label, file, overlay.safeTools().size());
            return Optional.of(overlay);
        } catch (JsonSyntaxException e) {
            LOGGER.warn("RAG overlay: {} file at {} has invalid JSON — skipping: {}", label, file, e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            LOGGER.warn("RAG overlay: could not read {} file at {} — skipping: {}", label, file, e.getMessage());
            return Optional.empty();
        }
    }
}
