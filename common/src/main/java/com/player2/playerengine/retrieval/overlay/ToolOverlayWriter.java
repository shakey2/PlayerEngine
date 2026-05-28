package com.player2.playerengine.retrieval.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic writer for {@link ToolOverlay} JSON files (Phase B5 learned overlays).
 */
public final class ToolOverlayWriter {

    private static final Logger LOGGER = LogManager.getLogger(ToolOverlayWriter.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ToolOverlayWriter() {}

    public static boolean writeAtomic(Path target, ToolOverlay overlay) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            String json = GSON.toJson(overlay);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            ToolOverlay verify = GSON.fromJson(json, ToolOverlay.class);
            if (verify == null || verify.schemaVersion != ToolOverlay.SUPPORTED_SCHEMA_VERSION) {
                Files.deleteIfExists(tmp);
                return false;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.warn("RAG overlay: atomic move unsupported for {} — used replace.", target);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("RAG overlay: write failed for {}: {}", target, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }
}
