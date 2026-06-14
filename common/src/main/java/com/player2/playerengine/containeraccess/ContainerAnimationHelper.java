package com.player2.playerengine.containeraccess;

import com.player2.playerengine.util.Debug;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Vanilla-only open/close animation for bot container sessions (Part C4.5, WS2).
 *
 * <p><b>Mechanism (Decision 6):</b> the chest/shulker lid is driven by block event id 1 —
 * {@code level.blockEvent(pos, block, 1, openCount)} — the same packet vanilla broadcasts;
 * sounds are played explicitly. The vanilla Player-based open-tracking path (the openers
 * counter) is NOT viable: it requires a {@code Player} (the bot is a {@code LivingEntity};
 * null NPEs) and its 5-tick recheck force-closes any fake open without a real
 * {@code ChestMenu} viewer. Pure common-module vanilla server API — no loader code.
 *
 * <p><b>Intended side effect:</b> trapped chests emit no redstone pulse during bot access
 * (this path bypasses the openers counter that drives their signal) — silent bot access does
 * not trigger traps. Do not "fix" by reintroducing the counter path.
 *
 * <p>Animation problems are cosmetic only (Decision 5): skips produce a log line, never a
 * model note or player chat line. All branches defensively no-op when the block/BE is gone
 * (mid-session removal is reported by the owning task, not here).
 */
public final class ContainerAnimationHelper {

    /**
     * Minimum ticks a session must stay open before closing — opening and closing on the same
     * tick renders nothing. Tasks wait this out even when validation fails right after the lid
     * opens (the failure reports immediately; the lid still closes through the normal guarded
     * stop path, never skipped, never double-fired).
     */
    public static final int ANIMATION_MIN_HOLD_TICKS = 20;

    /** Vanilla container-sound volume/pitch (replicates ChestBlockEntity.playSound et al.). */
    private static final float SOUND_VOLUME = 0.5F;

    /** Players holding a container GUI farther than this (squared blocks) are not "viewers". */
    private static final double VIEWER_RANGE_SQ = 64.0;

    private ContainerAnimationHelper() {
    }

    /**
     * Opens the container visually: chests get block event id 1 / param 1 on BOTH halves (the
     * renderer takes the max openness, but mirror vanilla's both-halves behavior) plus
     * {@code CHEST_OPEN} once at the double-chest center; barrels get the persistent
     * {@code OPEN=true} blockstate (a stuck-open barrel from an interrupted prior session is
     * adopted silently — the defensive normalize); shulkers are best-effort and skip when a
     * real viewer already holds the box open; {@code GENERIC} is a no-op.
     */
    public static void open(ServerLevel level, ResolvedContainer resolved) {
        if (resolved.kind().isChest()) {
            openChest(level, resolved);
        } else if (resolved.kind() == ContainerKind.BARREL) {
            openBarrel(level, resolved.canonicalPos());
        } else if (resolved.kind() == ContainerKind.SHULKER_BOX) {
            openShulker(level, resolved.canonicalPos());
        }
        // GENERIC: no animation (furnaces/hoppers/... have none).
    }

    /**
     * Closes the container visually. Chest close is GUARDED: if
     * {@code ChestBlockEntity.getOpenCount} reports a real viewer on EITHER half, our close is
     * skipped (vanilla will close the lid when that viewer leaves; this is what prevents the
     * lid slamming on a co-viewing player). Barrels close unconditionally (the {@code OPEN}
     * blockstate is persistent and must never be left stuck). Shulkers use the best-effort
     * real-viewer guard. Always safe to call from stop/finish paths — idempotent and
     * defensive against removed blocks.
     */
    public static void close(ServerLevel level, ResolvedContainer resolved) {
        if (resolved.kind().isChest()) {
            closeChest(level, resolved);
        } else if (resolved.kind() == ContainerKind.BARREL) {
            closeBarrel(level, resolved.canonicalPos());
        } else if (resolved.kind() == ContainerKind.SHULKER_BOX) {
            closeShulker(level, resolved.canonicalPos());
        }
    }

    // ------------------------------------------------------------------ chests

    private static void openChest(ServerLevel level, ResolvedContainer resolved) {
        BlockState state = level.getBlockState(resolved.canonicalPos());
        if (!(state.getBlock() instanceof ChestBlock)) {
            return; // block gone mid-session; cosmetic no-op
        }
        level.blockEvent(resolved.canonicalPos(), state.getBlock(), 1, 1);
        sendChestEventToSecondaryHalf(level, resolved, 1);
        // Canonical pos is never the LEFT half (RIGHT or SINGLE), so this plays exactly once.
        playChestSound(level, resolved.canonicalPos(), state, SoundEvents.CHEST_OPEN);
    }

