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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a configured fallback profile name to its API base URL (Phase A4).
 *
 * <p>Fetches {@code GET /v1/ai_profiles} into a short-lived cache scoped by billing identity and
 * client id, mapping profile names to adjusted base URLs. Profile switching is done by changing
 * the base URL, not a request body field:
 * {@code http://127.0.0.1:<port>/<profile-name>/v1} → stored as {@code http://127.0.0.1:<port>/<profile-name>}
 * so the existing {@code /v1/chat/completions} endpoint string can be appended unchanged.
 *
 * <p>Only active when {@code dedicatedClientProxy = false} — bridge mode does not support base URL
 * switching and is silently skipped.
 */
public final class ProfileUrlResolver {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROFILES_ENDPOINT = "/v1/ai_profiles";
    private static final long SUCCESS_CACHE_TTL_MS = 30_000L;
    private static final long FAILURE_CACHE_TTL_MS = 5_000L;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final int MAX_PROFILES_PER_BILLING = 16;
    private static final int MAX_PROFILE_NAME_LENGTH = 80;
    private static final int MAX_PROFILE_BASE_URL_LENGTH = 2_048;
    private static final ConcurrentHashMap<CacheKey, CacheEntry> PROFILE_CACHE = new ConcurrentHashMap<>();

    private record CacheKey(String clientId, String billingKey) {
    }

    private record CacheEntry(Map<String, String> profiles, long expiresAtMs) {
        boolean isCurrent(long nowMs) {
            return nowMs >= 0L && nowMs < expiresAtMs;
        }
    }

    private record FetchResult(Map<String, String> profiles, boolean successful) {
    }

    private ProfileUrlResolver() {
    }

    /**
     * Resolve the given profile name to its adjusted base URL.
     *
     * @return the base URL (e.g., {@code http://127.0.0.1:4315/budget-mode}) or empty if not found
     *         or if the player context is unavailable.
     */
    public static Optional<String> resolve(Player2APIService apiService, String profileName) {
        if (!isValidNamedProfileName(profileName)) {
            LOGGER.warn("ProfileUrlResolver: configured profile name is not a valid named-profile identifier");
            return Optional.empty();
        }
        Map<String, String> profiles = ensureLoaded(apiService);
        String url = profiles.get(profileName);
        if (url == null) {
            LOGGER.warn("ProfileUrlResolver: configured profile was not found for the current billing identity");
        }
        return Optional.ofNullable(url);
    }

    /**
     * Returns the base URL of the sole named (non-Default) profile, for B3 task-class routing.
     *
     * <p>A profile is "named" if its name is not {@code "default"} (case-insensitive).
     * Returns empty if there are zero named profiles or more than one named profile.
     * Callers must not assume a specific name for the patron tier.
     */
    public static Optional<String> getSoleNamedProfileBaseUrl(Player2APIService apiService) {
        Map<String, String> profiles = ensureLoaded(apiService);
        String found = null;
        for (Map.Entry<String, String> entry : profiles.entrySet()) {
            if (!"default".equalsIgnoreCase(entry.getKey())) {
                if (found != null) {
                    LOGGER.debug("ProfileUrlResolver.getSoleNamedProfileBaseUrl: >1 named profiles — returning empty");
                    return Optional.empty();
                }
                found = entry.getValue();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * Returns all known profile names (for debug / routing probe commands).
     * Returns an empty set if the profile list has not been fetched yet or the fetch failed.
     */
    public static Set<String> getProfileNames(Player2APIService apiService) {
        return Collections.unmodifiableSet(ensureLoaded(apiService).keySet());
    }

    /** Force re-fetch of the profile list on next use (e.g., after server reload). */
    public static void invalidateCache() {
        int count = PROFILE_CACHE.size();
        PROFILE_CACHE.clear();
        LOGGER.info("ProfileUrlResolver: cache invalidated, entries={}", count);
    }

    private static Map<String, String> ensureLoaded(Player2APIService apiService) {
        if (apiService == null) {
            return Collections.emptyMap();
        }
        Player2PayerResolution.ApiBillingContext billing = apiService.billingOrFallback();
        String billingKey = billing != null ? billing.billingKey() : null;
        if (billingKey == null || billingKey.isBlank()) {
            return Collections.emptyMap();
        }

        String clientId = apiService.getClientId() == null ? "" : apiService.getClientId();
        CacheKey key = new CacheKey(clientId, billingKey);
        long nowMs = System.currentTimeMillis();
        CacheEntry cached = PROFILE_CACHE.get(key);
        if (cached != null && cached.isCurrent(nowMs)) {
            return cached.profiles();
        }

        pruneCache(nowMs);
        CacheEntry refreshed = PROFILE_CACHE.compute(key, (ignored, existing) -> {
            long checkMs = System.currentTimeMillis();
            if (existing != null && existing.isCurrent(checkMs)) {
                return existing;
            }
            FetchResult fetched = fetchProfiles(apiService, billing);
            long ttlMs = fetched.successful() ? SUCCESS_CACHE_TTL_MS : FAILURE_CACHE_TTL_MS;
            return new CacheEntry(fetched.profiles(), System.currentTimeMillis() + ttlMs);
        });
        pruneCache(System.currentTimeMillis());
        return refreshed.profiles();
    }

    private static FetchResult fetchProfiles(Player2APIService apiService,
                                             Player2PayerResolution.ApiBillingContext billing) {
        try {
            ServerPlayer payer = billing.onlinePayer();
            if (payer == null) {
                LOGGER.warn("ProfileUrlResolver: no online billing player; cannot fetch /v1/ai_profiles");
                return new FetchResult(Collections.emptyMap(), false);
            }

            JsonElement element = Player2HTTPUtils.sendRequestElement(
                    payer, apiService.getClientId(), PROFILES_ENDPOINT, "GET", null);

            if (element == null || !element.isJsonArray()) {
                LOGGER.warn("ProfileUrlResolver: /v1/ai_profiles returned an unsupported response shape");
                return new FetchResult(Collections.emptyMap(), false);
            }

            JsonArray arr = element.getAsJsonArray();
            Map<String, String> result = new HashMap<>();
            for (JsonElement entry : arr) {
                if (result.size() >= MAX_PROFILES_PER_BILLING) break;
                if (!entry.isJsonObject()) continue;
                var obj = entry.getAsJsonObject();
                if (!obj.has("name") || !obj.has("base_url")) continue;
                JsonElement nameElement = obj.get("name");
                JsonElement baseUrlElement = obj.get("base_url");
                if (!nameElement.isJsonPrimitive() || !nameElement.getAsJsonPrimitive().isString()
                        || !baseUrlElement.isJsonPrimitive() || !baseUrlElement.getAsJsonPrimitive().isString()) continue;
                String name = nameElement.getAsString();
                String baseUrl = baseUrlElement.getAsString();
                if (!isValidNamedProfileName(name)
                        || baseUrl.isBlank() || baseUrl.length() > MAX_PROFILE_BASE_URL_LENGTH) continue;
                // Strip trailing /v1 so the existing /v1/chat/completions endpoint appends correctly
                String adjustedUrl = baseUrl.endsWith("/v1")
                        ? baseUrl.substring(0, baseUrl.length() - 3)
                        : baseUrl;
                if (adjustedUrl.isBlank()) continue;
                result.put(name, adjustedUrl);
            }
            LOGGER.info("ProfileUrlResolver: loaded {} profiles from /v1/ai_profiles", result.size());
            return new FetchResult(Collections.unmodifiableMap(result), true);

        } catch (Exception e) {
            LOGGER.warn("ProfileUrlResolver: failed to fetch /v1/ai_profiles ({}); retrying after cache TTL",
                    e.getClass().getSimpleName());
            return new FetchResult(Collections.emptyMap(), false);
        }
    }

    private static void pruneCache(long nowMs) {
        for (Map.Entry<CacheKey, CacheEntry> entry : PROFILE_CACHE.entrySet()) {
            if (!entry.getValue().isCurrent(nowMs)) {
                PROFILE_CACHE.remove(entry.getKey(), entry.getValue());
            }
        }
        int overflow = PROFILE_CACHE.size() - MAX_CACHE_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        for (Map.Entry<CacheKey, CacheEntry> entry : PROFILE_CACHE.entrySet()) {
            if (overflow-- <= 0) {
                break;
            }
            PROFILE_CACHE.remove(entry.getKey(), entry.getValue());
        }
    }

    static boolean isValidNamedProfileName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_PROFILE_NAME_LENGTH
                || "default".equalsIgnoreCase(name)) {
            return false;
        }
        int first = name.codePointAt(0);
        int last = name.codePointBefore(name.length());
        if (isWhitespace(first) || isWhitespace(last)) {
            return false;
        }
        for (int offset = 0; offset < name.length(); ) {
            int codePoint = name.codePointAt(offset);
            if (java.lang.Character.isISOControl(codePoint)
                    || java.lang.Character.getType(codePoint) == java.lang.Character.FORMAT) {
                return false;
            }
            offset += java.lang.Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isWhitespace(int codePoint) {
        return java.lang.Character.isWhitespace(codePoint)
                || java.lang.Character.isSpaceChar(codePoint);
    }
}
