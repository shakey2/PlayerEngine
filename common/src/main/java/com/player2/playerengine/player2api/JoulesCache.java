package com.player2.playerengine.player2api;

import com.google.gson.JsonElement;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coarse-polling cache for the Player2 Joules balance (Phase A4).
 *
 * <p>Calls {@code GET /v1/joules} per billing key at most once every
 * {@link Player2ServerRuntimeConfig#getJoulesRefreshIntervalSeconds()} seconds.
 * 402 responses invalidate the entry immediately so the next check gets a fresh read.
 *
 * <p>Refresh is synchronous (called from the LLM thread); {@code /v1/joules} is a fast local call.
 */
public final class JoulesCache {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String JOULES_ENDPOINT = "/v1/joules";

    private JoulesCache() {
    }

    /** Immutable snapshot from one {@code GET /v1/joules} response. */
    public static final class JoulesSnapshot {
        /** Joule balance as returned by {@code GET /v1/joules} — already in display units. */
        public final long joulesRaw;
        public final String patronTier;
        public final String userId;
        public final long refreshedAtMs;
        final AtomicBoolean softMessagedThisSnapshot = new AtomicBoolean(false);
        final AtomicBoolean hardMessagedThisSnapshot = new AtomicBoolean(false);

        JoulesSnapshot(long joulesRaw, String patronTier, String userId) {
            this.joulesRaw = joulesRaw;
            this.patronTier = patronTier != null ? patronTier : "";
            this.userId = userId != null ? userId : "";
            this.refreshedAtMs = System.currentTimeMillis();
        }

        /**
         * Joules balance as returned by the API.
         * The {@code joules} field from {@code GET /v1/joules} is already in display units —
         * it is NOT a raw×10000 value and must not be divided.
         */
        public long joulesDisplay() {
            return joulesRaw;
        }

        public boolean isPatron() {
            return !patronTier.isEmpty();
        }
    }

    /**
     * Synthetic snapshot for routing probe / self-tests (does not touch the live cache).
     */
    public static JoulesSnapshot snapshotForProbe(long joulesDisplay, String patronTier) {
        return new JoulesSnapshot(joulesDisplay, patronTier != null ? patronTier : "", "probe");
    }

    private static final ConcurrentHashMap<String, JoulesSnapshot> CACHE = new ConcurrentHashMap<>();

    /** Return the cached snapshot for the billing key, if present. */
    public static Optional<JoulesSnapshot> get(String billingKey) {
        if (billingKey == null) return Optional.empty();
        return Optional.ofNullable(CACHE.get(billingKey));
    }

    /**
     * Refresh the Joules balance for the billing key synchronously by calling {@code GET /v1/joules}.
     * Stores the result in the cache and returns it.
     */
    public static JoulesSnapshot refresh(Player2APIService apiService, String billingKey) {
        try {
            Map<String, JsonElement> response = Player2ApiDispatcher.route(
                    apiService.getController(),
                    apiService.getClientId(),
                    "GET", JOULES_ENDPOINT, null,
                    apiService.billingOrFallback());

            long joulesRaw = response.containsKey("joules") ? response.get("joules").getAsLong() : 0L;
            String patronTier = response.containsKey("patron_tier") ? response.get("patron_tier").getAsString() : "";
            String userId = response.containsKey("user_id") ? response.get("user_id").getAsString() : "";

            JoulesSnapshot snap = new JoulesSnapshot(joulesRaw, patronTier, userId);
            CACHE.put(billingKey, snap);
            LOGGER.debug("JoulesCache: refreshed billingKey={} joules={} patronTier={}",
                    billingKey, snap.joulesDisplay(), patronTier);
            return snap;
        } catch (Exception e) {
            LOGGER.warn("JoulesCache: failed to refresh for billingKey={}: {}", billingKey, e.getMessage());
            // Return stale if present; otherwise treat as no data (fail open)
            JoulesSnapshot stale = CACHE.get(billingKey);
            return stale;
        }
    }

    /**
     * Refresh the cache if the current snapshot is stale (older than
     * {@link BudgetThresholds#getJoulesRefreshIntervalSeconds()}).
     */
    public static void maybeRefresh(Player2APIService apiService, String billingKey,
            BudgetThresholds config) {
        if (billingKey == null || apiService == null) return;
        JoulesSnapshot existing = CACHE.get(billingKey);
        long refreshIntervalMs = (long) config.getJoulesRefreshIntervalSeconds() * 1_000L;
        if (existing == null || System.currentTimeMillis() > existing.refreshedAtMs + refreshIntervalMs) {
            refresh(apiService, billingKey);
        }
    }

    /**
     * Invalidate the cached entry for this billing key (e.g., after a 402 response).
     * The next call to {@link #maybeRefresh} or {@link #refresh} will fetch fresh data.
     */
    public static void invalidate(String billingKey) {
        if (billingKey == null) return;
        CACHE.remove(billingKey);
        LOGGER.debug("JoulesCache: invalidated billingKey={}", billingKey);
    }

    /** Invalidate all cached entries (operator /budget reset). */
    public static void invalidateAll() {
        int count = CACHE.size();
        CACHE.clear();
        LOGGER.info("JoulesCache: invalidated all entries, count={}", count);
    }

    /**
     * Check whether the Joules snapshot triggers a soft or hard threshold.
     * Returns OK if thresholds are disabled (0) or if snapshot is null (fail open).
     */
    public static BudgetTracker.BudgetCheckResult checkJoulesThreshold(JoulesSnapshot snap,
            BudgetThresholds config) {
        int hard = config.getHardJoulesThreshold();
        int soft = config.getSoftJoulesThreshold();
        if (snap == null || (hard == 0 && soft == 0)) {
            return BudgetTracker.BudgetCheckResult.OK;
        }
        if (hard > 0 && snap.joulesDisplay() < hard) {
            return BudgetTracker.BudgetCheckResult.HARD_LIMIT;
        }
        if (soft > 0 && snap.joulesDisplay() < soft) {
            return BudgetTracker.BudgetCheckResult.SOFT_LIMIT;
        }
        return BudgetTracker.BudgetCheckResult.OK;
    }

    /**
     * Returns true (and marks the flag) the first time a soft-limit message should be sent for
     * the current snapshot. Subsequent calls before the next refresh return false.
     */
    public static boolean shouldSendSoftMessage(String billingKey) {
        JoulesSnapshot snap = CACHE.get(billingKey);
        if (snap == null) return false;
        return snap.softMessagedThisSnapshot.compareAndSet(false, true);
    }

    /**
     * Returns true (and marks the flag) the first time a hard-limit message should be sent for
     * the current snapshot.
     */
    public static boolean shouldSendHardMessage(String billingKey) {
        JoulesSnapshot snap = CACHE.get(billingKey);
        if (snap == null) return false;
        return snap.hardMessagedThisSnapshot.compareAndSet(false, true);
    }

    /** Return all cached snapshots for status display. */
    public static ConcurrentHashMap<String, JoulesSnapshot> statusSnapshot() {
        return CACHE;
    }
}
