package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointItemCategorizer;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchOrder;
import com.player2.playerengine.agentic.elliegps.WaypointSearchDegradationReporter;
import com.player2.playerengine.agentic.elliegps.WaypointSearchResult;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.containeraccess.ItemCount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic keyword&rarr;candidate-coordinate resolution over the EllieGPS index and store for the
 * tool-acquisition pipeline's marked-chest stage (WS3, stage b).
 *
 * <p>This is the same static accessor chain the counting service
 * ({@link com.player2.playerengine.agentic.elliegps.EllieGPSWaypointCountingService}) uses, but it
 * returns the navigate-to <em>coordinates</em> of marked inventory waypoints that actually list the
 * target tool registry id, ordered nearest-first. It does NOT call or extend
 * {@code LocateWaypointsCommand} (which only returns chat text).
 *
 * <p><b>Design invariants (HARD):</b>
 * <ul>
 *   <li>100% deterministic, on-device, snapshot-bearing. No model calls. Never throws to the caller
 *       (returns an empty list on any failure).</li>
 *   <li>Navigate-to coordinate is read via {@link WaypointRecord#canonicalBlockPos()} ONLY — never
 *       {@code record.secondaryPos} / {@link WaypointRecord#secondaryBlockPos()} (that is only a
 *       double chest's second half, not the navigate target).</li>
 *   <li>Same filter as the counting service: inventory-type, non-stale, snapshot-bearing, matching
 *       dimension, within radius, and the snapshot must list the tool registry id.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 */
final class MarkedChestToolLocator {

    private MarkedChestToolLocator() {}

    /**
     * Resolves candidate marked-chest coordinates that contain {@code tool}, nearest-first.
     *
     * @param controller   controller used for one-shot player/model degradation feedback
     * @param tool         the target tool item
     * @param origin       the bot position the search is anchored at
     * @param radiusBlocks the search radius
     * @param dimensionId  the bot's current dimension id ({@code level.dimension().location().toString()});
     *                     {@code null}/blank yields an empty result (conservative — never cross dimensions)
     * @return navigate-to {@link BlockPos} list ordered nearest-first; never {@code null}, possibly empty
     */
    static List<BlockPos> candidateCoordinates(
            PlayerEngineController controller,
            Item tool,
            Vec3 origin,
            double radiusBlocks,
            String dimensionId) {
        try {
            if (tool == null || origin == null
                    || dimensionId == null || dimensionId.isBlank()) {
                WaypointSearchDegradationReporter.reportOnce(
                        controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
                return List.of();
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(tool);
            if (key == null) {
                WaypointSearchDegradationReporter.reportOnce(
                        controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
                return List.of();
            }
            String registryId = key.toString();

            // Map the registry id to the indexed vocabulary, then request the complete bounded
            // inventory corpus before applying exact item and radius checks.
            List<String> keywords = WaypointItemCategorizer.categorize(List.of(new ItemCount(registryId, 1)));
            String query = String.join(" ", keywords).trim();
            if (query.isEmpty()) {
                WaypointSearchDegradationReporter.reportOnce(
                        controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
                return List.of();
            }
            WaypointSearchResult search = WaypointSearchService.find(
                    query,
                    dimensionId,
                    Set.of(WaypointTypes.INVENTORY),
                    false,
                    WaypointSearchOrder.RELEVANCE,
                    null,
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            WaypointSearchDegradationReporter.reportOnce(controller, search.status());
            if (search.status() == WaypointSearchStatus.FAILED_SCAN_LIMIT
                    || search.status() == WaypointSearchStatus.FAILED_STORE_UNAVAILABLE
                    || search.status() == WaypointSearchStatus.FAILED_SEARCH_ERROR) {
                return List.of();
            }

            double radiusSq = radiusBlocks * radiusBlocks;
            List<BlockPos> matches = new ArrayList<>();
            for (WaypointRecord record : search.records()) {
                if (!WaypointTypes.INVENTORY.equals(record.type)) {
                    continue;
                }
                if (record.stale) {
                    continue;
                }
                if (!dimensionId.equals(record.dimension)) {
                    continue;
                }
                InventoryWaypointData inv = record.inventoryData();
                if (inv == null || inv.snapshot == null || inv.snapshot.items == null) {
                    continue;
                }
                BlockPos pos = record.canonicalBlockPos();
                if (pos == null) {
                    continue;
                }
                if (!withinRadius(pos, origin, radiusSq)) {
                    continue;
                }
                boolean listsTool = false;
                for (ItemCount ic : inv.snapshot.items) {
                    if (ic != null && registryId.equals(ic.registryId()) && ic.count() > 0) {
                        listsTool = true;
                        break;
                    }
                }
                if (listsTool) {
                    matches.add(pos);
                }
            }

            matches.sort(Comparator
                    .comparingDouble((BlockPos p) -> distSq(p, origin))
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getZ));
            return matches;
        } catch (Exception e) {
            WaypointSearchDegradationReporter.reportOnce(
                    controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
            return List.of();
        }
    }

    private static boolean withinRadius(BlockPos pos, Vec3 origin, double radiusSq) {
        return distSq(pos, origin) <= radiusSq;
    }

    private static double distSq(BlockPos pos, Vec3 origin) {
        double dx = pos.getX() + 0.5 - origin.x;
        double dy = pos.getY() + 0.5 - origin.y;
        double dz = pos.getZ() + 0.5 - origin.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
