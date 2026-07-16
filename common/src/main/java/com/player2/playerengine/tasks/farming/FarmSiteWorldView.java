package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.EllieGPSStore;
import com.player2.playerengine.agentic.elliegps.FarmWaypointData;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import com.player2.playerengine.agentic.elliegps.WaypointTypes;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Objects;

/**
 * Read-only, server-thread boundary used by deterministic farm-site planning.
 *
 * <p>One call to {@link #observe(BlockPos)} or {@link #observeWorld(BlockPos)} is one planner read.
 * Implementations may gather several tightly-related facts atomically inside that call. Runtime
 * planning must never retain a {@link ServerLevel} outside the production implementation.
 */
public interface FarmSiteWorldView {

    enum BlockKind {
        AIR,
        GRASS_BLOCK,
        DIRT,
        STONE,
        FARMLAND,
        CROP,
        STANDALONE_WATER,
        REPLACEABLE_VEGETATION,
        OTHER
    }

    enum FluidKind {
        NONE,
        STANDALONE_WATER_SOURCE,
        OTHER_WATER_SOURCE,
        FLOWING_WATER,
        OTHER
    }

    /** All mutable facts which must remain stable between selection and the first edit. */
    record CellObservation(
            boolean loaded,
            boolean inBuildHeight,
            boolean withinWorldBorder,
            BlockKind blockKind,
            FluidKind fluidKind,
            boolean replaceable,
            boolean collisionEmpty,
            boolean recognizedCrop,
            boolean blockEntity,
            boolean fallingHazard,
            boolean unbreakable,
            boolean solidTopSupport,
            boolean nonWaterloggable,
            boolean playerProtected,
            boolean warmEnoughToRain,
            int blockLight,
            String stateFingerprint,
            FarmBreakToolRequirement breakToolRequirement) {

        public CellObservation {
            Objects.requireNonNull(blockKind, "blockKind");
            Objects.requireNonNull(fluidKind, "fluidKind");
            Objects.requireNonNull(breakToolRequirement, "breakToolRequirement");
            stateFingerprint = stateFingerprint == null ? "" : stateFingerprint;
            if (blockLight < 0 || blockLight > 15) {
                throw new IllegalArgumentException("blockLight must be in [0,15]");
            }
        }

        /** Compatibility fixture constructor; production observations always pass the live fact. */
        public CellObservation(
                boolean loaded,
                boolean inBuildHeight,
                boolean withinWorldBorder,
                BlockKind blockKind,
                FluidKind fluidKind,
                boolean replaceable,
                boolean collisionEmpty,
                boolean recognizedCrop,
                boolean blockEntity,
                boolean fallingHazard,
                boolean unbreakable,
                boolean solidTopSupport,
                boolean nonWaterloggable,
                boolean playerProtected,
                boolean warmEnoughToRain,
                int blockLight,
                String stateFingerprint) {
            this(
                    loaded,
                    inBuildHeight,
                    withinWorldBorder,
                    blockKind,
                    fluidKind,
                    replaceable,
                    collisionEmpty,
                    recognizedCrop,
                    blockEntity,
                    fallingHazard,
                    unbreakable,
                    solidTopSupport,
                    nonWaterloggable,
                    playerProtected,
                    warmEnoughToRain,
                    blockLight,
                    stateFingerprint,
                    FarmBreakToolRequirement.none(Blocks.AIR.defaultBlockState()));
        }

        public boolean hasFluid() {
            return fluidKind != FluidKind.NONE;
        }

        public boolean isStandaloneWaterSource() {
            return blockKind == BlockKind.STANDALONE_WATER
                    && fluidKind == FluidKind.STANDALONE_WATER_SOURCE;
        }

        public boolean isReadable() {
            return loaded && inBuildHeight && withinWorldBorder;
        }
    }

    /** Candidate-wide facts which are not tied to one block state. */
    record WorldObservation(
            boolean ultraWarm,
            boolean protectionStoreAvailable,
            boolean waypointStoreAvailable,
            boolean waypointCollision) {
    }

    /** Fails before any world read when planning is accidentally invoked off the server thread. */
    void assertServerThread();

    /** Reads one immutable set of block, fluid, climate, loading, and protection facts. */
    CellObservation observe(BlockPos position);

    /** Reads dimension and EllieGPS collision facts for one candidate center. */
    WorldObservation observeWorld(BlockPos center);

    /** Creates the production view over the live level and current player-placement store. */
    static FarmSiteWorldView live(ServerLevel level) {
        return new ServerWorldView(level, PlayerPlacedBlockStore.get());
    }

    static String stateFingerprint(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.is(Blocks.FARMLAND)) {
            // Hydration moisture changes on ordinary/random ticks and is not manifest drift. The
            // block-kind observation still detects destructive farmland -> dirt/trample changes.
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "|farmland";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "|" + state;
    }

