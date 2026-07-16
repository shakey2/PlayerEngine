package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointSearchService;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * Immutable, fail-closed snapshot of every registered farm's horizontal 9x9 footprint.
 * Stale and future-payload records remain exclusions: registration uncertainty may suppress a
 * source, but it may never authorize harvesting from a plot the bot has already recorded.
 */
public final class RegisteredFarmExclusionSnapshot {
    private static volatile CachedSnapshot cached;

    private final boolean available;
    private final List<BlockPos> centers;

    private RegisteredFarmExclusionSnapshot(boolean available, List<BlockPos> centers) {
        this.available = available;
        this.centers = List.copyOf(centers);
    }

    public static RegisteredFarmExclusionSnapshot capture(String dimensionId) {
        EllieGPSStore store = EllieGPSStore.get();
        if (store == null) {
            return unavailable();
        }
        try {
            long generation = store.mutationGeneration();
            CachedSnapshot current = cached;
            if (current != null
                    && current.store() == store
                    && current.generation() == generation
                    && Objects.equals(current.dimensionId(), dimensionId)) {
                return current.snapshot();
            }
            EllieGPSStore.BoundedSnapshot bounded = store.boundedSnapshot(
                    WaypointSearchService.MAX_AUTHORITATIVE_RECORDS);
            RegisteredFarmExclusionSnapshot snapshot =
                    !bounded.available() || !bounded.complete()
                            ? unavailable()
                            : fromRecords(dimensionId, bounded.records());
            if (store.mutationGeneration() != generation) {
                return unavailable();
            }
            cached = new CachedSnapshot(store, generation, dimensionId, snapshot);
            return snapshot;
        } catch (RuntimeException storeFailure) {
            return unavailable();
        }
    }

    static RegisteredFarmExclusionSnapshot fromRecords(
            String dimensionId,
            Iterable<WaypointRecord> records) {
        if (dimensionId == null || dimensionId.isBlank() || records == null) {
            return unavailable();
        }
        ArrayList<BlockPos> centers = new ArrayList<>();
        int inspected = 0;
        for (WaypointRecord record : records) {
            if (++inspected > WaypointSearchService.MAX_AUTHORITATIVE_RECORDS) {
                return unavailable();
            }
            if (record == null
                    || !WaypointTypes.FARM.equals(record.type)
                    || !dimensionId.equals(record.dimension)) {
                continue;
            }
            BlockPos center = record.canonicalBlockPos();
            if (center != null) {
                centers.add(center.immutable());
            }
        }
        centers.sort((first, second) -> {
            int x = Integer.compare(first.getX(), second.getX());
            if (x != 0) {
                return x;
            }
            int y = Integer.compare(first.getY(), second.getY());
            return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
        });
        return new RegisteredFarmExclusionSnapshot(true, centers);
    }

    public static RegisteredFarmExclusionSnapshot unavailable() {
        return new RegisteredFarmExclusionSnapshot(false, List.of());
    }

    public boolean available() {
        return available;
    }

    public int footprintCount() {
        return centers.size();
    }

    /**
     * Horizontal membership deliberately ignores Y: a crop, chest, or supporting soil anywhere
     * over/under a registered 9x9 column is conservatively excluded as a world source.
     */
    public boolean contains(BlockPos position) {
        Objects.requireNonNull(position, "position");
        if (!available) {
            return true;
        }
        for (BlockPos center : centers) {
            if (Math.abs(position.getX() - center.getX()) <= FarmPlotPolicy.HYDRATION_RADIUS
                    && Math.abs(position.getZ() - center.getZ()) <= FarmPlotPolicy.HYDRATION_RADIUS) {
                return true;
            }
        }
        return false;
    }

    List<BlockPos> centers() {
        return centers;
    }

    private record CachedSnapshot(
            EllieGPSStore store,
            long generation,
            String dimensionId,
            RegisteredFarmExclusionSnapshot snapshot) {
    }
}
