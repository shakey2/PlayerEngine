package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.commands.BlockScanner;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import com.player2.playerengine.tasks.agentic.MarkedChestItemLocator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * Freezes finite, stage-local source manifests for planting-item acquisition. Marked storage is
 * frozen once against the farm request's original anchor. Unmarked world sources are frozen in
 * bounded rounds against the NPC's current position after each bounded exploration leg. Discovery
 * consumes only the current BlockScanner snapshot and loaded chunks; it never invokes locate
 * commands, structure generation, teleportation, model calls, or chest-content inspection.
 */
public final class PlantableSourcePlanner {
    public static final int MAX_MARKED_CHESTS = 32;
    public static final int MAX_SCANNER_VISITS = 512;
    public static final int MAX_NATURAL_GRASS = MAX_SCANNER_VISITS;
    public static final int MAX_VILLAGE_CROPS = MAX_SCANNER_VISITS;
    public static final int MAX_VILLAGE_CHESTS = MAX_SCANNER_VISITS;
    public static final int MAX_SCANNER_CYCLE_VISITS = 8_192;
    public static final int MAX_RECOGNIZED_BLOCK_TYPES = 4096;
    public static final int MAX_STRUCTURE_REFERENCE_TYPES = 256;
    public static final int MAX_STRUCTURE_REFERENCES = 512;
    public static final double WORLD_SEARCH_RADIUS =
            ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS;

    public enum SourceKind {
        MARKED_CHEST,
        NATURAL_GRASS,
        VILLAGE_CROP,
        VILLAGE_CHEST
    }

    public record BreakCandidate(
            SourceKind kind,
            BlockPos position,
            BlockState expectedState) {
        public BreakCandidate {
            if (kind != SourceKind.NATURAL_GRASS && kind != SourceKind.VILLAGE_CROP) {
                throw new IllegalArgumentException("break candidate requires a break source kind");
            }
            position = Objects.requireNonNull(position, "position").immutable();
            expectedState = Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    public record ChestCandidate(BlockPos position, BlockPos secondaryPosition) {
        public ChestCandidate {
            position = Objects.requireNonNull(position, "position").immutable();
            secondaryPosition = secondaryPosition == null ? null : secondaryPosition.immutable();
        }
    }

    public record MarkedManifest(List<BlockPos> markedChests) {
        public MarkedManifest {
            markedChests = immutablePositions(markedChests);
        }
    }

    public record WorldRoundManifest(
            BlockPos scanOrigin,
            List<BreakCandidate> naturalGrass,
            List<BreakCandidate> villageCrops,
            List<ChestCandidate> villageChests,
            boolean worldGuardsAvailable,
            boolean discoveryTruncated,
            int nextNaturalScanCursor,
            int nextCropScanCursor,
            int nextVillageChestScanCursor) {
        public WorldRoundManifest {
            scanOrigin = Objects.requireNonNull(scanOrigin, "scanOrigin").immutable();
            naturalGrass = List.copyOf(naturalGrass == null ? List.of() : naturalGrass);
            villageCrops = List.copyOf(villageCrops == null ? List.of() : villageCrops);
            villageChests = List.copyOf(villageChests == null ? List.of() : villageChests);
            if (nextNaturalScanCursor < 0
                    || nextCropScanCursor < 0
                    || nextVillageChestScanCursor < 0) {
                throw new IllegalArgumentException("scanner cursors cannot be negative");
            }
        }
    }

    private PlantableSourcePlanner() {
    }

    /** Freeze the one-shot marked-storage stage against the request's immutable farm anchor. */
    public static MarkedManifest freezeMarked(
            PlayerEngineController controller,
            Item plantingItem,
            BlockPos origin,
            String dimensionId) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(plantingItem, "plantingItem");
        BlockPos frozenOrigin = Objects.requireNonNull(origin, "origin").immutable();
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }

        Vec3 originVec = Vec3.atCenterOf(frozenOrigin);
        return new MarkedManifest(capped(
                MarkedChestItemLocator.candidateCoordinates(
                        controller,
                        plantingItem,
                        originVec,
                        ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS,
                        dimensionId),
                MAX_MARKED_CHESTS));
    }

