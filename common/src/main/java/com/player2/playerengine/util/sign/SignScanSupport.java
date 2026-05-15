package com.player2.playerengine.util.sign;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class SignScanSupport {
    public static final int MAX_SIGNS = 48;
    public static final int MAX_LINE_LEN = 192;

    private SignScanSupport() {
    }

    public static boolean isSignBlockEntity(BlockEntity be) {
        return be instanceof SignBlockEntity;
    }

    /**
     * Face is the direction from the anchor (support) block toward the adjacent air cell where the
     * sign is placed.
     */
    public static BlockHitResult blockHitForSignUse(BlockPos anchor, Direction faceTowardAir) {
        return blockHitForSignUse(anchor, faceTowardAir, null);
    }

    /**
     * Same as {@link #blockHitForSignUse(BlockPos, Direction)}, with optional {@code signItem} so
     * wall-mounted {@link HangingSignItem} uses a hit point on the upper part of the face (closer
     * to vanilla raycasts).
     */
    public static BlockHitResult blockHitForSignUse(BlockPos anchor, Direction faceTowardAir, @Nullable Item signItem) {
        BlockPos airPos = anchor.relative(faceTowardAir);
        Direction towardSolid = faceTowardAir.getOpposite();
        double hx = airPos.getX() + 0.5 + towardSolid.getStepX() * 0.5;
        double hy = airPos.getY() + 0.5 + towardSolid.getStepY() * 0.5;
        double hz = airPos.getZ() + 0.5 + towardSolid.getStepZ() * 0.5;

        if (signItem instanceof HangingSignItem && faceTowardAir.getAxis().isHorizontal()) {
            hy = anchor.getY() + 1.0 - 1.5 / 16.0;
            hx = Mth.clamp(hx, anchor.getX() + 1.0 / 16.0, anchor.getX() + 15.0 / 16.0);
            hz = Mth.clamp(hz, anchor.getZ() + 1.0 / 16.0, anchor.getZ() + 15.0 / 16.0);
        }

        Vec3 hitPos = new Vec3(hx, hy, hz);
        return new BlockHitResult(hitPos, faceTowardAir, anchor, false);
    }

    public static BlockPos expectedSignBlockPos(BlockPos anchor, Direction faceTowardAir) {
        if (faceTowardAir == Direction.UP) {
            return anchor.above();
        }
        if (faceTowardAir == Direction.DOWN) {
            return anchor.below();
        }
        return anchor.relative(faceTowardAir);
    }

    public static boolean shouldSneakToPlaceAgainst(BlockState supportState) {
        Block b = supportState.getBlock();
        return b == Blocks.CHEST
                || b == Blocks.TRAPPED_CHEST
                || b instanceof EnderChestBlock
                || b instanceof BarrelBlock
                || b instanceof ShulkerBoxBlock
                || b instanceof CraftingTableBlock
                || b instanceof DoorBlock
                || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock
                || b == Blocks.ANVIL
                || b == Blocks.CHIPPED_ANVIL
                || b == Blocks.DAMAGED_ANVIL
                || b == Blocks.ENCHANTING_TABLE
                || b == Blocks.GRINDSTONE
                || b == Blocks.STONECUTTER
                || b == Blocks.LOOM
                || b == Blocks.CARTOGRAPHY_TABLE
                || b == Blocks.SMITHING_TABLE
                || b == Blocks.FLETCHING_TABLE
                || b == Blocks.BELL
                || b == Blocks.LEVER
                || b == Blocks.REPEATER
                || b == Blocks.COMPARATOR
                || b == Blocks.NOTE_BLOCK
                || b == Blocks.REDSTONE_WIRE;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static String cardinalFromYaw(float yRotDegrees) {
        float y = yRotDegrees % 360.0f;
        if (y < 0) {
            y += 360.0f;
        }
        if (y >= 315 || y < 45) {
            return "south";
        }
        if (y < 135) {
            return "west";
        }
        if (y < 225) {
            return "north";
        }
        return "east";
    }
}
