package com.player2.playerengine.retrieval;

import com.player2.playerengine.automaton.utils.DirUtil;
import com.player2.playerengine.retrieval.overlay.ToolOverlay;
import com.player2.playerengine.retrieval.overlay.ToolOverlayLoader;
import com.player2.playerengine.retrieval.overlay.ToolOverlayMerger;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-owner-aware holder for {@link ToolRetriever} instances.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>{@link #globalInstance} — baseline seed metadata + global overlay
 *       ({@code config/playerengine/tool_overrides.json}). Disk-cached at
 *       {@code config/playerengine/rag_index/}; survives restarts without rebuild cost.
 *   <li>{@link #perOwnerCache} — per-owner retrievers built in-memory by merging the same
 *       global overlay with the per-owner overlay
 *       ({@code world/player2npc/.../owners/<uuid>/tool_overrides.json}). Lazy-built on
 *       first access; evicted and rebuilt whenever {@link #reloadAll} is called.
 * </ul>
 *
 * <h3>Reload race semantics</h3>
 * Callers that hold a reference to a retrieved {@link ToolRetriever} continue using the old
 * instance for the duration of their retrieval call. The volatile write in
 * {@link #reloadGlobal()} and the {@link ConcurrentHashMap} eviction in
 * {@link #invalidateOwnerCache()} ensure subsequent callers observe the new instance.
 * No in-flight retrieval is interrupted; no lock is held during the retrieval itself.
 *
 * <h3>Thread safety</h3>
 * {@link #initialize()} and {@link #reloadGlobal()} are {@code synchronized} to avoid
 * duplicate builds. {@link #getForOwner} uses {@code ConcurrentHashMap.computeIfAbsent}
 * which guarantees at-most-one build per key (may build twice in a race but both results
 * are equivalent).
 */
public final class RagIndex {

    private static final Logger LOGGER = LogManager.getLogger(RagIndex.class);

    /** Global retriever: baseline + global overlay, disk-cached. */
    private static volatile ToolRetriever globalInstance;

    /**
     * Per-owner retriever cache: global + per-owner overlay, in-memory only.
     * Evicted on reload; lazy rebuilt on next access.
     */
    private static final Map<UUID, ToolRetriever> perOwnerCache = new ConcurrentHashMap<>();

    private RagIndex() {}

    // -------------------------------------------------------------------------
    // Initialisation (called once at mod init)
    // -------------------------------------------------------------------------

    /**
     * Builds or loads the global retriever. Safe to call multiple times; subsequent calls
     * are no-ops if {@link #globalInstance} is already populated.
     *
     * <p>Called from {@code PlayerEngine.onInitialize()} after command registration.
     */
    public static synchronized void initialize() {
        if (globalInstance != null) return;
        globalInstance = buildGlobal();
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the global retriever (baseline + global overlay), or {@code null} if
     * {@link #initialize()} has not been called or failed.
     */
    public static ToolRetriever getGlobal() {
        return globalInstance;
    }

    /**
     * Returns the per-owner retriever for {@code ownerUuid}, building it lazily on first
     * access. Falls back to {@link #getGlobal()} if the build fails.
     *
     * <p>If the owner has no per-owner overlay file, the returned retriever is identical in
     * results to the global retriever (global overlay applied, no extra overlay on top).
     *
     * @param server   the running {@link MinecraftServer} (used to locate the world directory)
     * @param ownerUuid the owner whose overlay should be applied
     * @return a non-null retriever; falls back to global on error
     */
    public static ToolRetriever getForOwner(MinecraftServer server, UUID ownerUuid) {
        if (ownerUuid == null || server == null) return getGlobal();
        return perOwnerCache.computeIfAbsent(ownerUuid, uuid -> {
            try {
                return buildForOwner(server, uuid);
            } catch (Exception e) {
                LOGGER.warn("RAG: per-owner build failed for {} — falling back to global: {}",
                        uuid, e.getMessage());
                return globalInstance;
            }
        });
    }

    /**
     * @deprecated Use {@link #getGlobal()} or {@link #getForOwner(MinecraftServer, UUID)}.
     *             Retained for call-sites that have not yet been migrated.
     */
    @Deprecated
    public static ToolRetriever get() {
        return globalInstance;
    }

    // -------------------------------------------------------------------------
    // Reload / invalidation
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the global retriever from disk overlays and atomically replaces
     * {@link #globalInstance}. Bypasses the {@link #initialize()} early-return guard.
     *
     * <p>Callers that already hold a reference to the old instance are unaffected;
     * subsequent callers receive the new instance.
     */
    public static synchronized void reloadGlobal() {
        LOGGER.info("RAG: reloading global retriever.");
        globalInstance = buildGlobal();
    }

    /**
     * Evicts all per-owner cached retrievers. Each owner's next access rebuilds lazily.
     *
     * <p>Called as part of {@link #reloadAll(MinecraftServer)} because any overlay edit
     * (global or per-owner) can affect any owner's merged view.
     */
    public static void invalidateOwnerCache() {
        int count = perOwnerCache.size();
        perOwnerCache.clear();
        LOGGER.info("RAG: evicted {} per-owner retriever(s) from cache.", count);
    }

    /**
     * Evicts a single owner from the cache. Useful when the B5 learning loop writes
     * a new overlay for a specific owner so only that owner pays the rebuild cost.
     *
     * @param ownerUuid the owner whose cache entry to evict
     */
    public static void invalidateOwner(UUID ownerUuid) {
        if (ownerUuid != null && perOwnerCache.remove(ownerUuid) != null) {
            LOGGER.debug("RAG: evicted per-owner retriever for {}.", ownerUuid);
        }
    }

    /**
     * Full reload: rebuilds the global retriever and evicts all per-owner caches.
     * Equivalent to {@link #reloadGlobal()} + {@link #invalidateOwnerCache()}.
     *
     * <p>Used by {@code /playerengine rag reload}. After this call, all subsequent
     * retrieval calls will use the freshly-loaded overlay data.
     *
     * @param server the running server (passed through to per-owner lazy builds)
     */
    public static void reloadAll(MinecraftServer server) {
        reloadGlobal();
        invalidateOwnerCache();
        LOGGER.info("RAG: full reload complete. Global token: {}...",
                globalInstance != null ? tokenShort() : "null");
    }

    // -------------------------------------------------------------------------
    // Internal build helpers
    // -------------------------------------------------------------------------

    private static ToolRetriever buildGlobal() {
        try {
            List<ToolOverlay> overlays = new ArrayList<>();
            ToolOverlayLoader.loadGlobal().ifPresent(overlays::add);

            Collection<ToolDocument> merged = ToolOverlayMerger.merge(SeedToolMetadata.all(), overlays);
            ToolMetadataRegistry registry = ToolMetadataRegistry.create(merged);
            Path indexDir = DirUtil.getConfigDir().resolve("playerengine").resolve("rag_index");
            ToolRetriever retriever = ToolRetriever.loadOrRebuild(registry, indexDir);
            LOGGER.info("RAG: global retriever ready ({} documents, token {}...).",
                    retriever.documentCount(), registry.getVersionToken().substring(0, 8));
            return retriever;
        } catch (Exception e) {
            LOGGER.error("RAG: global build failed — retrieval unavailable: {}", e.getMessage(), e);
            return null;
        }
    }

    private static ToolRetriever buildForOwner(MinecraftServer server, UUID ownerUuid) {
        List<ToolOverlay> overlays = new ArrayList<>();
        ToolOverlayLoader.loadGlobal().ifPresent(overlays::add);
        ToolOverlayLoader.loadPerOwner(server, ownerUuid).ifPresent(overlays::add);

        Collection<ToolDocument> merged = ToolOverlayMerger.merge(SeedToolMetadata.all(), overlays);
        ToolMetadataRegistry registry = ToolMetadataRegistry.create(merged);
        ToolRetriever retriever = ToolRetriever.buildInMemory(registry);
        LOGGER.debug("RAG: per-owner retriever built for {} ({} documents).",
                ownerUuid, retriever.documentCount());
        return retriever;
    }

    private static String tokenShort() {
        ToolRetriever g = globalInstance;
        if (g == null) return "null";
        // globalInstance does not expose its registry directly; we just show document count
        return g.documentCount() + "docs";
    }
}
