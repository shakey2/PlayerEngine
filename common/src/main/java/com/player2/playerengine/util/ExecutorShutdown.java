package com.player2.playerengine.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bounded shutdown for mod-owned {@link ExecutorService}s so server/JVM shutdown is not blocked
 * indefinitely by workers stuck on I/O.
 */
public final class ExecutorShutdown {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final long DEFAULT_AWAIT_SEC = 5L;

    private ExecutorShutdown() {
    }

    public static void shutdownNowAwait(String label, ExecutorService service) {
        shutdownNowAwait(label, service, DEFAULT_AWAIT_SEC, TimeUnit.SECONDS);
    }

    public static void shutdownNowAwait(String label, ExecutorService service, long timeout, TimeUnit unit) {
        if (service == null) {
            return;
        }
        if (service.isShutdown()) {
            return;
        }
        service.shutdownNow();
        try {
            if (!service.awaitTermination(timeout, unit)) {
                LOGGER.warn("Executor \"{}\" did not terminate within {} {}", label, timeout, unit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for executor \"{}\" to terminate", label);
        }
    }
}
