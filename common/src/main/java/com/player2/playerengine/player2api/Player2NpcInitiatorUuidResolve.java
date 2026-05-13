package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a chat/API initiator username to a UUID (online, then {@link Player2NpcPersistencePaths}
 * server username cache, then Mojang profile cache). Shared by blacklist policies.
 */
public final class Player2NpcInitiatorUuidResolve {
    private static final Logger LOGGER = LogManager.getLogger();

    private Player2NpcInitiatorUuidResolve() {
    }

    @Nullable
    public static UUID resolve(MinecraftServer server, String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String trimmed = username.trim();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getGameProfile().getName().equalsIgnoreCase(trimmed)) {
                return sp.getUUID();
            }
        }
        UUID fromFile = lookupUuidFromServerUsernameCache(server, trimmed);
        if (fromFile != null) {
            return fromFile;
        }
        try {
            var cache = server.getProfileCache();
            if (cache != null) {
                Optional<com.mojang.authlib.GameProfile> opt = cache.get(trimmed);
                if (opt.isPresent()) {
                    return opt.get().getId();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Player2NpcInitiatorUuidResolve profile cache failed for {}", trimmed, e);
        }
        return null;
    }

    /**
     * Reads {@link Player2NpcPersistencePaths#SERVER_USERNAME_UUID_CACHE_FILE_NAME}; must stay aligned with
     * Player2NPC {@code ServerUsernameUuidCache} JSON layout.
     */
    @Nullable
    private static UUID lookupUuidFromServerUsernameCache(MinecraftServer server, String username) {
        Path path = Player2NpcPersistencePaths.serverUsernameUuidCacheFile(server);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("byLowerName")) {
                return null;
            }
            String key = username.toLowerCase(Locale.ROOT);
            var byLower = root.getAsJsonObject("byLowerName");
            if (!byLower.has(key)) {
                return null;
            }
            var el = byLower.get(key);
            if (el == null || !el.isJsonPrimitive()) {
                return null;
            }
            String uuidStr = el.getAsString();
            if (uuidStr == null || uuidStr.isBlank()) {
                return null;
            }
            return UUID.fromString(uuidStr.trim());
        } catch (Exception e) {
            LOGGER.debug("lookupUuidFromServerUsernameCache failed for {}", username, e);
            return null;
        }
    }
}
