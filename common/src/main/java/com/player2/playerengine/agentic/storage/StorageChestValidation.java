package com.player2.playerengine.agentic.storage;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.StorageTargetSource;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Validation helpers for C2 storage chest candidates. */
public final class StorageChestValidation {

    private StorageChestValidation() {}

    public static boolean isVanillaStorageChestBlock(Block block) {
        if (block instanceof EnderChestBlock) {
            return false;
        }
        return WorldHelper.isChest(block);
    }

    public static boolean isBlockedTop(PlayerEngineController mod, BlockPos pos) {
        BlockPos above = pos.above();
        if (!WorldHelper.isSolidBlock(mod, above)) {
            return false;
        }
        return !WorldHelper.canBreak(mod, above);
    }

    public static boolean isLikelyLootChest(PlayerEngineController mod, BlockPos pos) {
        int range = 6;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (mod.getWorld().getBlockState(pos.offset(dx, 0, dz)).is(Blocks.SPAWNER)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Optional<String> validateExistingCandidate(
            PlayerEngineController mod,
            BlockPos pos,
            double searchRadiusSq,
            boolean avoidLootChests) {
        List<String> reject = new ArrayList<>();
        if (!mod.getWorld().dimension().equals(mod.getPlayer().level().dimension())) {
            return Optional.of("wrong_dimension");
        }
        if (!mod.getChunkTracker().isChunkLoaded(pos)) {
            return Optional.of("chunk_unloaded");
        }
        if (mod.getBlockScanner().isUnreachable(pos)) {
            return Optional.of("unreachable");
        }
        Vec3Dist(mod, pos, searchRadiusSq, reject);
        if (!reject.isEmpty()) {
            return Optional.of(reject.get(0));
        }
        BlockState state = mod.getWorld().getBlockState(pos);
        Block block = state.getBlock();
        if (!isVanillaStorageChestBlock(block)) {
            return Optional.of("not_chest");
        }
        if (isBlockedTop(mod, pos)) {
            return Optional.of("blocked_top");
        }
        if (!WorldHelper.canReach(mod, pos)) {
            return Optional.of("cannot_reach");
        }
        Optional<ContainerCache> cache = mod.getItemStorage().getContainerAtPosition(pos);
        if (cache.isPresent() && cache.get().isFull()) {
            return Optional.of("container_full");
        }
        if (avoidLootChests && isLikelyLootChest(mod, pos)) {
            return Optional.of("loot_chest");
        }
        return Optional.empty();
    }

    private static void Vec3Dist(PlayerEngineController mod, BlockPos pos, double radiusSq, List<String> reject) {
        double dist = mod.getPlayer().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (dist > radiusSq) {
            reject.add("out_of_radius");
        }
    }

    public static StorageTargetSource sourceForCache(Optional<ContainerCache> cache) {
        return cache.isPresent() ? StorageTargetSource.EXISTING_CONFIRMED : StorageTargetSource.EXISTING_UNKNOWN;
    }
}
