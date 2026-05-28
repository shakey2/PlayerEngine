package com.player2.playerengine.retrieval.learning;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extra per-billing-key cap for B5 deep-check calls (in addition to A4).
 */
public final class DeepCheckBudgetTracker {

    private static final Logger LOGGER = LogManager.getLogger(DeepCheckBudgetTracker.class);

    private static final class WindowState {
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile long windowStartMs = System.currentTimeMillis();
    }

    private static final ConcurrentHashMap<String, WindowState> WINDOWS = new ConcurrentHashMap<>();

    private DeepCheckBudgetTracker() {}

    public static DeepCheckBudgetSnapshot peek(String billingKey, DeepCheckBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return new DeepCheckBudgetSnapshot(0, 0, 0);
        }
        WindowState ws = getOrCreate(billingKey);
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            return new DeepCheckBudgetSnapshot(
                    ws.callCount.get(), ws.windowStartMs, ws.windowStartMs + windowMs);
        }
    }

    public static DeepCheckBudgetResult peekCap(String billingKey, DeepCheckBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return DeepCheckBudgetResult.OK;
        }
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        WindowState ws = getOrCreate(billingKey);
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            if (ws.callCount.get() >= thresholds.callsPerWindow()) {
                return DeepCheckBudgetResult.CAP_REACHED;
            }
            return DeepCheckBudgetResult.OK;
        }
    }

    public static DeepCheckBudgetResult checkAndRecord(String billingKey, DeepCheckBudgetThresholds thresholds) {
        if (billingKey == null || thresholds.callsPerWindow() <= 0) {
            return DeepCheckBudgetResult.OK;
        }
        long windowMs = (long) thresholds.windowMinutes() * 60_000L;
        WindowState ws = getOrCreate(billingKey);
        synchronized (ws) {
            resetIfExpired(ws, windowMs);
            int count = ws.callCount.incrementAndGet();
            if (count > thresholds.callsPerWindow()) {
                ws.callCount.decrementAndGet();
                LOGGER.debug("[B5] deep_check_cap_skip billingKey={} count={}", billingKey, count - 1);
                return DeepCheckBudgetResult.CAP_REACHED;
            }
            return DeepCheckBudgetResult.OK;
        }
    }

    private static WindowState getOrCreate(String billingKey) {
        return WINDOWS.computeIfAbsent(billingKey, k -> new WindowState());
    }

    private static void resetIfExpired(WindowState ws, long windowMs) {
        long now = System.currentTimeMillis();
        if (now > ws.windowStartMs + windowMs) {
            ws.windowStartMs = now;
            ws.callCount.set(0);
        }
    }
}
