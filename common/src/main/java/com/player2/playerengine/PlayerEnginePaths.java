package com.player2.playerengine;

import com.player2.playerengine.automaton.utils.DirUtil;
import dev.architectury.platform.Platform;

import java.nio.file.Path;

/**
 * Canonical filesystem layout for PlayerEngine-owned files under the game directory.
 *
 * <p>User-facing settings live under {@link #root()}. Generated caches and durable derived
 * stores live under {@link #dataRoot()}. Do not use {@link Platform#getConfigFolder()} for
 * PlayerEngine app data except via {@link #legacyConfigPlayerEngineRoot()} during migration.
 */
public final class PlayerEnginePaths {
    private static final String DATA_DIR = "data";
    private static final String MOD_INTELLIGENCE_DIR = "mod_intelligence";
    private static final String RAG_INDEX_DIR = "rag_index";

    private PlayerEnginePaths() {}

    /** {@code <gameDir>/playerengine} */
    public static Path root() {
        return DirUtil.getGameDir().resolve(PlayerEngine.MOD_ID);
    }

    /** Same as {@link #root()} — small JSON/settings the operator may edit. */
    public static Path userConfigRoot() {
        return root();
    }

    /** {@code <gameDir>/playerengine/data} — generated/runtime stores. */
    public static Path dataRoot() {
        return root().resolve(DATA_DIR);
    }

    /** {@code <gameDir>/playerengine/data/mod_intelligence} */
    public static Path modIntelligenceRoot() {
        return dataRoot().resolve(MOD_INTELLIGENCE_DIR);
    }

    /** {@code <gameDir>/playerengine/data/rag_index} */
    public static Path ragIndexRoot() {
        return dataRoot().resolve(RAG_INDEX_DIR);
    }

    /** A file directly under {@link #root()}, e.g. {@code settings.txt}. */
    public static Path userFile(String fileName) {
        return root().resolve(fileName);
    }

    /**
     * Legacy location where generated data was stored before the config-restraint move.
     * Used only by {@link PlayerEngineStorageMigration}.
     */
    public static Path legacyConfigPlayerEngineRoot() {
        return DirUtil.getConfigDir().resolve(PlayerEngine.MOD_ID);
    }

    /** Legacy {@code config/chatclef_config.json} (pre-move). */
    public static Path legacyChatclefConfigFile() {
        return DirUtil.getConfigDir().resolve("chatclef_config.json");
    }
}
