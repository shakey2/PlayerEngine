package com.player2.playerengine.executor;

import com.player2.playerengine.player2api.config.BudgetThresholds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-billing-key rolling call-count tracker (Phase A4).
 *
 * <p>All state is checked/updated lazily when a completion is about to be dispatched.
 * No background thread is needed — windows reset on the next call attempt after expiry.
 *
 * <p>Thread-safe: each billing key's {@link WindowState} uses atomic fields.
 */
public final class BudgetTracker {
    private static final Logger LOGGER = LogManager.getLogger();

    private BudgetTracker() {
    }

    /** Result of a budget check for a single completion attempt. */
    public enum BudgetCheckResult {
        OK, SOFT_LIMIT, HARD_LIMIT
    }

    /** Rolling window state for one billing key. */
    static final class WindowState {
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile long windowStartMs = System.currentTimeMillis();
        final AtomicBoolean softMessagedThisWindow = new AtomicBoolean(false);
        final AtomicBoolean hardMessagedThisWindow = new AtomicBoolean(false);
    }

    /** Snapshot for status display (immutable view). */
    public record WindowSnapshot(int callCount, long windowStartMs, long windowEndMs) {
    }

    private static final ConcurrentHashMap<String, WindowState> WINDOWS = new ConcurrentHashMap<>();

    private static WindowState getOrCreate(String billingKey) {
        return WINDOWS.computeIfAbsent(billingKey, k -> new WindowState());
    }

    /**
     * Record one LLM call attempt for the given billing key and return whether any call-count limit
     * is reached. Call this before every completion dispatch.
     *
     * @param config either a {@link com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig}
     *               (OWNER_PAYS_ALL) or a {@link com.player2.playerengine.player2api.config.PlayerBudgetConfig}
     *               (PROMPTER_PAYS) — both implement {@link BudgetThresholds}.
     */
    public static BudgetCheckResult checkAndRecord(String billingKey, BudgetThresholds config) {
        if (billingKey == null) {
            return BudgetCheckResult.OK;
        }
        int soft = config.getSoftBudgetCallsPerWindow();
        int hard = config.getHardBudgetCallsPerWindow();
        if (soft == 0 && hard == 0) {
            return BudgetCheckResult.OK;
        }

        long windowMs = (long) config.getBudgetWindowMinutes() * 60_000L;
        WindowState ws = getOrCreate(billingKey);

        synchronized (ws) {
            long now = System.currentTimeMillis();
            if (now > ws.windowStartMs + windowMs) {
                ws.windowStartMs = now;
                ws.callCount.set(1);
                ws.softMessagedThisWindow.set(false);
                ws.hardMessagedThisWindow.set(false);
                LOGGER.debug("BudgetTracker: window reset for billingKey={}", billingKey);
                return BudgetCheckResult.OK;
            }
            int count = ws.callCount.incrementAndGet();
            if (hard > 0 && count > hard) {
                return BudgetCheckResult.HARD_LIMIT;
            }
            if (soft > 0 && count > soft) {
                return BudgetCheckResult.SOFT_LIMIT;
            }
            return BudgetCheckResult.OK;
        }
    }

    /**
     * Read-only budget check for probe / status (does not increment call count).
     */
    public static BudgetCheckResult peek(String billingKey, BudgetThresholds config) {
        if (billingKey == null) {
            return BudgetCheckResult.OK;
        }
        int soft = config.getSoftBudgetCallsPerWindow();
        int hard = config.getHardBudgetCallsPerWindow();
        if (soft == 0 && hard == 0) {
            return BudgetCheckResult.OK;
        }
        long windowMs = (long) config.getBudgetWindowMinutes() * 60_000L;
        WindowState ws = WINDOWS.get(billingKey);
        if (ws == null) {
            return BudgetCheckResult.OK;
        }
        synchronized (ws) {
            long now = System.currentTimeMillis();
            if (now > ws.windowStartMs + windowMs) {
                return BudgetCheckResult.OK;
            }
            int nextCount = ws.callCount.get() + 1;
            if (hard > 0 && nextCount > hard) {
                return BudgetCheckResult.HARD_LIMIT;
            }
            if (soft > 0 && nextCount > soft) {
                return BudgetCheckResult.SOFT_LIMIT;
            }
            return BudgetCheckResult.OK;
        }
    }

    /**
     * Returns true (and marks the flag) the first time a soft-limit message should be sent in the
     * current window for this billing key. Subsequent calls within the same window return false.
     */
    public static boolean shouldSendSoftMessage(String billingKey) {
        if (billingKey == null) return false;
        WindowState ws = getOrCreate(billingKey);
        return ws.softMessagedThisWindow.compareAndSet(false, true);
    }

    /**
     * Returns true (and marks the flag) the first time a hard-limit message should be sent in the
     * current window for this billing key. Subsequent calls within the same window return false.
     */
    public static boolean shouldSendHardMessage(String billingKey) {
        if (billingKey == null) return false;
        WindowState ws = getOrCreate(billingKey);
        return ws.hardMessagedThisWindow.compareAndSet(false, true);
    }

    /** Reset the window for a single billing key (operator command). */
    public static void reset(String billingKey) {
        if (billingKey == null) return;
        WINDOWS.remove(billingKey);
        LOGGER.info("BudgetTracker: window reset (operator) for billingKey={}", billingKey);
    }

    /** Reset all windows (operator /budget reset). */
    public static void resetAll() {
        int count = WINDOWS.size();
        WINDOWS.clear();
        LOGGER.info("BudgetTracker: all windows reset (operator), clearedCount={}", count);
    }

    /** Return an immutable snapshot of all current windows for status display. */
    public static Map<String, WindowSnapshot> statusSnapshot(BudgetThresholds config) {
        long windowMs = (long) config.getBudgetWindowMinutes() * 60_000L;
        Map<String, WindowSnapshot> result = new HashMap<>();
        for (Map.Entry<String, WindowState> entry : WINDOWS.entrySet()) {
            WindowState ws = entry.getValue();
            result.put(entry.getKey(),
                    new WindowSnapshot(ws.callCount.get(), ws.windowStartMs, ws.windowStartMs + windowMs));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Returns the stricter of two check results. */
    public static BudgetCheckResult stricter(BudgetCheckResult a, BudgetCheckResult b) {
        if (a == BudgetCheckResult.HARD_LIMIT || b == BudgetCheckResult.HARD_LIMIT) {
            return BudgetCheckResult.HARD_LIMIT;
        }
        if (a == BudgetCheckResult.SOFT_LIMIT || b == BudgetCheckResult.SOFT_LIMIT) {
            return BudgetCheckResult.SOFT_LIMIT;
        }
        return BudgetCheckResult.OK;
    }
}
