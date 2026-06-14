package com.player2.playerengine.containeraccess;

import com.player2.playerengine.PlayerEngineController;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Static storage-container locator service (the {@code locate_storage} backend).
 *
 * <p>Deliberately a static service rather than command-private logic so future task-chain
 * steps and the C5 EllieGPS catalogue ingestion can call it directly without going through
 * the command layer.
 *
 * <p><b>Supported kinds</b> are exactly the C4.5 set the resolver understands: chest,
 * trapped chest (double variants canonicalized to the combiner-FIRST half), barrel, and
 * shulker boxes (all 17 colour variants). Every position this class returns is the
 * {@link ResolvedContainer#canonicalPos() canonical} position, so it can be passed verbatim
 * to {@code scan_storage} / {@code withdraw_from_storage} / {@code deposit_to_storage}.
 *
 * <p><b>Stale-tracker rule</b> (2026-06-11 lesson): {@link
 * com.player2.playerengine.commands.BlockScanner} tracked positions are append-only — mined
 * blocks persist — so every candidate is LIVE-validated through
 * {@link ContainerResolver#resolve} (which also applies the non-loading chunk guard) before
 * it is trusted. Unloaded-chunk candidates are skipped, never sync-loaded.
 */
public final class StorageLocator {

    /** Server-side ray-trace reach (blocks) for the "container the user is looking at" lookup. */
    public static final double LOOK_REACH_BLOCKS = 20.0;

    /**
     * The supported container blocks (the C4.5 resolver set): chest, trapped chest, barrel,
     * and the 17 shulker box variants. Ender chests are intentionally absent (per-player
     * storage, rejected by the resolver).
     */
    private static final Block[] SUPPORTED_BLOCKS = {
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,
    };

    private StorageLocator() {
    }

    /** A located supported container: its canonical position plus the resolved kind. */
    public record Located(BlockPos canonicalPos, ContainerKind kind) {
    }

    /**
     * Finds the supported container nearest to {@code origin} (Euclidean, block centers)
     * within {@code maxRadiusBlocks}, live-validating candidates nearest-first.
     *
     * <p>Candidate discovery is the bot's {@code BlockScanner} tracked positions; each
     * candidate is re-resolved live (loaded chunks only) before being trusted, so stale
     * (mined) tracker entries are skipped. Pure lookup: no navigation, no chunk loading.
     */
    public static Optional<Located> findNearestContainer(
            PlayerEngineController mod, Vec3 origin, double maxRadiusBlocks) {
        ServerLevel level = mod.getWorld();
        if (level == null || origin == null) {
            return Optional.empty();
        }
        double capSq = maxRadiusBlocks * maxRadiusBlocks;
        List<BlockPos> candidates = mod.getBlockScanner().getKnownLocations(SUPPORTED_BLOCKS);
        candidates.sort(Comparator.comparingDouble(p -> origin.distanceToSqr(Vec3.atCenterOf(p))));
        for (BlockPos candidate : candidates) {
            if (origin.distanceToSqr(Vec3.atCenterOf(candidate)) > capSq) {
                // Sorted nearest-first: everything beyond this is also out of range.
                break;
            }
            Optional<Located> located = resolveSupported(level, candidate);
            if (located.isPresent()) {
                return located;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the supported container {@code player} is currently looking at, via a
     * server-side ray trace from the player's eyes ({@code Entity.pick}, fluids ignored),
     * or empty when the player is looking at anything else. The reported position is the
     * canonical one (a double chest reports its canonical half even when the player looks
     * at the other half).
     *
     * <p>Caller is responsible for ensuring {@code player} is in {@code level} (the bot's
     * level) so the coordinates are meaningful to the storage commands.
     */
    public static Optional<Located> findLookedAtContainer(
            ServerLevel level, ServerPlayer player, double reachBlocks) {
        if (level == null || player == null) {
            return Optional.empty();
        }
        HitResult hit = player.pick(reachBlocks, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return Optional.empty();
        }
        return resolveSupported(level, blockHit.getBlockPos());
    }

    /**
     * Live-validates {@code pos} through {@link ContainerResolver#resolve} (non-loading
     * chunk guard included) and returns it as a {@link Located} when it is one of the
     * supported kinds; empty for unloaded chunks, stale positions, and unsupported
     * ({@link ContainerKind#GENERIC}) containers.
     */
    private static Optional<Located> resolveSupported(ServerLevel level, BlockPos pos) {
        ContainerResolver.Resolution resolution = ContainerResolver.resolve(level, pos);
        if (!resolution.ok()) {
            return Optional.empty();
        }
        ResolvedContainer resolved = resolution.resolved();
        if (resolved.kind() == ContainerKind.GENERIC) {
            return Optional.empty();
        }
        return Optional.of(new Located(resolved.canonicalPos(), resolved.kind()));
    }
}
