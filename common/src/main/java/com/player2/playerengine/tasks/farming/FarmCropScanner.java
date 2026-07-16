package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.FarmCropCount;
import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.FarmWaypointObservation;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Single authoritative live scanner for the fixed 9x9 farm footprint. */
public final class FarmCropScanner {
    private FarmCropScanner() {
    }

    /** One supported live crop cell, including its exact scan-time maturity. */
    public record CropCell(
            BlockPos position,
            String blockId,
            String plantingItemId,
            boolean fullyGrown) {
        public CropCell {
            position = Objects.requireNonNull(position, "position").immutable();
            blockId = Objects.requireNonNull(blockId, "blockId");
        }
    }

    /** Observation payload plus the per-cell maturity seam consumed by finite harvesting. */
    public record ScanResult(
            FarmObservationResult observationResult,
            List<CropCell> cropCells,
            List<BlockPos> openCells) {
        public ScanResult {
            observationResult = Objects.requireNonNull(observationResult, "observationResult");
            cropCells = List.copyOf(Objects.requireNonNull(cropCells, "cropCells"));
            openCells = Objects.requireNonNull(openCells, "openCells").stream()
                    .map(position -> Objects.requireNonNull(position, "open cell").immutable())
                    .toList();
        }

        public int matureCount() {
            return (int) cropCells.stream().filter(CropCell::fullyGrown).count();
        }

        public int recognizedCount() {
            return cropCells.size();
        }

        public int openCount() {
            return openCells.size();
        }

        /** Stable snake-order positions that must still pass a fresh action-time maturity gate. */
        public List<BlockPos> matureCandidates() {
            return cropCells.stream()
                    .filter(CropCell::fullyGrown)
                    .map(CropCell::position)
                    .toList();
        }
    }

    public static FarmObservationResult observe(ServerLevel level, WaypointRecord farm) {
        return scan(level, farm).observationResult();
    }

    public static ScanResult scan(ServerLevel level, WaypointRecord farm) {
        return scan(level, farm, FarmActionStateSource.LIVE);
    }

    /**
     * Observes the bounded farm footprint before center water exists.
     *
     * <p>Aggregate farmland, exact open capacity, and recognized-crop counts leave this scanner;
     * derived per-cell positions remain local to this scan and are not persisted in EllieGPS.
     */
    public static FarmObservationResult observePrepared(
            ServerLevel level,
            BlockPos center) {
        return observePrepared(level, center, FarmActionStateSource.LIVE);
    }

    static FarmObservationResult observePrepared(
            ServerLevel level,
            BlockPos center,
            FarmActionStateSource stateSource) {
        if (level == null) {
            return failure(FarmObservationStatus.HANDLER_UNAVAILABLE,
                    "farm world is unavailable").observationResult();
        }
        if (center == null) {
            return failure(FarmObservationStatus.UNSUPPORTED_DATA,
                    "farm center is invalid").observationResult();
        }
        if (stateSource == null) {
            return failure(FarmObservationStatus.HANDLER_UNAVAILABLE,
                    "farm state source is unavailable").observationResult();
        }
        if (!footprintLoaded(level, center)) {
            return failure(FarmObservationStatus.UNLOADED,
                    "farm footprint is not fully loaded").observationResult();
        }
        return scanLoadedFootprint(level, center, stateSource).observationResult();
    }

    public static ScanResult scan(
            ServerLevel level,
            WaypointRecord farm,
            FarmActionStateSource stateSource) {
        if (level == null) {
            return failure(FarmObservationStatus.HANDLER_UNAVAILABLE, "farm world is unavailable");
        }
        if (stateSource == null) {
            return failure(FarmObservationStatus.HANDLER_UNAVAILABLE, "farm state source is unavailable");
        }
        if (farm == null || farm.farmData() == null || !farm.farmData().isSupportedVersion()) {
            return failure(FarmObservationStatus.UNSUPPORTED_DATA, "farm waypoint data is unsupported");
        }
        FarmWaypointData data = farm.farmData();
        if (data.radius() != FarmPlotPolicy.HYDRATION_RADIUS) {
            return failure(FarmObservationStatus.UNSUPPORTED_DATA, "farm radius is not supported");
        }
        BlockPos center = farm.canonicalBlockPos();
        if (center == null) {
            return failure(FarmObservationStatus.UNSUPPORTED_DATA, "farm center is invalid");
        }
        String dimension = level.dimension().location().toString();
        if (!Objects.equals(dimension, farm.dimension)) {
            return failure(FarmObservationStatus.UNLOADED, "farm is in a different dimension");
        }

        if (!footprintLoaded(level, center)) {
            return failure(FarmObservationStatus.UNLOADED, "farm footprint is not fully loaded");
        }
        if (!FarmInteractionPostconditions.isStandaloneWaterSource(level.getBlockState(center))) {
            return failure(FarmObservationStatus.STALE_CENTER, "farm center is no longer a water source");
        }

        return scanLoadedFootprint(level, center, stateSource);
    }

    private static boolean footprintLoaded(ServerLevel level, BlockPos center) {
        for (BlockPos cell : FarmPlotGeometry.allCells(center)) {
            if (!level.hasChunkAt(cell) || !level.hasChunkAt(cell.above())) {
                return false;
            }
        }
        return true;
    }

