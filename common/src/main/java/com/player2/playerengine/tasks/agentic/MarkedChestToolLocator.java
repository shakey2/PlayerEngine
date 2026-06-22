package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.EllieGPSWaypointIndex;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointItemCategorizer;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.containeraccess.ItemCount;
import com.player2.playerengine.retrieval.RetrievalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * target pickaxe registry id, ordered nearest-first. It does NOT call or extend
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
 *       dimension, within radius, and the snapshot must list the pickaxe registry id.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 */
final class MarkedChestToolLocator {

    private MarkedChestToolLocator() {}

    /**
     * Resolves the candidate marked-chest coordinates that contain {@code pickaxe}, nearest-first.
     *
     * @param pickaxe      the target pickaxe item
     * @param origin       the bot position the search is anchored at
     * @param radiusBlocks the search radius
     * @param dimensionId  the bot's current dimension id ({@code level.dimension().location().toString()});
     *                     {@code null}/blank yields an empty result (conservative — never cross dimensions)
     * @return navigate-to {@link BlockPos} list ordered nearest-first; never {@code null}, possibly empty
     */
    static List<BlockPos> candidateCoordinates(
            Item pickaxe, Vec3 origin, double radiusBlocks, String dimensionId) {
        try {
            if (pickaxe == null || dimensionId == null || dimensionId.isBlank()) {
                return List.of();
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(pickaxe);
            if (key == null) {
                return List.of();
            }
            String registryId = key.toString();

            EllieGPSStore store = EllieGPSStore.get();
            if (store == null) {
                return List.of();
            }
            EllieGPSWaypointIndex index = EllieGPSWaypointIndex.getCurrent();
            if (index == null) {
                return List.of();
            }

            // Map the pickaxe registry id to the same categorizer vocabulary the records were indexed
            // under, then query the index for candidate waypoint ids (keyword pre-filter — never a
            // full-store fallback scan).
            List<String> keywords = WaypointItemCategorizer.categorize(List.of(new ItemCount(registryId, 1)));
            String query = String.join(" ", keywords).trim();
            if (query.isEmpty()) {
                return List.of();
            }
            List<RetrievalHit> hits = index.query(query, 10);
            if (hits.isEmpty()) {
                return List.of();
            }
            List<String> candidateIds = new ArrayList<>(hits.size());
            for (RetrievalHit hit : hits) {
                candidateIds.add(hit.toolId());
            }

            double radiusSq = radiusBlocks * radiusBlocks;
            List<BlockPos> matches = new ArrayList<>();
            for (WaypointRecord record : store.publishedRecords()) {
                if (!candidateIds.contains(record.id)) {
                    continue;
                }
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
                boolean listsPickaxe = false;
                for (ItemCount ic : inv.snapshot.items) {
                    if (ic != null && registryId.equals(ic.registryId()) && ic.count() > 0) {
                        listsPickaxe = true;
                        break;
                    }
                }
                if (listsPickaxe) {
                    matches.add(pos);
                }
            }

            matches.sort(Comparator.comparingDouble(p -> distSq(p, origin)));
            return matches;
        } catch (Exception e) {
            // Best-effort detection: any failure degrades to "no marked candidates".
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
