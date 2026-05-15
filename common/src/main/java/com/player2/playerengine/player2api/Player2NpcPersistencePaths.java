package com.player2.playerengine.player2api;

import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Paths under {@code player2npc/persistentdata/} used by PlayerEngine for cross-mod features.
 * Kept in sync with Player2NPC {@code OwnerCharacterStoragePaths} layout (no compile dependency on NPC).
 */
public final class Player2NpcPersistencePaths {
    public static final String BOT_BLACKLIST_FILE_NAME = "botblacklist.json";
    public static final String USER_BLACKLIST_FILE_NAME = "userblacklist.json";
    public static final String BOT_WHITELIST_FILE_NAME = "botwhitelist.json";
    public static final String USER_WHITELIST_FILE_NAME = "userwhitelist.json";
    public static final String USER_SETTINGS_FILE_NAME = "user-settings.json";
    public static final String SERVER_USERNAME_UUID_CACHE_FILE_NAME = "server_username_uuid_cache.json";

    private Player2NpcPersistencePaths() {
    }

    public static Path persistentDataRoot(Path worldRoot) {
        return worldRoot.resolve("player2npc").resolve("persistentdata");
    }

    public static Path ownersRoot(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve("owners");
    }

    public static Path botBlacklistFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(BOT_BLACKLIST_FILE_NAME);
    }

    public static Path userBlacklistFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(USER_BLACKLIST_FILE_NAME);
    }

    public static Path botWhitelistFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(BOT_WHITELIST_FILE_NAME);
    }

    public static Path userWhitelistFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(USER_WHITELIST_FILE_NAME);
    }

    public static Path userSettingsFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(USER_SETTINGS_FILE_NAME);
    }

    public static Path serverUsernameUuidCacheFile(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(SERVER_USERNAME_UUID_CACHE_FILE_NAME);
    }

    public static Path botBlacklistFile(MinecraftServer server, UUID ownerUuid) {
        return botBlacklistFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path userBlacklistFile(MinecraftServer server, UUID ownerUuid) {
        return userBlacklistFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path botWhitelistFile(MinecraftServer server, UUID ownerUuid) {
        return botWhitelistFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path userWhitelistFile(MinecraftServer server, UUID ownerUuid) {
        return userWhitelistFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path userSettingsFile(MinecraftServer server, UUID ownerUuid) {
        return userSettingsFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path serverUsernameUuidCacheFile(MinecraftServer server) {
        return serverUsernameUuidCacheFile(server.getWorldPath(LevelResource.ROOT));
    }
}
