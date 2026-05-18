package com.player2.playerengine.player2api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.player2.playerengine.player2api.config.PlayerBudgetConfig;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and saves per-player budget configuration (Phase A4).
 *
 * <p>Files live at {@code player2npc/persistentdata/owners/<uuid>/player-budget.json} in the
 * world folder, alongside other per-player Player2 data.
 *
 * <p>An in-memory cache avoids disk reads on every completion call. Call {@link #invalidate(UUID)}
 * after saving to refresh the cache on the next read.
 */
public final class PlayerBudgetConfigHolder {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "player-budget.json";

    private PlayerBudgetConfigHolder() {
    }

    private static final ConcurrentHashMap<UUID, PlayerBudgetConfig> CACHE = new ConcurrentHashMap<>();

    /**
     * Load the budget config for the given player UUID.
     * Returns defaults (all limits disabled) if no file exists.
     * Uses in-memory cache — fast for the LLM hot-path.
     */
    public static PlayerBudgetConfig load(MinecraftServer server, UUID playerUuid) {
        if (playerUuid == null) return new PlayerBudgetConfig();
        return CACHE.computeIfAbsent(playerUuid, uuid -> loadFromDisk(server, uuid));
    }

    /** Save the config for a player and update the in-memory cache. */
    public static void save(MinecraftServer server, UUID playerUuid, PlayerBudgetConfig config) {
        if (playerUuid == null || config == null) return;
        Path path = Player2NpcPersistencePaths.playerBudgetFile(server, playerUuid);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
            CACHE.put(playerUuid, config);
            LOGGER.debug("PlayerBudgetConfigHolder: saved budget for player {}", playerUuid);
        } catch (IOException e) {
            LOGGER.warn("PlayerBudgetConfigHolder: failed to save budget for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** Evict a player's config from the in-memory cache (forces disk re-read on next access). */
    public static void invalidate(UUID playerUuid) {
        if (playerUuid != null) {
            CACHE.remove(playerUuid);
        }
    }

    private static PlayerBudgetConfig loadFromDisk(MinecraftServer server, UUID playerUuid) {
        Path path = Player2NpcPersistencePaths.playerBudgetFile(server, playerUuid);
        if (!Files.isRegularFile(path)) {
            return new PlayerBudgetConfig();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            PlayerBudgetConfig cfg = GSON.fromJson(raw, PlayerBudgetConfig.class);
            return cfg != null ? cfg : new PlayerBudgetConfig();
        } catch (Exception e) {
            LOGGER.warn("PlayerBudgetConfigHolder: failed to load budget for {}, using defaults: {}",
                    playerUuid, e.getMessage());
            return new PlayerBudgetConfig();
        }
    }
}