    /**
     * Ground cover which may be ignored for site fitness but must still be physically cleared.
     * Vanilla flowers are tagged/non-colliding but are not generally marked replaceable.
     */
    static boolean isClearableGroundCover(BlockState state, FluidState fluid) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(fluid, "fluid");
        return fluid.isEmpty()
                && (state.canBeReplaced()
                || state.is(BlockTags.FLOWERS)
                || state.getBlock() instanceof FlowerBlock
                || state.getBlock() instanceof TallFlowerBlock);
    }

    /** Public pure classification seam shared by production observation and self-tests. */
    static BlockKind classifyBlock(BlockState state, FluidState fluid) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(fluid, "fluid");
        if (state.isAir()) {
            return BlockKind.AIR;
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            return BlockKind.GRASS_BLOCK;
        }
        if (state.is(Blocks.DIRT)) {
            return BlockKind.DIRT;
        }
        if (state.is(Blocks.STONE)) {
            return BlockKind.STONE;
        }
        if (state.is(Blocks.FARMLAND)) {
            return BlockKind.FARMLAND;
        }
        if (HarvestBehaviorRegistry.DEFAULT.resolve(state).isPresent()) {
            return BlockKind.CROP;
        }
        if (state.is(Blocks.WATER) && fluid.is(FluidTags.WATER) && fluid.isSource()) {
            return BlockKind.STANDALONE_WATER;
        }
        if (isClearableGroundCover(state, fluid)) {
            return BlockKind.REPLACEABLE_VEGETATION;
        }
        return BlockKind.OTHER;
    }

    /** Production implementation; deliberately hidden behind the read-only interface. */
    final class ServerWorldView implements FarmSiteWorldView {
        private final ServerLevel level;
        private final PlayerPlacedBlockStore protectionStore;
        private final String dimensionId;

        private ServerWorldView(ServerLevel level, PlayerPlacedBlockStore protectionStore) {
            this.level = Objects.requireNonNull(level, "level");
            this.protectionStore = protectionStore;
            this.dimensionId = level.dimension().location().toString();
        }

        @Override
        public void assertServerThread() {
            MinecraftServer server = level.getServer();
            if (server == null || !server.isSameThread()) {
                throw new IllegalStateException("farm site planning must run on the server thread");
            }
        }

        @Override
        public CellObservation observe(BlockPos requestedPosition) {
            assertServerThread();
            BlockPos position = requestedPosition.immutable();
            boolean inBuildHeight = level.isInWorldBounds(position);
            boolean loaded = inBuildHeight && level.isLoaded(position);
            boolean withinBorder = inBuildHeight
                    && level.getWorldBorder().isWithinBounds(position);
            if (!loaded || !withinBorder) {
                return unavailable(loaded, inBuildHeight, withinBorder);
            }

            BlockState state = level.getBlockState(position);
            FluidState fluid = state.getFluidState();
            BlockKind blockKind = FarmSiteWorldView.classifyBlock(state, fluid);
            FluidKind fluidKind = classifyFluid(state, fluid);
            boolean fluidEmpty = fluid.isEmpty();
            boolean protectedByPlayer = protectionStore != null
                    && protectionStore.contains(dimensionId, position);
            // Reject the capability itself, even when the live block entity has not finished loading.
            boolean hasBlockEntity = state.hasBlockEntity();
            boolean solidTop = fluidEmpty
                    && !state.canBeReplaced()
                    && state.isFaceSturdy(level, position, Direction.UP);
            boolean nonWaterloggable = !(state.getBlock() instanceof LiquidBlockContainer);
            String fingerprint = FarmSiteWorldView.stateFingerprint(state);
            return new CellObservation(
                    true,
                    true,
                    true,
                    blockKind,
                    fluidKind,
                    state.canBeReplaced(),
                    state.getCollisionShape(level, position).isEmpty(),
                    HarvestBehaviorRegistry.DEFAULT.resolve(state).isPresent(),
                    hasBlockEntity,
                    state.getBlock() instanceof Fallable,
                    state.getDestroySpeed(level, position) < 0.0F,
                    solidTop,
                    nonWaterloggable,
                    protectedByPlayer,
                    level.getBiome(position).value().warmEnoughToRain(position),
                    level.getBrightness(LightLayer.BLOCK, position),
                    fingerprint,
                    FarmBreakToolRequirement.resolve(state));
        }

        @Override
        public WorldObservation observeWorld(BlockPos requestedCenter) {
            assertServerThread();
            BlockPos center = requestedCenter.immutable();
            EllieGPSStore waypointStore = EllieGPSStore.get();
            boolean protectionAvailable = protectionStore != null
                    && PlayerPlacedBlockStore.get() == protectionStore;
            WaypointRecord existing = waypointStore == null
                    ? null
                    : waypointStore.byPosition(dimensionId, center);
            FarmWaypointData existingFarm = existing == null ? null : existing.farmData();
            return new WorldObservation(
                    level.dimensionType().ultraWarm(),
                    protectionAvailable,
                    waypointStore != null,
                    existing != null
                            && (!WaypointTypes.FARM.equals(existing.type)
                            || existingFarm == null
                            || !existingFarm.isSupportedVersion()));
        }

        private static CellObservation unavailable(
                boolean loaded,
                boolean inBuildHeight,
                boolean withinBorder) {
            return new CellObservation(
                    loaded,
                    inBuildHeight,
                    withinBorder,
                    BlockKind.OTHER,
                    FluidKind.NONE,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    true,
                    15,
                    "unavailable",
                    FarmBreakToolRequirement.custom(Blocks.BEDROCK.defaultBlockState()));
        }

        private static FluidKind classifyFluid(BlockState state, FluidState fluid) {
            if (fluid.isEmpty()) {
                return FluidKind.NONE;
            }
            if (fluid.is(FluidTags.WATER)) {
                if (fluid.isSource()) {
                    return state.is(Blocks.WATER)
                            ? FluidKind.STANDALONE_WATER_SOURCE
                            : FluidKind.OTHER_WATER_SOURCE;
                }
                return FluidKind.FLOWING_WATER;
            }
            return FluidKind.OTHER;
        }
    }
}