    private static void closeChest(ServerLevel level, ResolvedContainer resolved) {
        // Guarded close (Decision 5 / WS2): a real player may hold either half open via its
        // GUI — vanilla's openers counter will close the lid for us when they leave.
        int viewers = ChestBlockEntity.getOpenCount(level, resolved.canonicalPos());
        if (resolved.secondaryPos() != null) {
            viewers += ChestBlockEntity.getOpenCount(level, resolved.secondaryPos());
        }
        if (viewers > 0) {
            Debug.logInternal("StorageAccess: skipped chest close @ "
                    + ContainerResolver.formatPos(resolved.canonicalPos())
                    + " - " + viewers + " real viewer(s) hold it open (guarded close)");
            return;
        }
        BlockState state = level.getBlockState(resolved.canonicalPos());
        if (!(state.getBlock() instanceof ChestBlock)) {
            return;
        }
        level.blockEvent(resolved.canonicalPos(), state.getBlock(), 1, 0);
        sendChestEventToSecondaryHalf(level, resolved, 0);
        playChestSound(level, resolved.canonicalPos(), state, SoundEvents.CHEST_CLOSE);
    }

    private static void sendChestEventToSecondaryHalf(ServerLevel level, ResolvedContainer resolved, int openParam) {
        if (resolved.secondaryPos() == null) {
            return;
        }
        BlockState secondaryState = level.getBlockState(resolved.secondaryPos());
        if (secondaryState.getBlock() instanceof ChestBlock) {
            level.blockEvent(resolved.secondaryPos(), secondaryState.getBlock(), 1, openParam);
        }
    }

    /**
     * Replicates the package-private {@code ChestBlockEntity.playSound}: the LEFT half is
     * skipped (the RIGHT/FIRST half plays for both), and a RIGHT half offsets the sound half a
     * block toward its connected neighbor so it sits at the double-chest center.
     */
    private static void playChestSound(ServerLevel level, BlockPos pos, BlockState state, SoundEvent sound) {
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.LEFT) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        if (type == ChestType.RIGHT) {
            Direction direction = ChestBlock.getConnectedDirection(state);
            x += direction.getStepX() * 0.5;
            z += direction.getStepZ() * 0.5;
        }
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, SOUND_VOLUME,
                level.random.nextFloat() * 0.1F + 0.9F);
    }

    // ------------------------------------------------------------------ barrels

    private static void openBarrel(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BarrelBlock)) {
            return;
        }
        if (state.getValue(BarrelBlock.OPEN)) {
            // Defensive normalize: OPEN is a PERSISTENT blockstate, so a hard crash in a prior
            // session can leave it stuck open. Adopt it silently — this session now owns it and
            // the close path resets it.
            Debug.logInternal("StorageAccess: barrel @ " + ContainerResolver.formatPos(pos)
                    + " was already open (adopting stuck state; will close on session end)");
            return;
        }
        level.setBlock(pos, state.setValue(BarrelBlock.OPEN, true), 3);
        playCenteredSound(level, pos, SoundEvents.BARREL_OPEN);
    }

    private static void closeBarrel(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BarrelBlock)) {
            return;
        }
        if (!state.getValue(BarrelBlock.OPEN)) {
            return; // already closed — idempotent stop path
        }
        level.setBlock(pos, state.setValue(BarrelBlock.OPEN, false), 3);
        playCenteredSound(level, pos, SoundEvents.BARREL_CLOSE);
    }

    // ------------------------------------------------------------------ shulkers (best-effort)

    private static void openShulker(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShulkerBoxBlock)
                || !(level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box)) {
            return;
        }
        if (!box.isClosed()) {
            // Real-viewer guard: a player (or a previous event) already has the box open or
            // animating. Cosmetic skip only — log, no note, no player line (Decision 5).
            Debug.logInternal("StorageAccess: skipped shulker open @ " + ContainerResolver.formatPos(pos)
                    + " - box is not closed (real viewer or mid-animation)");
            return;
        }
        level.blockEvent(pos, state.getBlock(), 1, 1);
        playCenteredSound(level, pos, SoundEvents.SHULKER_BOX_OPEN);
    }

    private static void closeShulker(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ShulkerBoxBlock)
                || !(level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box)) {
            return;
        }
        // Real-viewer guard: block event id 1 OVERWRITES the BE's own openCount with the param,
        // so sending 0 while a player's GUI is open would slam the lid and corrupt their count.
        // ShulkerBoxBlockEntity.openCount has no public accessor on 1.20.1, so the viewer check
        // is the best-effort heuristic: any nearby player whose open menu is a ShulkerBoxMenu.
        if (hasShulkerViewerNearby(level, pos)) {
            Debug.logInternal("StorageAccess: skipped shulker close @ " + ContainerResolver.formatPos(pos)
                    + " - a real viewer has a shulker GUI open nearby (guarded close)");
            return;
        }
        if (box.isClosed()) {
            return; // already closed — idempotent stop path
        }
        level.blockEvent(pos, state.getBlock(), 1, 0);
        playCenteredSound(level, pos, SoundEvents.SHULKER_BOX_CLOSE);
    }

    private static boolean hasShulkerViewerNearby(ServerLevel level, BlockPos pos) {
        for (ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof ShulkerBoxMenu
                    && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                            <= VIEWER_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ shared

    /** Vanilla-style block-centered container sound (barrels, shulkers). */
    private static void playCenteredSound(ServerLevel level, BlockPos pos, SoundEvent sound) {
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                sound, SoundSource.BLOCKS, SOUND_VOLUME, level.random.nextFloat() * 0.1F + 0.9F);
    }
}