    private static ScanResult scanLoadedFootprint(
            ServerLevel level,
            BlockPos center,
            FarmActionStateSource stateSource) {
        String dimension = level.dimension().location().toString();

        int farmland = 0;
        ArrayList<ObservedCropState> observedCropStates =
                new ArrayList<>(FarmPlotPolicy.SOIL_CELL_COUNT);
        ArrayList<BlockPos> openCells = new ArrayList<>(FarmPlotPolicy.SOIL_CELL_COUNT);
        for (BlockPos soil : FarmPlotGeometry.soilCells(center)) {
            BlockState soilState = level.getBlockState(soil);
            BlockPos cropPosition = soil.above().immutable();
            BlockState cropState = Objects.requireNonNull(
                    stateSource.read(level, cropPosition), "farm state source returned null");
            if (soilState.is(Blocks.FARMLAND)) {
                farmland++;
                if (isOpenCell(soilState, cropState)) {
                    openCells.add(cropPosition);
                }
            }
            observedCropStates.add(new ObservedCropState(cropPosition, cropState));
        }

        List<CropCell> cropCells = enumerateRecognizedStates(
                level, observedCropStates, HarvestBehaviorRegistry.DEFAULT);
        List<FarmCropCount> crops = aggregateCounts(cropCells);

        FarmWaypointObservation observation = new FarmWaypointObservation(
                dimension,
                center,
                FarmPlotPolicy.HYDRATION_RADIUS,
                farmland,
                openCells.size(),
                crops,
                level.getGameTime());
        return new ScanResult(
                new FarmObservationResult(FarmObservationStatus.OBSERVED, observation, ""),
                cropCells,
                openCells);
    }

    static List<FarmCropCount> aggregateCounts(List<CropCell> cropCells) {
        Objects.requireNonNull(cropCells, "cropCells");
        TreeMap<CropKey, Integer> counts = new TreeMap<>();
        for (CropCell cropCell : cropCells) {
            Objects.requireNonNull(cropCell, "cropCell");
            counts.merge(new CropKey(cropCell.blockId(), cropCell.plantingItemId()),
                    1, Math::addExact);
        }

        ArrayList<FarmCropCount> crops = new ArrayList<>(counts.size());
        for (Map.Entry<CropKey, Integer> entry : counts.entrySet()) {
            crops.add(new FarmCropCount(
                    entry.getKey().blockId(), entry.getKey().plantingItemId(), entry.getValue()));
        }
        return List.copyOf(crops);
    }

    /** Shared pure enumeration seam used by production scans and deterministic race fixtures. */
    static List<CropCell> enumerateRecognized(
            LevelReader level,
            Iterable<BlockPos> cropPositions,
            FarmActionStateSource stateSource,
            HarvestBehaviorRegistry registry) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cropPositions, "cropPositions");
        Objects.requireNonNull(stateSource, "stateSource");
        Objects.requireNonNull(registry, "registry");
        ArrayList<ObservedCropState> observed = new ArrayList<>();
        for (BlockPos position : cropPositions) {
            BlockPos cropPos = Objects.requireNonNull(position, "crop position").immutable();
            BlockState cropState = Objects.requireNonNull(
                    stateSource.read(level, cropPos), "farm state source returned null");
            observed.add(new ObservedCropState(cropPos, cropState));
        }
        return enumerateRecognizedStates(level, observed, registry);
    }

    private static List<CropCell> enumerateRecognizedStates(
            LevelReader level,
            Iterable<ObservedCropState> observedStates,
            HarvestBehaviorRegistry registry) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(observedStates, "observedStates");
        Objects.requireNonNull(registry, "registry");
        ArrayList<CropCell> cells = new ArrayList<>();
        ResourceLocation airId = BuiltInRegistries.BLOCK.getKey(Blocks.AIR);
        for (ObservedCropState observed : observedStates) {
            BlockPos cropPos = observed.position();
            BlockState cropState = observed.state();
            HarvestBehavior behavior = registry.resolve(cropState).orElse(null);
            if (behavior == null) {
                continue;
            }
            ResourceLocation blockId = behavior.canonicalCropId(cropState).orElse(null);
            if (blockId == null || blockId.equals(airId)) {
                continue;
            }
            String plantingItemId = behavior.plantingItemId(level, cropPos, cropState)
                    .map(ResourceLocation::toString)
                    .orElse(null);
            cells.add(new CropCell(
                    cropPos,
                    blockId.toString(),
                    plantingItemId,
                    behavior.isMature(cropState)));
        }
        return List.copyOf(cells);
    }

    /** Exact planting-capacity predicate: valid farmland support with air immediately above. */
    static boolean isOpenCell(BlockState soilState, BlockState cropState) {
        return Objects.requireNonNull(soilState, "soilState").is(Blocks.FARMLAND)
                && Objects.requireNonNull(cropState, "cropState").isAir();
    }

    static boolean isFullyGrown(CropBlock crop, BlockState state) {
        return Objects.requireNonNull(crop, "crop").isMaxAge(
                Objects.requireNonNull(state, "state"));
    }

    private static ScanResult failure(FarmObservationStatus status, String reason) {
        return new ScanResult(
                new FarmObservationResult(status, null, reason), List.of(), List.of());
    }

    private record ObservedCropState(BlockPos position, BlockState state) {
        private ObservedCropState {
            position = Objects.requireNonNull(position, "position").immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    private record CropKey(String blockId, String plantingItemId) implements Comparable<CropKey> {
        @Override
        public int compareTo(CropKey other) {
            int blockOrder = blockId.compareTo(other.blockId);
            if (blockOrder != 0) {
                return blockOrder;
            }
            return Objects.toString(plantingItemId, "")
                    .compareTo(Objects.toString(other.plantingItemId, ""));
        }
    }
}
