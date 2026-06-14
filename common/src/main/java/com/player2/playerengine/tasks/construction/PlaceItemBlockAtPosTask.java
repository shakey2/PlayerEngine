package com.player2.playerengine.tasks.construction;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.IInteractionManagerProvider;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.ApproachNearBlockPosTask;
import com.player2.playerengine.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places one block from inventory onto a fixed air cell (support = {@code placePos.below()}).
 * Does not use Baritone's builder process (no cobblestone scaffolding).
 */
public class PlaceItemBlockAtPosTask extends Task {

    private static final int APPROACH_RADIUS = 4;
    private static final double REACH_SQ = 6.25 * 6.25;
    private static final int SETTLE_TICKS = 4;
    private static final int MAX_CLICK_ATTEMPTS = 8;

    private final BlockPos placePos;
    private final Block expectedBlock;
    private final Item placeItem;
    private int clickAttempts;
    private int settleTicks;

    public PlaceItemBlockAtPosTask(BlockPos placePos, Block expectedBlock) {
        this.placePos = placePos.immutable();
        this.expectedBlock = expectedBlock;
        this.placeItem = expectedBlock.asItem();
    }

    @Override
    public boolean isFinished() {
        return WorldHelper.isBlock(this.controller, this.placePos, this.expectedBlock);
    }

    @Override
    protected void onStart() {
        this.clickAttempts = 0;
        this.settleTicks = 0;
    }

    @Override
    protected Task onTick() {
        if (isFinished()) {
            return null;
        }
        PlayerEngineController mod = this.controller;
        BlockPos support = this.placePos.below();
        if (!WorldHelper.isSolidBlock(mod, support)) {
            fail("no_support");
            return null;
        }
        if (!WorldHelper.isAir(mod, this.placePos) && !isFinished()) {
            fail("occupied");
            return null;
        }
        if (!mod.getItemStorage().hasItem(this.placeItem)) {
            fail("missing_item");
            return null;
        }
        if (!withinReach(mod, this.placePos)) {
            this.setDebugState("Approaching placement site");
            return new ApproachNearBlockPosTask(this.placePos, APPROACH_RADIUS);
        }
        if (!mod.getSlotHandler().forceEquipItem(this.placeItem)) {
            this.setDebugState("Equipping place item");
            return null;
        }
        if (this.settleTicks > 0) {
            this.settleTicks--;
            this.setDebugState("Settling after place");
            return null;
        }
        if (this.clickAttempts >= MAX_CLICK_ATTEMPTS) {
            fail("place_click_exhausted");
            return null;
        }
        this.clickAttempts++;
        this.setDebugState("Placing at " + this.placePos.toShortString());
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(support).add(0.0, 0.5, 0.0),
                Direction.UP,
                support,
                false);
        aimAt(mod, hit.getLocation());
        InteractionHand hand = InteractionHand.MAIN_HAND;
        InteractionResult result = ((IInteractionManagerProvider) mod.getEntity())
                .getInteractionManager()
                .interactBlock(
                        mod.getPlayer(),
                        mod.getWorld(),
                        mod.getPlayer().getMainHandItem(),
                        hand,
                        hit);
        if (result.consumesAction() || isFinished()) {
            mod.getPlayer().swing(hand);
            this.settleTicks = SETTLE_TICKS;
            // ISSUE 2: register the just-placed block in the BlockScanner synchronously, the moment it is
            // verified present, instead of waiting for the next close-block scan or the 80-tick background
            // rescan. The world BlockPlaceEvent mixin only fires for redstone-conductor blocks, so a placed
            // CHEST (not a conductor in 1.20.1) is otherwise NOT indexed for several ticks. That gap is why
            // a follow-on resolve_storage_chest (preferExisting + no placement) scanned getKnownLocations
            // (CHEST) and found nothing despite the bot having just placed/used the adjacent chest. addBlock
            // self-guards with isBlockAtPosition, so this only registers a block actually present at placePos
            // (here gated on isFinished()) -- no phantom entries.
            if (isFinished()) {
                mod.getBlockScanner().addBlock(this.expectedBlock, this.placePos);
            }
        }
        return null;
    }

    private static void aimAt(PlayerEngineController mod, Vec3 target) {
        Vec3 look = target.subtract(mod.getPlayer().getEyePosition(1.0F)).normalize();
        double yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
        double horizDist = Math.sqrt(look.x * look.x + look.z * look.z);
        double pitch = Math.toDegrees(-Math.atan2(look.y, horizDist));
        mod.getPlayer().setYRot((float) yaw);
        mod.getPlayer().setXRot((float) pitch);
    }

    private static boolean withinReach(PlayerEngineController mod, BlockPos pos) {
        return mod.getPlayer().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQ;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceItemBlockAtPosTask task
                && task.placePos.equals(this.placePos)
                && task.expectedBlock == this.expectedBlock;
    }

    @Override
    protected String toDebugString() {
        return "PlaceItemBlockAtPos " + this.expectedBlock + " @ " + this.placePos.toShortString();
    }
}
