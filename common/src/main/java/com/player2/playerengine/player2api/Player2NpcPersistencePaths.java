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
    public static final String PLAYER_BUDGET_FILE_NAME = "player-budget.json";
    public static final String SERVER_USERNAME_UUID_CACHE_FILE_NAME = "server_username_uuid_cache.json";
    /** Filename for per-owner (and global) tool keyword/example overlay files. */
    public static final String TOOL_OVERRIDES_FILE_NAME = "tool_overrides.json";
    /** Per-owner learned overlay (B5); separate from manual {@link #TOOL_OVERRIDES_FILE_NAME}. */
    public static final String TOOL_LEARNED_OVERRIDES_FILE_NAME = "tool_learned_overrides.json";
    public static final String LEARN_AUDIT_FILE_NAME = "learn_audit.jsonl";

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

    public static Path playerBudgetFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(PLAYER_BUDGET_FILE_NAME);
    }

    public static Path serverUsernameUuidCacheFile(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(SERVER_USERNAME_UUID_CACHE_FILE_NAME);
    }

    public static Path toolOverridesFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(TOOL_OVERRIDES_FILE_NAME);
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

    public static Path playerBudgetFile(MinecraftServer server, UUID ownerUuid) {
        return playerBudgetFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path serverUsernameUuidCacheFile(MinecraftServer server) {
        return serverUsernameUuidCacheFile(server.getWorldPath(LevelResource.ROOT));
    }

    public static Path toolOverridesFile(MinecraftServer server, UUID ownerUuid) {
        return toolOverridesFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path toolLearnedOverridesFile(Path worldRoot, UUID ownerUuid) {
        return ownersRoot(worldRoot).resolve(ownerUuid.toString()).resolve(TOOL_LEARNED_OVERRIDES_FILE_NAME);
    }

    public static Path toolLearnedOverridesFile(MinecraftServer server, UUID ownerUuid) {
        return toolLearnedOverridesFile(server.getWorldPath(LevelResource.ROOT), ownerUuid);
    }

    public static Path learnAuditFile(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(LEARN_AUDIT_FILE_NAME);
    }

    public static Path learnAuditFile(MinecraftServer server) {
        return learnAuditFile(server.getWorldPath(LevelResource.ROOT));
    }

    // -------------------------------------------------------------------------
    // EllieGPS paths (Part C5)
    // -------------------------------------------------------------------------

    /** Directory name for EllieGPS per-world data. */
    public static final String ELLIEGPS_DIR_NAME = "elliegps";

    /**
     * Root EllieGPS directory: {@code <worldRoot>/player2npc/persistentdata/elliegps/}.
     *
     * <p>Contains {@code waypoints.json} (source of truth) and the {@code index/} subdirectory
     * ({@code waypoints.bin} + {@code version.txt}).
     */
    public static Path ellieGpsRoot(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(ELLIEGPS_DIR_NAME);
    }

    public static Path ellieGpsRoot(MinecraftServer server) {
        return ellieGpsRoot(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * Authoritative waypoints file: {@code <worldRoot>/player2npc/persistentdata/elliegps/waypoints.json}.
     * Written via atomic tmp-rename on every mutation; always the recovery source.
     */
    public static Path ellieGpsWaypointsFile(Path worldRoot) {
        return ellieGpsRoot(worldRoot).resolve("waypoints.json");
    }

    public static Path ellieGpsWaypointsFile(MinecraftServer server) {
        return ellieGpsWaypointsFile(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * EllieGPS index directory: {@code <worldRoot>/player2npc/persistentdata/elliegps/index/}.
     *
     * <p>Contains {@code waypoints.bin} (serialized {@code LexicalIndex.State} +
     * {@code MinHashIndex.State}) and {@code version.txt} (the version token). This directory
     * is a derived cache; it is always rebuildable from {@link #ellieGpsWaypointsFile}.
     */
    public static Path ellieGpsIndexDir(Path worldRoot) {
        return ellieGpsRoot(worldRoot).resolve("index");
    }

    public static Path ellieGpsIndexDir(MinecraftServer server) {
        return ellieGpsIndexDir(server.getWorldPath(LevelResource.ROOT));
    }

    // -------------------------------------------------------------------------
    // GraphRAG memory paths (Phase D, W2) — per companion, PRIVATE
    // -------------------------------------------------------------------------

    /** Per-companion memory subdirectory name. */
    public static final String MEMORY_DIR_NAME = "memory";

    /** Authoritative memory graph filename within a companion's memory directory. */
    public static final String MEMORY_GRAPH_FILE_NAME = "graph.json";

    /**
     * Owner-resolved per-companion memory directory:
     * {@code <worldRoot>/player2npc/persistentdata/owners/<ownerUuid>/<companionId>/memory/}.
     *
     * <p>Mirrors the conversation-history canonical layout. The memory graph is strictly private
     * to one {@code (ownerUuid, companionId)} pair.
     */
    public static Path memoryDir(Path worldRoot, UUID ownerUuid, String companionId) {
        return ownersRoot(worldRoot)
                .resolve(ownerUuid.toString())
                .resolve(companionId)
                .resolve(MEMORY_DIR_NAME);
    }

    public static Path memoryDir(MinecraftServer server, UUID ownerUuid, String companionId) {
        return memoryDir(server.getWorldPath(LevelResource.ROOT), ownerUuid, companionId);
    }

    /**
     * Owner-UUID-unresolvable fallback memory directory:
     * {@code <worldRoot>/player2npc/persistentdata/<entityUuid>/<companionId>/memory/}.
     *
     * <p>Mirrors the conversation-history entity-scoped fallback (see {@code AIPersistantData}):
     * used when the owner UUID cannot be resolved this session.
     */
    public static Path memoryDirEntityFallback(Path worldRoot, UUID entityUuid, String companionId) {
        return persistentDataRoot(worldRoot)
                .resolve(entityUuid.toString())
                .resolve(companionId)
                .resolve(MEMORY_DIR_NAME);
    }

    public static Path memoryDirEntityFallback(MinecraftServer server, UUID entityUuid, String companionId) {
        return memoryDirEntityFallback(server.getWorldPath(LevelResource.ROOT), entityUuid, companionId);
    }

    /**
     * Authoritative memory graph file (owner-resolved):
     * {@code owners/<ownerUuid>/<companionId>/memory/graph.json}.
     */
    public static Path memoryGraphFile(Path worldRoot, UUID ownerUuid, String companionId) {
        return memoryDir(worldRoot, ownerUuid, companionId).resolve(MEMORY_GRAPH_FILE_NAME);
    }

    /** Authoritative memory graph file (owner-unresolvable fallback). */
    public static Path memoryGraphFileEntityFallback(Path worldRoot, UUID entityUuid, String companionId) {
        return memoryDirEntityFallback(worldRoot, entityUuid, companionId).resolve(MEMORY_GRAPH_FILE_NAME);
    }

    // -------------------------------------------------------------------------
    // Companion mood paths (Lightweight Companion Mood System — WS1) — per companion
    // -------------------------------------------------------------------------

    /** Filename for a companion's persisted current {@code CompanionMood}. */
    public static final String MOOD_FILE_NAME = "mood.json";

    /**
     * Owner-resolved per-companion mood file:
     * {@code <worldRoot>/player2npc/persistentdata/owners/<ownerUuid>/<characterId>/mood.json}.
     *
     * <p>Parallel to {@code conversation.jsonl} (same {@code owners/<ownerUuid>/<characterId>/}
     * directory). Holds the single current {@code CompanionMood} record; missing/corrupt → neutral.
     */
    public static Path moodFile(Path worldRoot, UUID ownerUuid, String characterId) {
        return ownersRoot(worldRoot)
                .resolve(ownerUuid.toString())
                .resolve(characterId)
                .resolve(MOOD_FILE_NAME);
    }

    public static Path moodFile(MinecraftServer server, UUID ownerUuid, String characterId) {
        return moodFile(server.getWorldPath(LevelResource.ROOT), ownerUuid, characterId);
    }

    // -------------------------------------------------------------------------
    // Deferred-job store paths (WS7)
    // -------------------------------------------------------------------------

    /** Directory name for deferred-job per-world data. */
    public static final String DEFERRED_JOBS_DIR_NAME = "deferredjobs";

    /**
     * Root deferred-jobs directory:
     * {@code <worldRoot>/player2npc/persistentdata/deferredjobs/}.
     *
     * <p>Contains {@code jobs.json} (source of truth for all active deferred jobs).
     */
    public static Path deferredJobsRoot(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(DEFERRED_JOBS_DIR_NAME);
    }

    public static Path deferredJobsRoot(MinecraftServer server) {
        return deferredJobsRoot(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * Authoritative deferred-jobs file:
     * {@code <worldRoot>/player2npc/persistentdata/deferredjobs/jobs.json}.
     * Written via atomic tmp-rename on every mutation; always the recovery source.
     */
    public static Path deferredJobsFile(Path worldRoot) {
        return deferredJobsRoot(worldRoot).resolve("jobs.json");
    }

    public static Path deferredJobsFile(MinecraftServer server) {
        return deferredJobsFile(server.getWorldPath(LevelResource.ROOT));
    }

    // -------------------------------------------------------------------------
    // Player-placed-block store paths (respect player structures)
    // -------------------------------------------------------------------------

    /** Directory name for the per-world player-placed-block store. */
    public static final String PLAYER_PLACED_DIR_NAME = "playerplaced";

    /** Filename for each dimension's player-placed-block data. */
    public static final String PLAYER_PLACED_FILE_NAME = "blocks.json";

    /**
     * Root player-placed directory:
     * {@code <worldRoot>/player2npc/persistentdata/playerplaced/}.
     *
     * <p>Holds one {@code <dimension-sanitized>/blocks.json} per loaded dimension.
     */
    public static Path playerPlacedRoot(Path worldRoot) {
        return persistentDataRoot(worldRoot).resolve(PLAYER_PLACED_DIR_NAME);
    }

    public static Path playerPlacedRoot(MinecraftServer server) {
        return playerPlacedRoot(server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * Per-dimension player-placed-block file:
     * {@code <worldRoot>/player2npc/persistentdata/playerplaced/<dimension-sanitized>/blocks.json}.
     * The dimension id (e.g. {@code minecraft:overworld}) is sanitized into a single safe path segment.
     */
    public static Path playerPlacedFile(Path worldRoot, String dimensionId) {
        return playerPlacedRoot(worldRoot).resolve(sanitizeDimensionId(dimensionId)).resolve(PLAYER_PLACED_FILE_NAME);
    }

    public static Path playerPlacedFile(MinecraftServer server, String dimensionId) {
        return playerPlacedFile(server.getWorldPath(LevelResource.ROOT), dimensionId);
    }

    /** Sanitizes a dimension id (e.g. {@code minecraft:the_nether}) into a single filesystem path segment. */
    private static String sanitizeDimensionId(String dimensionId) {
        return dimensionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
