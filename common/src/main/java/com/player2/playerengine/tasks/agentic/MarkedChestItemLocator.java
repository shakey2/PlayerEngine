package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.InventoryWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointItemCategorizer;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchDegradationReporter;
import com.player2.playerengine.agentic.elliegps.WaypointSearchOrder;
import com.player2.playerengine.agentic.elliegps.WaypointSearchResult;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointSearchStatus;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.containeraccess.ItemCount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic, item-generic EllieGPS inventory-waypoint lookup used by bounded acquisition tasks.
 * Candidate discovery is inventory-typed, snapshot-bearing, current-dimension, radius-bounded, and
 * exact-item filtered after the authoritative local search. No model call or command relay occurs.
 */
public final class MarkedChestItemLocator {
    private MarkedChestItemLocator() {
    }

    /** Availability-bearing snapshot used to exclude every marked half from unmarked world scans. */
    public record MarkedPositionSnapshot(boolean available, Set<BlockPos> positions) {
        public MarkedPositionSnapshot {
            positions = Set.copyOf(positions == null ? Set.of() : positions);
        }
    }

    /**
     * Resolves marked inventory waypoints whose frozen snapshot contains {@code item}, nearest first.
     */
    public static List<BlockPos> candidateCoordinates(
            PlayerEngineController controller,
            Item item,
            Vec3 origin,
            double radiusBlocks,
            String dimensionId) {
        try {
            if (item == null || origin == null || !validDimension(dimensionId)
                    || !Double.isFinite(radiusBlocks) || radiusBlocks < 0.0D) {
                WaypointSearchDegradationReporter.reportOnce(
                        controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
                return List.of();
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) {
                WaypointSearchDegradationReporter.reportOnce(
                        controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
                return List.of();
            }
            String registryId = key.toString();
            List<String> keywords = WaypointItemCategorizer.categorize(
                    List.of(new ItemCount(registryId, 1)));
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
            if (isTerminalSearchFailure(search.status())) {
                return List.of();
            }

            double radiusSq = radiusBlocks * radiusBlocks;
            LinkedHashSet<BlockPos> uniqueMatches = new LinkedHashSet<>();
            for (WaypointRecord record : search.records()) {
                if (!eligibleRecord(record, registryId, dimensionId)) {
                    continue;
                }
                BlockPos pos = record.canonicalBlockPos();
                if (pos != null && distSq(pos, origin) <= radiusSq) {
                    uniqueMatches.add(pos.immutable());
                }
            }
            ArrayList<BlockPos> matches = new ArrayList<>(uniqueMatches);
            matches.sort(positionComparator(origin));
            return List.copyOf(matches);
        } catch (RuntimeException searchFailure) {
            WaypointSearchDegradationReporter.reportOnce(
                    controller, WaypointSearchStatus.FAILED_SEARCH_ERROR);
            return List.of();
        }
    }

    /**
     * Captures every canonical and secondary inventory-waypoint position in one dimension.
     * Stale records remain exclusions: uncertainty may suppress a village-chest fallback but must
     * never cause a marked chest to be treated as an unmarked world source.
     */
    public static MarkedPositionSnapshot markedPositions(String dimensionId) {
        if (!validDimension(dimensionId)) {
            return new MarkedPositionSnapshot(false, Set.of());
        }
        EllieGPSStore store = EllieGPSStore.get();
        if (store == null) {
            return new MarkedPositionSnapshot(false, Set.of());
        }
        try {
            LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
            EllieGPSStore.BoundedSnapshot snapshot = store.boundedSnapshot(
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            if (!snapshot.available() || !snapshot.complete()) {
                return new MarkedPositionSnapshot(false, Set.of());
            }
            for (WaypointRecord record : snapshot.records()) {
                if (record == null
                        || !WaypointTypes.INVENTORY.equals(record.type)
                        || !dimensionId.equals(record.dimension)) {
                    continue;
                }
                BlockPos canonical = record.canonicalBlockPos();
                BlockPos secondary = record.secondaryBlockPos();
                if (canonical != null) {
                    positions.add(canonical.immutable());
                }
                if (secondary != null) {
                    positions.add(secondary.immutable());
                }
            }
            return new MarkedPositionSnapshot(true, positions);
        } catch (RuntimeException storeFailure) {
            return new MarkedPositionSnapshot(false, Set.of());
        }
    }

    static Comparator<BlockPos> positionComparator(Vec3 origin) {
        return Comparator
                .comparingDouble((BlockPos pos) -> distSq(pos, origin))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
    }

    private static boolean eligibleRecord(
            WaypointRecord record,
            String registryId,
            String dimensionId) {
        if (record == null
                || !WaypointTypes.INVENTORY.equals(record.type)
                || record.stale
                || !dimensionId.equals(record.dimension)) {
            return false;
        }
        InventoryWaypointData inventory = record.inventoryData();
        if (inventory == null || inventory.snapshot == null || inventory.snapshot.items == null) {
            return false;
        }
        for (ItemCount count : inventory.snapshot.items) {
            if (count != null && count.count() > 0 && registryId.equals(count.registryId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTerminalSearchFailure(WaypointSearchStatus status) {
        return status == WaypointSearchStatus.FAILED_SCAN_LIMIT
                || status == WaypointSearchStatus.FAILED_STORE_UNAVAILABLE
                || status == WaypointSearchStatus.FAILED_SEARCH_ERROR;
    }

    private static boolean validDimension(String dimensionId) {
        return dimensionId != null && !dimensionId.isBlank();
    }

    private static double distSq(BlockPos pos, Vec3 origin) {
        double dx = pos.getX() + 0.5D - origin.x;
        double dy = pos.getY() + 0.5D - origin.y;
        double dz = pos.getZ() + 0.5D - origin.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
