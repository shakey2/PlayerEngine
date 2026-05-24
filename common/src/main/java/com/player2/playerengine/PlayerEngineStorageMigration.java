package com.player2.playerengine;

import com.player2.playerengine.player2api.Player2NpcPersistencePaths;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time best-effort migration from {@code config/playerengine/} to {@code playerengine/}.
 */
public final class PlayerEngineStorageMigration {
    private static final AtomicBoolean RAN = new AtomicBoolean(false);
    private static final String MARKER = ".migrated_from_config_v1";

    private PlayerEngineStorageMigration() {}

    public static void runOnce() {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        Path marker = PlayerEnginePaths.dataRoot().resolve(MARKER);
        if (Files.exists(marker)) {
            return;
        }

        Logger log = PlayerEngine.LOGGER;
        try {
            Files.createDirectories(PlayerEnginePaths.dataRoot());
            Files.createDirectories(PlayerEnginePaths.root());

            migrateDirectory(
                    PlayerEnginePaths.legacyConfigPlayerEngineRoot().resolve("mod_intelligence"),
                    PlayerEnginePaths.modIntelligenceRoot(),
                    true,
                    log);
            migrateDirectory(
                    PlayerEnginePaths.legacyConfigPlayerEngineRoot().resolve("rag_index"),
                    PlayerEnginePaths.ragIndexRoot(),
                    false,
                    log);
            migrateFile(
                    PlayerEnginePaths.legacyConfigPlayerEngineRoot().resolve("settings.txt"),
                    PlayerEnginePaths.userFile("settings.txt"),
                    log);
            migrateFile(
                    PlayerEnginePaths.legacyConfigPlayerEngineRoot()
                            .resolve(Player2NpcPersistencePaths.TOOL_OVERRIDES_FILE_NAME),
                    PlayerEnginePaths.userFile(Player2NpcPersistencePaths.TOOL_OVERRIDES_FILE_NAME),
                    log);
            migrateFile(
                    PlayerEnginePaths.legacyConfigPlayerEngineRoot().resolve("tool_overrides.README.md"),
                    PlayerEnginePaths.userFile("tool_overrides.README.md"),
                    log);
            migrateFile(
                    PlayerEnginePaths.legacyChatclefConfigFile(),
                    PlayerEnginePaths.userFile("chatclef_config.json"),
                    log);

            Files.createFile(marker);
            log.info("PlayerEngine storage migration: completed (marker at {})", marker);
        } catch (IOException e) {
            log.warn("PlayerEngine storage migration: failed before marker write: {}", e.getMessage());
        }
    }

    private static void migrateFile(Path source, Path dest, Logger log) throws IOException {
        if (!Files.exists(source) || Files.isDirectory(source)) {
            return;
        }
        if (Files.exists(dest)) {
            log.info("PlayerEngine storage migration: skip file (dest exists) {}", dest);
            return;
        }
        Files.createDirectories(dest.getParent());
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
            log.info("PlayerEngine storage migration: moved {} -> {}", source, dest);
        } catch (IOException e) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            log.info("PlayerEngine storage migration: copied {} -> {} ({})", source, dest, e.getMessage());
        }
    }

    private static void migrateDirectory(Path source, Path dest, boolean preferMove, Logger log)
            throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        if (Files.exists(dest)) {
            log.info("PlayerEngine storage migration: skip dir (dest exists) {}", dest);
            return;
        }
        Files.createDirectories(dest.getParent());
        if (preferMove) {
            try {
                Files.move(source, dest);
                log.info("PlayerEngine storage migration: moved dir {} -> {}", source, dest);
                return;
            } catch (IOException e) {
                log.info("PlayerEngine storage migration: move dir failed, copying {} ({})",
                        source, e.getMessage());
            }
        }
        copyTree(source, dest);
        log.info("PlayerEngine storage migration: copied dir {} -> {}", source, dest);
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(dir));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                if (!Files.exists(target)) {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
