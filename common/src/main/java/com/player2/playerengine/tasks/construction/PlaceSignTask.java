package com.player2.playerengine.tasks.construction;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.ApproachNearBlockPosTask;
import com.player2.playerengine.util.sign.SignScanSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Paths near an anchor block, places a sign from the main hand using the same right-click path as
 * other tasks, then writes front/back text on the server.
 */
public class PlaceSignTask extends Task {
    /** Squared distance from bot to anchor center within which we attempt the right-click. */
    private static final double REACH_SQ = 6.25 * 6.25;

    /** Baritone goal radius (blocks, Euclidean from anchor block) — no exact stand tile required. */
    private static final int APPROACH_RADIUS = 4;

    private final BlockPos anchor;
    private final Direction faceTowardAir;
    private final Item signItem;
    private final Component[] frontLines;
    private final Component[] backLines;
    private final BlockPos expectedSignPos;
    private final Runnable onSuccess;
    private final java.util.function.Consumer<String> onFail;

    private boolean gotoDispatched;
    private boolean clicked;
    private int settleTicks;
    private boolean finished;

    public PlaceSignTask(
            BlockPos anchor,
            Direction faceTowardAir,
            Item signItem,
            Component[] frontLines,
            Component[] backLines,
            Runnable onSuccess,
            java.util.function.Consumer<String> onFail) {
        this.anchor = anchor;
        this.faceTowardAir = faceTowardAir;
        this.signItem = signItem;
        this.frontLines = frontLines;
        this.backLines = backLines;
        this.expectedSignPos = SignScanSupport.expectedSignBlockPos(anchor, faceTowardAir);
        this.onSuccess = onSuccess;
        this.onFail = onFail;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    protected void onStart() {
        this.gotoDispatched = false;
        this.clicked = false;
        this.settleTicks = 0;
        this.finished = false;
    }

    @Override
    protected Task onTick() {
        if (this.finished) {
            return null;
        }
        PlayerEngineController mod = this.controller;
        ServerLevel level = mod.getWorld();
        BlockState support = level.getBlockState(this.anchor);
        if (support.isAir()) {
            this.failNow("anchor_air");
            return null;
        }

        if (!(this.signItem instanceof SignItem)) {
            this.failNow("not_sign_item");
            return null;
        }

        if (!this.gotoDispatched) {
            this.gotoDispatched = true;
            if (withinReach(mod)) {
                return null;
            }
            return new ApproachNearBlockPosTask(this.anchor, APPROACH_RADIUS);
        }

        if (!this.clicked) {
            if (!withinReach(mod)) {
                return new ApproachNearBlockPosTask(this.anchor, APPROACH_RADIUS);
            }

            if (!mod.getSlotHandler().forceEquipItem(this.signItem)) {
                this.failNow("equip_failed");
                return null;
            }

            BlockHitResult hit = SignScanSupport.blockHitForSignUse(this.anchor, this.faceTowardAir, this.signItem);
            Vec3 look = hit.getLocation().subtract(mod.getPlayer().getEyePosition(1.0F)).normalize();
            double yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
            double horizDist = Math.sqrt(look.x * look.x + look.z * look.z);
            double pitch = Math.toDegrees(-Math.atan2(look.y, horizDist));
            mod.getPlayer().setYRot((float) yaw);
            mod.getPlayer().setXRot((float) pitch);

            boolean sneak = SignScanSupport.shouldSneakToPlaceAgainst(support);
            boolean wasSneak = mod.getPlayer().isShiftKeyDown();
            mod.getPlayer().setShiftKeyDown(sneak);
            InteractionResult res = mod.getBaritone()
                    .getEntityContext()
                    .playerController()
                    .processRightClickBlock(mod.getPlayer(), level, InteractionHand.MAIN_HAND, hit);
            mod.getPlayer().setShiftKeyDown(wasSneak);
            if (res.shouldSwing()) {
                mod.getPlayer().swing(InteractionHand.MAIN_HAND, true);
            }
            mod.getSlotHandler().registerSlotAction();
            this.clicked = true;
            this.settleTicks = 0;
            return null;
        }

        if (this.settleTicks < 4) {
            this.settleTicks++;
            return null;
        }

        if (!(level.getBlockEntity(this.expectedSignPos) instanceof SignBlockEntity sbe)) {
            this.failNow("no_sign_block_entity_at_expected");
            return null;
        }

        if (sbe.isWaxed()) {
            this.failNow("sign_waxed");
            return null;
        }

        boolean okFront = applySide(sbe, true, this.frontLines);
        boolean okBack = applySide(sbe, false, this.backLines);
        if (!okFront || !okBack) {
            this.failNow("set_text_failed");
            return null;
        }

        shrinkOneSignInHand(mod, this.signItem);
        sbe.setChanged();
        level.sendBlockUpdated(this.expectedSignPos, sbe.getBlockState(), sbe.getBlockState(), 3);
        this.finished = true;
        this.onSuccess.run();
        return null;
    }

    private void failNow(String code) {
        this.finished = true;
        this.onFail.accept(code);
    }

    private boolean withinReach(PlayerEngineController mod) {
        return mod.getPlayer().position().distanceToSqr(Vec3.atCenterOf(this.anchor)) <= REACH_SQ;
    }

    private static boolean applySide(SignBlockEntity sbe, boolean front, Component[] lines) {
        SignText base = front ? sbe.getFrontText() : sbe.getBackText();
        DyeColor color = base.getColor();
        boolean glow = base.hasGlowingText();
        Component[] msgs = new Component[4];
        for (int i = 0; i < 4; i++) {
            msgs[i] = i < lines.length && lines[i] != null ? lines[i] : Component.empty();
        }
        SignText st = new SignText(msgs, msgs, color, glow);
        return sbe.setText(st, front);
    }

    private static void shrinkOneSignInHand(PlayerEngineController mod, Item signItem) {
        ItemStack hand = mod.getPlayer().getItemInHand(InteractionHand.MAIN_HAND);
        if (!hand.isEmpty() && hand.is(signItem)) {
            hand.shrink(1);
            mod.getSlotHandler().registerSlotAction();
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceSignTask t
                && t.anchor.equals(this.anchor)
                && t.faceTowardAir == this.faceTowardAir
                && t.signItem == this.signItem;
    }

    @Override
    protected String toDebugString() {
        return "PlaceSignTask";
    }
}
