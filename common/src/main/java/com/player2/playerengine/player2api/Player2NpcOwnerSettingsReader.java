package com.player2.playerengine.player2api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads {@code user-settings.json} under the owner UUID directory (same layout as Player2NPC).
 * Re-reads from disk on each call so mode changes apply without reload.
 */
public final class Player2NpcOwnerSettingsReader {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final String KEY_USER_LIST_MODE = "userListMode";
    public static final String KEY_BOT_LIST_MODE = "botListMode";

    public enum ListMode {
        BLACKLIST,
        WHITELIST;

        static ListMode fromJson(String raw) {
            if (raw != null && raw.trim().equalsIgnoreCase("whitelist")) {
                return WHITELIST;
            }
            return BLACKLIST;
        }
    }

    private Player2NpcOwnerSettingsReader() {
    }

    public static ListMode userListMode(MinecraftServer server, UUID settingsOwnerUuid) {
        return load(server, settingsOwnerUuid).user;
    }

    public static ListMode botListMode(MinecraftServer server, UUID settingsOwnerUuid) {
        return load(server, settingsOwnerUuid).bot;
    }

    private static final class Modes {
        final ListMode user;
        final ListMode bot;

        Modes(ListMode user, ListMode bot) {
            this.user = user;
            this.bot = bot;
        }
    }

    private static Modes load(MinecraftServer server, UUID ownerUuid) {
        Path path = Player2NpcPersistencePaths.userSettingsFile(server, ownerUuid);
        if (!Files.isRegularFile(path)) {
            return new Modes(ListMode.BLACKLIST, ListMode.BLACKLIST);
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            ListMode u = root.has(KEY_USER_LIST_MODE)
                    ? ListMode.fromJson(root.get(KEY_USER_LIST_MODE).getAsString())
                    : ListMode.BLACKLIST;
            ListMode b = root.has(KEY_BOT_LIST_MODE)
                    ? ListMode.fromJson(root.get(KEY_BOT_LIST_MODE).getAsString())
                    : ListMode.BLACKLIST;
            return new Modes(u, b);
        } catch (Exception e) {
            LOGGER.warn("Failed reading user settings at {}", path, e);
            return new Modes(ListMode.BLACKLIST, ListMode.BLACKLIST);
        }
    }
}
