package com.player2.playerengine.agentic.storage;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bounded deterministic placement-site selection for a single chest (C2). */
public final class ChestPlacementSelector {

    private static final Logger LOGGER = LogManager.getLogger(ChestPlacementSelector.class);

    private ChestPlacementSelector() {}

    public record SelectResult(
            Optional<ChestPlacementCandidate> best,
            int candidateCount,
            Map<String, Integer> rejectionCounts
    ) {}

    public static SelectResult select(PlayerEngineController mod, Vec3 origin, double placementRadius) {
        Map<String, Integer> rejections = new HashMap<>();
        List<ChestPlacementCandidate> usable = new ArrayList<>();
        BlockPos center = BlockPos.containing(origin);
        int yMin = Math.max(mod.getWorld().getMinBuildHeight(), center.getY() - 2);
        int yMax = Math.min(mod.getWorld().getMaxBuildHeight() - 1, center.getY() + 2);
        int r = (int) Math.ceil(placementRadius);
        double radiusSq = placementRadius * placementRadius;
        int candidates = 0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = yMin; y <= yMax; y++) {
                    BlockPos pos = center.offset(dx, y - center.getY(), dz);
                    candidates++;
                    Optional<String> reject = validatePlacementSite(mod, pos, origin, radiusSq);
                    if (reject.isPresent()) {
                        rejections.merge(reject.get(), 1, Integer::sum);
                        continue;
                    }
                    double score = scorePlacement(mod, pos, origin);
                    usable.add(new ChestPlacementCandidate(pos, score, List.of()));
                }
            }
        }

        usable.sort(Comparator.comparingDouble(ChestPlacementCandidate::score)
                .thenComparingInt(c -> c.pos().getX())
                .thenComparingInt(c -> c.pos().getY())
                .thenComparingInt(c -> c.pos().getZ()));
        Optional<ChestPlacementCandidate> best = usable.isEmpty() ? Optional.empty() : Optional.of(usable.get(0));
        if (best.isPresent()) {
            LOGGER.info("[Agentic] placement scan: candidates={} selected={} score={}",
                    candidates, best.get().pos(), best.get().score());
        } else {
            LOGGER.info("[Agentic] placement scan: candidates={} selected=none rejected={}",
                    candidates, rejections);
        }
        return new SelectResult(best, candidates, Map.copyOf(rejections));
    }

    private static Optional<String> validatePlacementSite(
            PlayerEngineController mod,
            BlockPos pos,
            Vec3 origin,
            double radiusSq) {
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return Optional.of("chunk_unloaded");
        }
        if (origin.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > radiusSq) {
            return Optional.of("out_of_radius");
        }
        if (!WorldHelper.isAir(mod, pos)) {
            return Optional.of("not_air");
        }
        if (!WorldHelper.isSolidBlock(mod, pos.below())) {
            return Optional.of("no_solid_floor");
        }
        if (!WorldHelper.isAir(mod, pos.above())) {
            return Optional.of("no_headroom");
        }
        if (!WorldHelper.canReach(mod, pos) || !WorldHelper.canPlace(mod, pos)) {
            return Optional.of("cannot_place");
        }
        if (isHazard(mod, pos) || isHazard(mod, pos.below())) {
            return Optional.of("hazard");
        }
        if (hasAdjacentChest(mod, pos)) {
            return Optional.of("adjacent_chest");
        }
        if (collidesWithEntities(mod, pos)) {
            return Optional.of("entity_collision");
        }
        BlockPos feet = mod.getPlayer().blockPosition();
        if (pos.equals(feet) || pos.equals(feet.above()) || pos.below().equals(feet)) {
            return Optional.of("under_bot");
        }
        return Optional.empty();
    }

    private static boolean hasAdjacentChest(PlayerEngineController mod, BlockPos pos) {
        for (BlockPos check : new BlockPos[]{
                pos.north(), pos.south(), pos.east(), pos.west()}) {
            Block b = mod.getWorld().getBlockState(check).getBlock();
            if (StorageChestValidation.isVanillaStorageChestBlock(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHazard(PlayerEngineController mod, BlockPos pos) {
        Block b = mod.getWorld().getBlockState(pos).getBlock();
        return b instanceof LiquidBlock
                || b == Blocks.FIRE
                || b == Blocks.SOUL_FIRE
                || b == Blocks.CACTUS
                || b == Blocks.MAGMA_BLOCK
                || b == Blocks.NETHER_PORTAL
                || b == Blocks.END_PORTAL;
    }

    private static boolean collidesWithEntities(PlayerEngineController mod, BlockPos pos) {
        AABB box = new AABB(pos);
        return !mod.getWorld().getEntities(null, box).isEmpty();
    }

    private static double scorePlacement(PlayerEngineController mod, BlockPos pos, Vec3 origin) {
        double dist = origin.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        double yDelta = Math.abs(pos.getY() - origin.y);
        return dist + yDelta * 2.0;
    }
}
