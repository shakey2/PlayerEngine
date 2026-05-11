package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.PlayerEngine;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * S2C/C2S proxy for arbitrary Player2 API calls on the connected client (device auth + local app / web).
 */
public final class Player2ClientApiBridge {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_PAYLOAD_BYTES = 1_048_576;
    private static final long REQUEST_TIMEOUT_SECONDS = 180;
    private static final Map<String, PendingRequest> PENDING_REQUESTS = new ConcurrentHashMap<>();

    private record PendingRequest(UUID playerId, CompletableFuture<JsonObject> future) {
    }

    private Player2ClientApiBridge() {
    }

    public static Map<String, JsonElement> sendJson(ServerPlayer player, String clientId, String method,
            String endpoint, JsonObject requestBody) throws Exception {
        JsonObject response = sendJsonObject(player, clientId, method, endpoint, requestBody);
        return toMap(response);
    }

    public static JsonObject sendJsonObject(ServerPlayer player, String clientId, String method, String endpoint,
            JsonObject requestBody) throws Exception {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING_REQUESTS.put(requestId, new PendingRequest(player.getUUID(), future));

        try {
            byte[] payload = requestBody == null ? new byte[0]
                    : requestBody.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_PAYLOAD_BYTES) {
                PENDING_REQUESTS.remove(requestId);
                throw new IllegalArgumentException("Player2 proxy request too large: " + payload.length + " bytes");
            }

            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
            buf.writeUtf(requestId);
            buf.writeUtf(clientId);
            buf.writeUtf(method == null ? "GET" : method);
            buf.writeUtf(endpoint == null ? "" : endpoint);
            buf.writeByteArray(payload);

            LOGGER.info("Server: Player2 proxy {} {} {} -> client {}",
                    requestId, method, endpoint, player.getName().getString());
            player.connection.send(NetworkManager.toPacket(NetworkManager.Side.S2C,
                    PlayerEngine.CLIENT_PLAYER2_PROXY_REQUEST_PACKET_ID, buf));

            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            PENDING_REQUESTS.remove(requestId);
        }
    }

    public static void handleClientProxyResponse(RegistryFriendlyByteBuf buf, ServerPlayer player) {
        String requestId = buf.readUtf();
        boolean success = buf.readBoolean();
        byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
        String responseText = new String(payload, StandardCharsets.UTF_8);

        PendingRequest pending = PENDING_REQUESTS.get(requestId);
        if (pending == null) {
            LOGGER.warn("Server: Ignoring Player2 proxy response for unknown request {}", requestId);
            return;
        }
        if (!pending.playerId().equals(player.getUUID())) {
            LOGGER.warn("Server: Ignoring Player2 proxy response {} from unexpected player {}", requestId,
                    player.getName().getString());
            return;
        }

        if (!success) {
            pending.future().completeExceptionally(new RuntimeException(responseText));
            return;
        }

        try {
            JsonObject response = JsonParser.parseString(responseText).getAsJsonObject();
            pending.future().complete(response);
        } catch (Exception e) {
            pending.future().completeExceptionally(e);
        }
    }

    public static Map<String, JsonElement> toMap(JsonObject response) {
        Map<String, JsonElement> responseMap = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : response.entrySet()) {
            responseMap.put(entry.getKey(), entry.getValue());
        }
        return responseMap;
    }
}