    /**
     * Freeze one world-discovery round around the NPC's current position. Attempted sets belong to
     * the logical acquisition checkpoint and prevent candidates from reappearing in later rounds.
     */
    public static WorldRoundManifest freezeWorldRound(
            PlayerEngineController controller,
            Item plantingItem,
            BlockPos scanOrigin,
            String dimensionId,
            Set<BlockPos> attemptedNaturalGrass,
            Set<BlockPos> attemptedVillageCrops,
            Set<BlockPos> attemptedVillageChests,
            int naturalScanCursor,
            int cropScanCursor,
            int villageChestScanCursor) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(plantingItem, "plantingItem");
        BlockPos frozenScanOrigin = Objects.requireNonNull(
                scanOrigin, "scanOrigin").immutable();
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }
        Set<BlockPos> attemptedGrass = immutablePositionSet(attemptedNaturalGrass);
        Set<BlockPos> attemptedCrops = immutablePositionSet(attemptedVillageCrops);
        Set<BlockPos> attemptedChests = immutablePositionSet(attemptedVillageChests);
        if (naturalScanCursor < 0 || cropScanCursor < 0 || villageChestScanCursor < 0) {
            throw new IllegalArgumentException("scanner cursors cannot be negative");
        }

        RegisteredFarmExclusionSnapshot farms =
                RegisteredFarmExclusionSnapshot.capture(dimensionId);
        PlayerPlacedBlockStore playerPlaced = PlayerPlacedBlockStore.get();
        MarkedChestItemLocator.MarkedPositionSnapshot markedPositions =
                MarkedChestItemLocator.markedPositions(dimensionId);
        boolean guardsAvailable = farms.available()
                && playerPlaced != null
                && markedPositions.available();
        if (!guardsAvailable) {
            return new WorldRoundManifest(
                    frozenScanOrigin,
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    naturalScanCursor,
                    cropScanCursor,
                    villageChestScanCursor);
        }

        ServerLevel level = controller.getWorld();
        BlockScanner scanner = controller.getBlockScanner();
        BreakWindow grass = plantingItem == Items.WHEAT_SEEDS
                ? naturalGrass(
                        level,
                        scanner,
                        playerPlaced,
                        farms,
                        frozenScanOrigin,
                        attemptedGrass,
                        naturalScanCursor)
                : new BreakWindow(List.of(), naturalScanCursor);
        RecognizedBlocks recognized = recognizedBlocks();
        BreakWindow crops = villageCrops(
                level,
                scanner,
                plantingItem,
                recognized.blocks(),
                playerPlaced,
                farms,
                frozenScanOrigin,
                attemptedCrops,
                cropScanCursor);
        ChestWindow chests = villageChests(
                level,
                scanner,
                playerPlaced,
                farms,
                markedPositions.positions(),
                frozenScanOrigin,
                attemptedChests,
                villageChestScanCursor);
        return new WorldRoundManifest(
                frozenScanOrigin,
                grass.candidates(),
                crops.candidates(),
                chests.candidates(),
                true,
                recognized.truncated(),
                grass.nextCursor(),
                crops.nextCursor(),
                chests.nextCursor());
    }

    /** Normative source order, exposed as a pure seam for task and deterministic tests. */
    public static List<SourceKind> sourceOrder(boolean wheatSeeds) {
        return wheatSeeds
                ? List.of(
                        SourceKind.MARKED_CHEST,
                        SourceKind.NATURAL_GRASS,
                        SourceKind.VILLAGE_CROP,
                        SourceKind.VILLAGE_CHEST)
                : List.of(
                        SourceKind.MARKED_CHEST,
                        SourceKind.VILLAGE_CROP,
                        SourceKind.VILLAGE_CHEST);
    }

    /** Exact worldgen-structure membership, not the looser POI-based {@code isVillage} predicate. */
    public static boolean isInsideVillageStructure(ServerLevel level, BlockPos position) {
        if (level == null || position == null) {
            return false;
        }
        try {
            if (!allReferencedStructureStartsLoaded(level, position)) {
                return false;
            }
            return level.structureManager()
                    .getStructureWithPieceAt(position, StructureTags.VILLAGE)
                    .isValid();
        } catch (RuntimeException structureFailure) {
            return false;
        }
    }

    /**
     * Preflight the exact vanilla lookup through already-loaded LevelChunks only. Vanilla's
     * StructureManager otherwise resolves reference/start chunks synchronously; refusing when any
     * referenced start is absent preserves discovery's no-load/no-generation contract.
     */
    private static boolean allReferencedStructureStartsLoaded(
            ServerLevel level,
            BlockPos position) {
        ChunkPos targetChunk = new ChunkPos(position);
        LevelChunk loadedTarget = level.getChunkSource().getChunkNow(
                targetChunk.x, targetChunk.z);
        if (loadedTarget == null) {
            return false;
        }

        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> referenceMap =
                loadedTarget.getAllReferences();
        int referenceTypeCount = referenceMap.size();
        if (!loadedStructureLookupPermitted(referenceTypeCount, 0, true)) {
            return false;
        }

        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int villageReferenceCount = 0;
        for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> entry
                : referenceMap.entrySet()) {
            Structure structure = entry.getKey();
            boolean villageStructure = structureRegistry
                    .getHolder(structureRegistry.getId(structure))
                    .map(holder -> holder.is(StructureTags.VILLAGE))
                    .orElse(false);
            if (!villageStructure) {
                continue;
            }

            var iterator = entry.getValue().iterator();
            while (iterator.hasNext()) {
                villageReferenceCount++;
                if (!loadedStructureLookupPermitted(
                        referenceTypeCount, villageReferenceCount, true)) {
                    return false;
                }
                ChunkPos startChunk = new ChunkPos(iterator.nextLong());
                if (level.getChunkSource().getChunkNow(
                        startChunk.x, startChunk.z) == null) {
                    return false;
                }
            }
        }
        return loadedStructureLookupPermitted(
                referenceTypeCount, villageReferenceCount, true);
    }

    /** Pure bound seam for the loaded-only structure-membership preflight. */
    static boolean loadedStructureLookupPermitted(
            int referenceTypeCount,
            int villageReferenceCount,
            boolean allReferencedStartsLoaded) {
        return allReferencedStartsLoaded
                && referenceTypeCount >= 0
                && referenceTypeCount <= MAX_STRUCTURE_REFERENCE_TYPES
                && villageReferenceCount >= 0
                && villageReferenceCount <= MAX_STRUCTURE_REFERENCES;
    }

    /** All exclusion stores required before any unmarked world-source action. */
    public static boolean worldGuardsAvailable(String dimensionId) {
        return PlayerPlacedBlockStore.get() != null
                && RegisteredFarmExclusionSnapshot.capture(dimensionId).available()
                && MarkedChestItemLocator.markedPositions(dimensionId).available();
    }

    /** Fresh action-time gate for a frozen grass/crop candidate. */
    public static boolean isLiveBreakCandidate(
            ServerLevel level,
            Item plantingItem,
            String dimensionId,
            BreakCandidate candidate) {
        if (level == null || plantingItem == null || candidate == null
                || !level.hasChunkAt(candidate.position())
                || !level.getBlockState(candidate.position()).equals(candidate.expectedState())) {
            return false;
        }
        PlayerPlacedBlockStore playerPlaced = PlayerPlacedBlockStore.get();
        RegisteredFarmExclusionSnapshot farms =
                RegisteredFarmExclusionSnapshot.capture(dimensionId);
        if (protectionDenied(
                playerPlaced,
                farms,
                dimensionId,
                candidate.position(),
                candidate.position().below())) {
            return false;
        }
        if (candidate.kind() == SourceKind.NATURAL_GRASS) {
            BlockState state = candidate.expectedState();
            return plantingItem == Items.WHEAT_SEEDS
                    && (state.is(Blocks.SHORT_GRASS) || isLowerTallGrass(state));
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(plantingItem);
        return itemId != null
                && isInsideVillageStructure(level, candidate.position())
                && matchesPlantingItem(
                level, candidate.position(), candidate.expectedState(), itemId);
    }

    /** Fresh action-time gate for one frozen, previously-unmarked village chest. */
    public static boolean isLiveVillageChestCandidate(
            ServerLevel level,
            String dimensionId,
            ChestCandidate candidate) {
        if (level == null || candidate == null
                || !level.hasChunkAt(candidate.position())
                || !level.getBlockState(candidate.position()).is(Blocks.CHEST)
                || (candidate.secondaryPosition() != null
                && (!level.hasChunkAt(candidate.secondaryPosition())
                || !level.getBlockState(candidate.secondaryPosition()).is(Blocks.CHEST)))) {
            return false;
        }
        PlayerPlacedBlockStore playerPlaced = PlayerPlacedBlockStore.get();
        RegisteredFarmExclusionSnapshot farms =
                RegisteredFarmExclusionSnapshot.capture(dimensionId);
        EllieGPSStore store = EllieGPSStore.get();
        return store != null
                && !store.hasTypeAtPosition(
                WaypointTypes.INVENTORY, dimensionId, candidate.position())
                && (candidate.secondaryPosition() == null
                || !store.hasTypeAtPosition(
                WaypointTypes.INVENTORY, dimensionId, candidate.secondaryPosition()))
                && !protectionDenied(
                playerPlaced,
                farms,
                dimensionId,
                candidate.position(),
                candidate.secondaryPosition())
                && isInsideVillageStructure(level, candidate.position())
                && (candidate.secondaryPosition() == null
                || isInsideVillageStructure(level, candidate.secondaryPosition()));
    }

    static boolean protectionDenied(
            PlayerPlacedBlockStore playerPlaced,
            RegisteredFarmExclusionSnapshot farms,
            String dimensionId,
            BlockPos primary,
            BlockPos secondary) {
        if (playerPlaced == null || farms == null || !farms.available()) {
            return true;
        }
        return playerPlaced.contains(dimensionId, primary)
                || (secondary != null && playerPlaced.contains(dimensionId, secondary))
                || farms.contains(primary)
                || (secondary != null && farms.contains(secondary));
    }

    static boolean matchesPlantingItem(
            ServerLevel level,
            BlockPos position,
            BlockState state,
            ResourceLocation targetItemId) {
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT.resolve(state).orElse(null);
        return behavior != null
                && behavior.isMature(state)
                && behavior.plantingItemId(level, position, state)
                .map(targetItemId::equals)
                .orElse(false);
    }

    private static BreakWindow naturalGrass(
            ServerLevel level,
            BlockScanner scanner,
            PlayerPlacedBlockStore playerPlaced,
            RegisteredFarmExclusionSnapshot farms,
            BlockPos origin,
            Set<BlockPos> attemptedPositions,
            int scanCursor) {
        ArrayList<BreakCandidate> candidates = new ArrayList<>();
        HashSet<BlockPos> seen = new HashSet<>();
        String dimensionId = level.dimension().location().toString();
        BlockScanner.KnownLocationWindow window = scanner.getKnownLocationsWindow(
                scanCursor,
                MAX_SCANNER_VISITS,
                MAX_SCANNER_CYCLE_VISITS,
                naturalGrassBlocks());
        for (BlockPos raw : window.locations()) {
            BlockPos position = canonicalGrassPosition(level, raw);
            if (position == null
                    || attemptedPositions.contains(position)
                    || !seen.add(position)
                    || !withinRadius(origin, position)) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if ((!state.is(Blocks.SHORT_GRASS) && !isLowerTallGrass(state))
                    || protectionDenied(
                    playerPlaced, farms, dimensionId, position, position.below())) {
                continue;
            }
            candidates.add(new BreakCandidate(
                    SourceKind.NATURAL_GRASS, position, state));
        }
        candidates.sort(breakComparator(origin));
        return new BreakWindow(
                capped(candidates, MAX_NATURAL_GRASS), window.nextCursor());
    }

    private static BreakWindow villageCrops(
            ServerLevel level,
            BlockScanner scanner,
            Item plantingItem,
            List<Block> recognizedBlocks,
            PlayerPlacedBlockStore playerPlaced,
            RegisteredFarmExclusionSnapshot farms,
            BlockPos origin,
            Set<BlockPos> attemptedPositions,
            int scanCursor) {
        if (recognizedBlocks.isEmpty()) {
            return new BreakWindow(List.of(), scanCursor);
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(plantingItem);
        if (itemId == null) {
            return new BreakWindow(List.of(), scanCursor);
        }
        String dimensionId = level.dimension().location().toString();
        ArrayList<BreakCandidate> candidates = new ArrayList<>();
        HashSet<BlockPos> seen = new HashSet<>();
        Block[] blocks = recognizedBlocks.toArray(Block[]::new);
        BlockScanner.KnownLocationWindow window = scanner.getKnownLocationsWindow(
                scanCursor, MAX_SCANNER_VISITS, MAX_SCANNER_CYCLE_VISITS, blocks);
        for (BlockPos raw : window.locations()) {
            BlockPos position = raw.immutable();
            if (attemptedPositions.contains(position)
                    || !seen.add(position)
                    || !withinRadius(origin, position)
                    || !level.hasChunkAt(position)
                    || !isInsideVillageStructure(level, position)
                    || protectionDenied(
                    playerPlaced, farms, dimensionId, position, position.below())) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if (matchesPlantingItem(level, position, state, itemId)) {
                candidates.add(new BreakCandidate(
                        SourceKind.VILLAGE_CROP, position, state));
            }
        }
        candidates.sort(breakComparator(origin));
        return new BreakWindow(
                capped(candidates, MAX_VILLAGE_CROPS), window.nextCursor());
    }

    private static ChestWindow villageChests(
            ServerLevel level,
            BlockScanner scanner,
            PlayerPlacedBlockStore playerPlaced,
            RegisteredFarmExclusionSnapshot farms,
            Set<BlockPos> markedPositions,
            BlockPos origin,
            Set<BlockPos> attemptedPositions,
            int scanCursor) {
        String dimensionId = level.dimension().location().toString();
        ArrayList<ChestCandidate> candidates = new ArrayList<>();
        HashSet<BlockPos> seenCanonical = new HashSet<>();
        BlockScanner.KnownLocationWindow window = scanner.getKnownLocationsWindow(
                scanCursor,
                MAX_SCANNER_VISITS,
                MAX_SCANNER_CYCLE_VISITS,
                Blocks.CHEST);
        for (BlockPos raw : window.locations()) {
            ChestCandidate candidate = canonicalChest(level, raw);
            if (candidate == null
                    || attemptedPositions.contains(candidate.position())
                    || !seenCanonical.add(candidate.position())
                    || !withinRadius(origin, candidate.position())
                    || markedPositions.contains(candidate.position())
                    || (candidate.secondaryPosition() != null
                    && markedPositions.contains(candidate.secondaryPosition()))
                    || protectionDenied(
                    playerPlaced,
                    farms,
                    dimensionId,
                    candidate.position(),
                    candidate.secondaryPosition())
                    || !isInsideVillageStructure(level, candidate.position())
                    || (candidate.secondaryPosition() != null
                    && !isInsideVillageStructure(level, candidate.secondaryPosition()))) {
                continue;
            }
            candidates.add(candidate);
        }
        candidates.sort(chestComparator(origin));
        return new ChestWindow(
                capped(candidates, MAX_VILLAGE_CHESTS), window.nextCursor());
    }

    private static ChestCandidate canonicalChest(ServerLevel level, BlockPos raw) {
        if (raw == null || !level.hasChunkAt(raw)) {
            return null;
        }
        BlockState state = level.getBlockState(raw);
        if (!state.is(Blocks.CHEST)) {
            return null;
        }
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return new ChestCandidate(raw, null);
        }
        BlockPos partner = raw.relative(ChestBlock.getConnectedDirection(state));
        if (!level.hasChunkAt(partner) || !level.getBlockState(partner).is(Blocks.CHEST)) {
            return null;
        }
        BlockPos first = comparePositions(raw, partner) <= 0 ? raw : partner;
        BlockPos second = first.equals(raw) ? partner : raw;
        return new ChestCandidate(first, second);
    }

    private static BlockPos canonicalGrassPosition(ServerLevel level, BlockPos raw) {
        if (raw == null || !level.hasChunkAt(raw)) {
            return null;
        }
        BlockState state = level.getBlockState(raw);
        if (state.is(Blocks.SHORT_GRASS)) {
            return raw.immutable();
        }
        if (!state.is(Blocks.TALL_GRASS)) {
            return null;
        }
        BlockPos canonical = state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER
                ? raw.below()
                : raw;
        if (!level.hasChunkAt(canonical)) {
            return null;
        }
        BlockState lower = level.getBlockState(canonical);
        return isLowerTallGrass(lower) ? canonical.immutable() : null;
    }

    private static boolean isLowerTallGrass(BlockState state) {
        return state.is(Blocks.TALL_GRASS)
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** Frozen wheat-seed natural-source policy: never includes a grass block. */
    static Block[] naturalGrassBlocks() {
        return new Block[]{Blocks.SHORT_GRASS, Blocks.TALL_GRASS};
    }

    private static RecognizedBlocks recognizedBlocks() {
        ArrayList<Block> blocks = new ArrayList<>();
        boolean truncated = false;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (HarvestBehaviorRegistry.DEFAULT.resolve(block.defaultBlockState()).isEmpty()) {
                continue;
            }
            if (blocks.size() >= MAX_RECOGNIZED_BLOCK_TYPES) {
                truncated = true;
                break;
            }
            blocks.add(block);
        }
        return new RecognizedBlocks(List.copyOf(blocks), truncated);
    }

    private static Comparator<BreakCandidate> breakComparator(BlockPos origin) {
        return Comparator
                .comparingLong((BreakCandidate candidate) ->
                        distanceSquared(origin, candidate.position()))
                .thenComparing(candidate -> candidate.position(), PlantableSourcePlanner::comparePositions);
    }

    private static Comparator<ChestCandidate> chestComparator(BlockPos origin) {
        return Comparator
                .comparingLong((ChestCandidate candidate) ->
                        distanceSquared(origin, candidate.position()))
                .thenComparing(candidate -> candidate.position(), PlantableSourcePlanner::comparePositions);
    }

    private static boolean withinRadius(BlockPos origin, BlockPos position) {
        double radius = WORLD_SEARCH_RADIUS;
        return distanceSquared(origin, position) <= radius * radius;
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int comparePositions(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) {
            return x;
        }
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }

    private static <T> List<T> capped(List<T> source, int cap) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return List.copyOf(source.subList(0, Math.min(cap, source.size())));
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static Set<BlockPos> immutablePositionSet(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        HashSet<BlockPos> copy = new HashSet<>();
        positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .forEach(copy::add);
        return Set.copyOf(copy);
    }

    private record RecognizedBlocks(List<Block> blocks, boolean truncated) {
    }

    private record BreakWindow(List<BreakCandidate> candidates, int nextCursor) {
    }

    private record ChestWindow(List<ChestCandidate> candidates, int nextCursor) {
    }
}
