package com.player2.playerengine.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side evaluation of per-owner userblacklist.json (written by Player2NPC). Blocks listed users from
 * prompting that owner's companions (chat, relay, and pre-LLM processing).
 */
public final class UserBlacklistPolicy {
    private static final Logger LOGGER = LogManager.getLogger();

    private UserBlacklistPolicy() {
    }

    public static boolean isBlocked(MinecraftServer server, @Nullable String initiatorUsername, AgentConversationData target) {
        if (server == null || initiatorUsername == null || initiatorUsername.isBlank() || target == null) {
            return false;
        }
        Player botOwner = target.getMod().getOwner();
        if (botOwner == null) {
            return false;
        }
        Path path = Player2NpcPersistencePaths.userBlacklistFile(server, botOwner.getUUID());
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String initiator = initiatorUsername.trim();
        UUID initiatorUuid = Player2NpcInitiatorUuidResolve.resolve(server, initiator);
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                String tu = o.has("targetUsername") ? o.get("targetUsername").getAsString() : null;
                if (tu == null || tu.isBlank()) {
                    continue;
                }
                String tUuidStr = o.has("targetUuid") && !o.get("targetUuid").isJsonNull() ? o.get("targetUuid").getAsString() : null;
                if (initiator.equalsIgnoreCase(tu.trim())) {
                    return true;
                }
                if (initiatorUuid != null && tUuidStr != null && !tUuidStr.isBlank()) {
                    try {
                        if (initiatorUuid.equals(UUID.fromString(tUuidStr.trim()))) {
                            return true;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed uuid in file
                    }
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed reading user blacklist at {}", path, e);
            return false;
        }
    }
}
