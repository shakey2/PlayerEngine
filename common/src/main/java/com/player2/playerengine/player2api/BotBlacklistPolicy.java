package com.player2.playerengine.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side evaluation of per-initiator bot blacklist / whitelist (JSON written by Player2NPC).
 * {@code isBlocked} means the initiator must not reach the target bot for this message round.
 */
public final class BotBlacklistPolicy {
    private static final Logger LOGGER = LogManager.getLogger();

    private BotBlacklistPolicy() {
    }

    public static boolean isBlocked(MinecraftServer server, @Nullable String initiatorUsername, AgentConversationData target) {
        if (server == null || initiatorUsername == null || initiatorUsername.isBlank() || target == null) {
            return false;
        }
        String initiatorTrim = initiatorUsername.trim();
        UUID initiatorUuid = Player2NpcInitiatorUuidResolve.resolve(server, initiatorTrim);
        Player2NpcOwnerSettingsReader.ListMode botMode = initiatorUuid != null
                ? Player2NpcOwnerSettingsReader.botListMode(server, initiatorUuid)
                : Player2NpcOwnerSettingsReader.ListMode.BLACKLIST;

        if (botMode == Player2NpcOwnerSettingsReader.ListMode.BLACKLIST) {
            if (initiatorUuid == null) {
                return false;
            }
            return blacklistFileBlocks(server, initiatorUuid, target);
        }
        if (initiatorOwnsTarget(initiatorTrim, initiatorUuid, target)) {
            return false;
        }
        if (initiatorUuid == null) {
            return true;
        }
        Path whitePath = Player2NpcPersistencePaths.botWhitelistFile(server, initiatorUuid);
        if (!Files.isRegularFile(whitePath)) {
            return true;
        }
        try {
            String raw = Files.readString(whitePath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            if (arr == null || arr.size() == 0) {
                return true;
            }
            return !botEntriesMatchTarget(arr, target);
        } catch (Exception e) {
            LOGGER.warn("Failed reading bot whitelist at {}", whitePath, e);
            return true;
        }
    }

    private static boolean initiatorOwnsTarget(String initiatorTrim, @Nullable UUID initiatorUuid, AgentConversationData target) {
        Player botOwner = target.getMod().getOwner();
        if (botOwner == null) {
            return false;
        }
        if (initiatorUuid != null && initiatorUuid.equals(botOwner.getUUID())) {
            return true;
        }
        String ownerName = botOwner.getGameProfile().getName();
        return ownerName != null && ownerName.equalsIgnoreCase(initiatorTrim);
    }

    private static boolean blacklistFileBlocks(MinecraftServer server, UUID initiatorUuid, AgentConversationData target) {
        Path path = Player2NpcPersistencePaths.botBlacklistFile(server, initiatorUuid);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            return botEntriesMatchTarget(arr, target);
        } catch (Exception e) {
            LOGGER.warn("Failed reading bot blacklist at {}", path, e);
            return false;
        }
    }

    private static boolean botEntriesMatchTarget(@Nullable JsonArray arr, AgentConversationData target) {
        if (arr == null) {
            return false;
        }
        Player botOwner = target.getMod().getOwner();
        if (botOwner == null) {
            return false;
        }
        String botOwnerName = target.getMod().getOwnerUsername();
        UUID botOwnerUuid = botOwner.getUUID();
        Character ch = target.getCharacter();
        if (ch == null) {
            return false;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            String tu = o.has("targetUsername") ? o.get("targetUsername").getAsString() : null;
            if (tu == null || tu.isBlank()) {
                continue;
            }
            String tUuidStr = o.has("targetUuid") && !o.get("targetUuid").isJsonNull() ? o.get("targetUuid").getAsString() : null;
            boolean allBots = o.has("allBots") && o.get("allBots").getAsBoolean();
            String cid = o.has("characterId") && !o.get("characterId").isJsonNull() ? o.get("characterId").getAsString() : null;
            String cname = o.has("characterName") && !o.get("characterName").isJsonNull() ? o.get("characterName").getAsString() : null;
            if (!ownerMatchesEntry(botOwnerName, botOwnerUuid, tu, tUuidStr)) {
                continue;
            }
            if (allBots) {
                return true;
            }
            if (cid != null && ch.id() != null && cid.equalsIgnoreCase(ch.id())) {
                return true;
            }
            if (cname != null) {
                if (ch.name() != null && ch.name().equalsIgnoreCase(cname)) {
                    return true;
                }
                if (ch.shortName() != null && ch.shortName().equalsIgnoreCase(cname)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ownerMatchesEntry(String botOwnerName, UUID botOwnerUuid, String entryUsername, @Nullable String entryUuidStr) {
        if (botOwnerName != null && botOwnerName.equalsIgnoreCase(entryUsername.trim())) {
            return true;
        }
        if (entryUuidStr != null && !entryUuidStr.isBlank()) {
            try {
                UUID entryUuid = UUID.fromString(entryUuidStr.trim());
                return Objects.equals(botOwnerUuid, entryUuid);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }
}
