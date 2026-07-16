package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.containeraccess.ItemCount;
import com.player2.playerengine.util.ItemTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Real EllieGPS counting service backed by structured {@link WaypointSearchService} results and
 * {@link EllieGPSStore} inventory snapshots (Part C5, WS2).
 *
 * <p><b>Decision 11 semantics (pinned):</b>
 * <ul>
 *   <li>The constructor takes nothing; reads only the static volatile store and index holders.</li>
 *   <li>The frozen 3-arg {@link EllieGPSCountingService} interface method delegates to the
 *       dimension-aware 4-arg overload with {@code dimensionId = null}, which always returns 0
 *       (conservative: never match across dimensions).</li>
 *   <li>The 4-arg overload is the real computation path, called by
 *       {@link com.player2.playerengine.util.helpers.MaterialAvailability#count} via an
 *       {@code instanceof} branch.</li>
 *   <li>Filtering: inventory-type, non-stale, snapshot-bearing, matching dimension, within
 *       radius. Count is the sum of snapshot item counts for exact registry-id matches to the
 *       {@code target}'s {@link ItemTarget#getMatches()} items.</li>
 *   <li>Keyword-only waypoints (null snapshot) contribute 0 permanently.</li>
 *   <li>Total catch-all: never throws to the caller. Returns 0 on any error.</li>
 * </ul>
 *
 * <p><b>Startup swap:</b> installed once at {@code SERVER_STARTING} into
 * {@link com.player2.playerengine.util.helpers.MaterialAvailability} via
 * {@code setWaypointSource}. Never reversed mid-run; the {@code ellieGpsEnabled} toggle is
 * enforced per-call inside {@code MaterialAvailability.count()}.
 *
 * <p><b>The query path never throws or performs network/model work.</b> Relevance lookup may make
 * one synchronous attempt to repair the derived index before using its deterministic in-memory
 * fallback; exact item counts always come from authoritative snapshot data.
 */
public final class EllieGPSWaypointCountingService implements EllieGPSCountingService {

    /**
     * No-arg constructor. Reads only the static volatile holders at query time;
     * safe to construct once at startup.
     */
    public EllieGPSWaypointCountingService() {
        // intentionally empty — reads static holders at query time
    }

    // -------------------------------------------------------------------------
    // EllieGPSCountingService interface (frozen 3-arg method)
    // -------------------------------------------------------------------------

    /**
     * Delegates to the dimension-aware 4-arg overload with {@code dimensionId = null}.
     * A null dimension always returns 0 (conservative; never guess a dimension).
     */
    @Override
    public int estimateNearbyWaypointItems(ItemTarget target, Vec3 origin, double radiusBlocks) {
        return estimateNearbyWaypointItems(target, origin, radiusBlocks, null);
    }

    // -------------------------------------------------------------------------
    // Public 4-arg dimension-aware overload (Decision 11)
    // -------------------------------------------------------------------------

    /**
     * Dimension-aware variant of {@link #estimateNearbyWaypointItems}. Called by
     * {@link com.player2.playerengine.util.helpers.MaterialAvailability#count} via an
     * {@code instanceof EllieGPSWaypointCountingService} branch so the 4-arg signature can be
     * invoked without changing the frozen interface.
     *
     * <p>Returns 0 when:
     * <ul>
     *   <li>{@code dimensionId} is null or blank (conservative default);</li>
     *   <li>No world is loaded (store is null);</li>
     *   <li>The bounded structured search yields no inventory candidates or exceeds its hard
     *       authoritative-record cap;</li>
     *   <li>No waypoint in the store matches the dimension, is non-stale, has a snapshot, and
     *       is within {@code radiusBlocks} of {@code origin};</li>
     *   <li>Any exception is caught.</li>
     * </ul>
     *
     * @param target       the item target the caller is short on
     * @param origin       world position the search is anchored at
     * @param radiusBlocks upper bound on which waypoints are considered
     * @param dimensionId  the bot's current dimension string
     *                     ({@code level.dimension().location().toString()}); null returns 0
     * @return best-effort non-negative count; 0 when unknown or no matching snapshot
     */
    public int estimateNearbyWaypointItems(
            ItemTarget target, Vec3 origin, double radiusBlocks, String dimensionId) {
        return estimateNearbyWaypointItemsChecked(target, origin, radiusBlocks, dimensionId).count();
    }

    /**
     * Checked variant of the dimension-aware count. The count remains conservative and
     * non-negative, while {@link WaypointCountResult#status()} lets internal consumers surface
     * bounded-search degradation instead of silently treating it as an ordinary zero.
     */
    public WaypointCountResult estimateNearbyWaypointItemsChecked(
            ItemTarget target, Vec3 origin, double radiusBlocks, String dimensionId) {
        WaypointSearchStatus status = WaypointSearchStatus.INDEXED;
        try {
            if (dimensionId == null || dimensionId.isBlank() || origin == null) {
                return new WaypointCountResult(
                        0, WaypointSearchStatus.FAILED_SEARCH_ERROR);
            }

            EllieGPSStore store = EllieGPSStore.get();
            if (store == null) {
                return new WaypointCountResult(
                        0, WaypointSearchStatus.FAILED_STORE_UNAVAILABLE);
            }

            // Build the set of target registry IDs for exact-identity matching.
            List<String> targetRegistryIds = buildRegistryIds(target);
            if (targetRegistryIds.isEmpty()) {
                return new WaypointCountResult(0, status);
            }

            // Request the complete bounded inventory candidate corpus. Exact item and radius
            // checks remain below, after structured type/dimension/stale filtering.
            String query = buildSearchQuery(targetRegistryIds);
            if (query.isEmpty()) {
                return new WaypointCountResult(0, status);
            }

            WaypointSearchResult search = WaypointSearchService.find(
                    query,
                    dimensionId,
                    Set.of(WaypointTypes.INVENTORY),
                    false,
                    WaypointSearchOrder.RELEVANCE,
                    null,
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            status = search.status();
            if (status == WaypointSearchStatus.FAILED_SCAN_LIMIT
                    || status == WaypointSearchStatus.FAILED_STORE_UNAVAILABLE
                    || status == WaypointSearchStatus.FAILED_SEARCH_ERROR) {
                return new WaypointCountResult(0, status);
            }

            double radiusSq = radiusBlocks * radiusBlocks;
            long total = 0;

            for (WaypointRecord record : search.records()) {
                // Filter: inventory type
                if (!WaypointTypes.INVENTORY.equals(record.type)) {
                    continue;
                }
                // Filter: not stale
                if (record.stale) {
                    continue;
                }
                // Filter: matching dimension
                if (!dimensionId.equals(record.dimension)) {
                    continue;
                }
                // Filter: must have a snapshot (keyword-only records contribute 0)
                InventoryWaypointData inv = record.inventoryData();
                if (inv == null || inv.snapshot == null) {
                    continue;
                }
                // Filter: within radiusBlocks of origin
                if (!withinRadius(record, origin, radiusSq)) {
                    continue;
                }
                // Sum exact-identity matches from the snapshot
                if (inv.snapshot.items != null) {
                    for (ItemCount ic : inv.snapshot.items) {
                        if (ic != null && ic.count() > 0
                                && targetRegistryIds.contains(ic.registryId())) {
                            total = Math.min(Integer.MAX_VALUE, total + ic.count());
                        }
                    }
                }
            }

            return new WaypointCountResult((int) total, status);
        } catch (Exception e) {
            PlayerEngine.LOGGER.warn("EllieGPS counting: estimateNearbyWaypointItems failed: {}", e.getMessage());
            return new WaypointCountResult(
                    0, WaypointSearchStatus.FAILED_SEARCH_ERROR);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the list of full registry id strings from the target's matched items.
     * Unresolvable items (item is null) are skipped.
     */
    private static List<String> buildRegistryIds(ItemTarget target) {
        if (target == null) return List.of();
        Item[] matches = target.getMatches();
        if (matches == null || matches.length == 0) return List.of();
        List<String> ids = new ArrayList<>(matches.length);
        for (Item item : matches) {
            if (item == null) continue;
            try {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null) {
                    ids.add(key.toString());
                }
            } catch (Exception e) {
                // Ignore unresolvable items — degrade gracefully
            }
        }
        return ids;
    }

    /** Maps target registry IDs to the deterministic vocabulary used by waypoint documents. */
    private static String buildSearchQuery(List<String> registryIds) {
        List<ItemCount> asCounts = new ArrayList<>(registryIds.size());
        for (String rid : registryIds) {
            asCounts.add(new ItemCount(rid, 1));
        }
        List<String> keywords = WaypointItemCategorizer.categorize(asCounts);
        return String.join(" ", keywords).trim();
    }

    /**
     * Returns true if the record's canonical position is within {@code radiusSq} of
     * {@code origin}. Uses the canonical position only; secondary position matching is for
     * key lookup, not radius checks.
     */
    private static boolean withinRadius(WaypointRecord record, Vec3 origin, double radiusSq) {
        if (record.pos == null || record.pos.length < 3) return false;
        double dx = record.pos[0] + 0.5 - origin.x;
        double dy = record.pos[1] + 0.5 - origin.y;
        double dz = record.pos[2] + 0.5 - origin.z;
        return (dx * dx + dy * dy + dz * dz) <= radiusSq;
    }
}
