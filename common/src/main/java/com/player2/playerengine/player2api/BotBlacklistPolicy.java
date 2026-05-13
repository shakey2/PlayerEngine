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
 * Server-side evaluation of per-player botblacklist.json (written by Player2NPC commands).
 */
public final class BotBlacklistPolicy {
    private static final Logger LOGGER = LogManager.getLogger();

    private BotBlacklistPolicy() {
    }

    public static boolean isBlocked(MinecraftServer server, @Nullable String initiatorUsername, AgentConversationData target) {
        if (server == null || initiatorUsername == null || initiatorUsername.isBlank() || target == null) {
            return false;
        }
        UUID initiatorUuid = Player2NpcInitiatorUuidResolve.resolve(server, initiatorUsername.trim());
        if (initiatorUuid == null) {
            return false;
        }
        Path path = Player2NpcPersistencePaths.botBlacklistFile(server, initiatorUuid);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
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
        } catch (Exception e) {
            LOGGER.warn("Failed reading bot blacklist at {}", path, e);
            return false;
        }
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
