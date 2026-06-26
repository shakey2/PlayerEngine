package com.player2.playerengine.memory;

import com.player2.playerengine.PlayerEngine;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase D serial-integration glue (no single workstream owns this): the per-server, per-{@link MemoryScope}
 * cache of live {@link MemoryStore}s, plus their lifecycle (lazy load, tick-end {@code flushIfDirty},
 * {@code SERVER_STOPPING} final flush + clear).
 *
 * <p>W2 provides per-companion store mechanics ({@code loadForCompanion}/{@code flushIfDirty}/{@code clear})
 * but deliberately leaves the registry to the integration pass. W3's {@code MemoryIngestionService}
 * store-provider seam, W5's {@code MemoryRetriever}, and W6's relationship-summary lookup all resolve a
 * store through this registry so they share one cached instance per scope (otherwise concurrent reloads
 * would diverge).
 *
 * <p><b>Common-only / egress.</b> The only Minecraft surface used is {@link MinecraftServer}: the world
 * root path ({@code getWorldPath(LevelResource.ROOT)}) for store location and {@code overworld().getGameTime()}
 * as the compaction recency basis. No loader imports; no log/stack/unbounded egress — failures distill to
 * {@code getClass().getSimpleName()} in a bounded WARN.
 *
 * <p><b>Threading.</b> {@link #getOrLoad} and the flush/clear lifecycle run on the SERVER THREAD (the
 * lifecycle hooks and {@code server.execute} marshalling). The store's own published snapshot is the
 * thread-safe read surface used off-thread.
 */
public final class MemoryStoreRegistry {

    private static final Map<MemoryScope, MemoryStore> STORES = new ConcurrentHashMap<>();

    private MemoryStoreRegistry() {}

    /**
     * Returns the live store for a scope, lazily loading it from disk on first request. Never throws;
     * returns {@code null} only when the world root cannot be resolved. SERVER THREAD.
     */
    public static MemoryStore getOrLoad(MinecraftServer server, MemoryScope scope) {
        if (server == null || scope == null) {
            return null;
        }
        MemoryStore existing = STORES.get(scope);
        if (existing != null) {
            return existing;
        }
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            // computeIfAbsent guards against a concurrent server.execute double-load for the same scope.
            return STORES.computeIfAbsent(scope, s -> MemoryStore.loadForCompanion(worldRoot, s));
        } catch (RuntimeException e) {
            PlayerEngine.LOGGER.warn("Memory: store load failed for {} ({}).",
                    scope, e.getClass().getSimpleName());
            return null;
        }
    }

    /** The already-loaded store for a scope, or {@code null} if not loaded (no disk hit). */
    public static MemoryStore peek(MemoryScope scope) {
        return scope == null ? null : STORES.get(scope);
    }

    /**
     * Tick-end flush of every dirty store (no-op for clean stores; never throws). Registered on the
     * server tick-end event by the lifecycle wiring.
     */
    public static void flushAllIfDirty(MinecraftServer server) {
        if (server == null || STORES.isEmpty()) {
            return;
        }
        long nowTick = currentTick(server);
        for (MemoryStore store : STORES.values()) {
            try {
                store.flushIfDirty(nowTick);
            } catch (RuntimeException e) {
                PlayerEngine.LOGGER.warn("Memory: tick-end flush failed ({}).", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * {@code SERVER_STOPPING}: final flush + in-memory clear of every store, then drop the registry so
     * the next {@code SERVER_STARTING} starts empty (mirrors the EllieGPS / DeferredJobStore lifecycle).
     */
    public static void clearAll(MinecraftServer server) {
        long nowTick = server != null ? currentTick(server) : 0L;
        for (MemoryStore store : STORES.values()) {
            try {
                store.clear(nowTick);
            } catch (RuntimeException e) {
                PlayerEngine.LOGGER.warn("Memory: final flush failed ({}).", e.getClass().getSimpleName());
            }
        }
        STORES.clear();
    }

    /** Game-time read used as the compaction recency basis; best-effort 0 when unavailable. */
    private static long currentTick(MinecraftServer server) {
        try {
            return server.overworld() != null ? server.overworld().getGameTime() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
