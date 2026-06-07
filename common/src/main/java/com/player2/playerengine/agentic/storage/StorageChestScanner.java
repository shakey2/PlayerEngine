package com.player2.playerengine.agentic.storage;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.steps.ResolveStorageChestParams;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Deterministic nearby chest discovery for C2. */
public final class StorageChestScanner {

    private static final Logger LOGGER = LogManager.getLogger(StorageChestScanner.class);

    private StorageChestScanner() {}

    public record ScanResult(
            Optional<StorageChestCandidate> best,
            int candidateCount,
            int usableCount,
            Map<String, Integer> rejectionCounts
    ) {}

    public static ScanResult scan(PlayerEngineController mod, ResolveStorageChestParams params) {
        double radiusSq = params.searchRadius() * params.searchRadius();
        Vec3 origin = mod.getPlayer().position();
        Map<String, Integer> rejections = new HashMap<>();
        List<StorageChestCandidate> usable = new ArrayList<>();

        List<BlockPos> positions = new ArrayList<>();
        positions.addAll(mod.getBlockScanner().getKnownLocations(Blocks.CHEST));
        positions.addAll(mod.getBlockScanner().getKnownLocations(Blocks.TRAPPED_CHEST));

        int candidates = 0;
        for (BlockPos pos : positions) {
            candidates++;
            Optional<String> reject = StorageChestValidation.validateExistingCandidate(
                    mod, pos, radiusSq, params.avoidLootChests());
            if (reject.isPresent()) {
                rejections.merge(reject.get(), 1, Integer::sum);
                continue;
            }
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            String blockId = ItemHelper.stripItemName(block.asItem());
            Optional<ContainerCache> cache = mod.getItemStorage().getContainerAtPosition(pos);
            List<String> warnings = new ArrayList<>();
            if (cache.isEmpty()) {
                warnings.add("container_cache_unknown");
            }
            double score = scoreCandidate(mod, pos, block, cache.isPresent(), origin);
            usable.add(new StorageChestCandidate(
                    pos,
                    blockId,
                    StorageChestValidation.sourceForCache(cache),
                    score,
                    List.copyOf(warnings)));
        }

        usable.sort(Comparator.comparingDouble(StorageChestCandidate::score)
                .thenComparingInt(c -> c.pos().getX())
                .thenComparingInt(c -> c.pos().getY())
                .thenComparingInt(c -> c.pos().getZ()));
        Optional<StorageChestCandidate> best = usable.isEmpty() ? Optional.empty() : Optional.of(usable.get(0));
        if (candidates > 0) {
            LOGGER.info("[Agentic] storage scan: candidates={} usable={} rejected={}",
                    candidates, usable.size(), rejections);
        }
        return new ScanResult(best, candidates, usable.size(), Map.copyOf(rejections));
    }

    private static double scoreCandidate(
            PlayerEngineController mod,
            BlockPos pos,
            Block block,
            boolean hasCache,
            Vec3 origin) {
        double dist = Math.sqrt(mod.getPlayer().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        double score = dist;
        if (hasCache) {
            score -= 2.0;
        }
        if (block == Blocks.TRAPPED_CHEST) {
            score += 0.5;
        }
        score += Math.abs(pos.getY() - mod.getPlayer().blockPosition().getY()) * 0.25;
        // Deterministic ordering is enforced by the (x, y, z) tie-break in the
        // candidate comparator below; no fragile float-coefficient packing here.
        return score;
    }
}
