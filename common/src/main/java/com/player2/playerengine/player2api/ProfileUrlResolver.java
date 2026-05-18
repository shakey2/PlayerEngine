package com.player2.playerengine.player2api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.player2.playerengine.player2api.utils.Player2HTTPUtils;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a configured fallback profile name to its API base URL (Phase A4).
 *
 * <p>Fetches {@code GET /v1/ai_profiles} once per session (cached), mapping profile names to
 * adjusted base URLs. Profile switching is done by changing the base URL, not a request body field:
 * {@code http://127.0.0.1:<port>/<profile-name>/v1} → stored as {@code http://127.0.0.1:<port>/<profile-name>}
 * so the existing {@code /v1/chat/completions} endpoint string can be appended unchanged.
 *
 * <p>Only active when {@code dedicatedClientProxy = false} — bridge mode does not support base URL
 * switching and is silently skipped.
 */
public final class ProfileUrlResolver {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROFILES_ENDPOINT = "/v1/ai_profiles";

    private ProfileUrlResolver() {
    }

    /**
     * Cached map of profile name → adjusted base URL (with {@code /v1} suffix stripped).
     * null means "not yet fetched" (distinct from "fetched but empty").
     */
    private static volatile Map<String, String> cachedProfiles = null;

    /**
     * Resolve the given profile name to its adjusted base URL.
     *
     * @return the base URL (e.g., {@code http://127.0.0.1:4315/budget-mode}) or empty if not found
     *         or if the player context is unavailable.
     */
    public static Optional<String> resolve(Player2APIService apiService, String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> profiles = ensureLoaded(apiService);
        String url = profiles.get(profileName.trim());
        if (url == null) {
            LOGGER.warn("ProfileUrlResolver: profile '{}' not found in /v1/ai_profiles (known: {})",
                    profileName, profiles.keySet());
        }
        return Optional.ofNullable(url);
    }

    /** Force re-fetch of the profile list on next use (e.g., after server reload). */
    public static void invalidateCache() {
        cachedProfiles = null;
        LOGGER.info("ProfileUrlResolver: cache invalidated");
    }

    private static Map<String, String> ensureLoaded(Player2APIService apiService) {
        if (cachedProfiles != null) {
            return cachedProfiles;
        }
        Map<String, String> loaded = fetchProfiles(apiService);
        cachedProfiles = loaded;
        return loaded;
    }

    private static Map<String, String> fetchProfiles(Player2APIService apiService) {
        try {
            Player2PayerResolution.ApiBillingContext billing = apiService.billingOrFallback();
            ServerPlayer payer = billing.onlinePayer();
            if (payer == null) {
                LOGGER.warn("ProfileUrlResolver: no online billing player; cannot fetch /v1/ai_profiles");
                return Collections.emptyMap();
            }

            JsonElement element = Player2HTTPUtils.sendRequestElement(
                    payer, apiService.getClientId(), PROFILES_ENDPOINT, "GET", null);

            if (!element.isJsonArray()) {
                LOGGER.warn("ProfileUrlResolver: expected JSON array from /v1/ai_profiles, got: {}",
                        element.getClass().getSimpleName());
                return Collections.emptyMap();
            }

            JsonArray arr = element.getAsJsonArray();
            Map<String, String> result = new HashMap<>();
            for (JsonElement entry : arr) {
                if (!entry.isJsonObject()) continue;
                var obj = entry.getAsJsonObject();
                if (!obj.has("name") || !obj.has("base_url")) continue;
                String name = obj.get("name").getAsString();
                String baseUrl = obj.get("base_url").getAsString();
                // Strip trailing /v1 so the existing /v1/chat/completions endpoint appends correctly
                String adjustedUrl = baseUrl.endsWith("/v1")
                        ? baseUrl.substring(0, baseUrl.length() - 3)
                        : baseUrl;
                result.put(name, adjustedUrl);
                LOGGER.debug("ProfileUrlResolver: loaded profile name={} adjustedUrl={}", name, adjustedUrl);
            }
            LOGGER.info("ProfileUrlResolver: loaded {} profiles from /v1/ai_profiles", result.size());
            return Collections.unmodifiableMap(result);

        } catch (Exception e) {
            LOGGER.warn("ProfileUrlResolver: failed to fetch /v1/ai_profiles: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
