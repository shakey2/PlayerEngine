package com.player2.playerengine.mixins;

import com.player2.playerengine.automaton.api.entity.LivingEntityPlacementContext;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockPlaceContext.class)
public class MixinBlockPlaceContext {

    @Shadow
    protected boolean replaceClicked;

    // Wall signs call getNearestLookingDirections(); vanilla passes null Player into orderedByNearest.
    @Inject(method = "getNearestLookingDirections", at = @At("HEAD"), cancellable = true)
    private void playerengine$getNearestLookingDirections(CallbackInfoReturnable<Direction[]> cir) {
        BlockPlaceContext self = (BlockPlaceContext) (Object) this;
        if (self.getPlayer() != null) {
            return;
        }
        LivingEntity le = LivingEntityPlacementContext.peek();
        if (le == null) {
            return;
        }
        Direction[] adirection = Direction.orderedByNearest(le);
        if (this.replaceClicked) {
            cir.setReturnValue(adirection);
            return;
        }
        Direction direction = self.getClickedFace();
        int i;
        for (i = 0; i < adirection.length && adirection[i] != direction.getOpposite(); ++i) {
        }
        if (i > 0) {
            System.arraycopy(adirection, 0, adirection, 1, i);
            adirection[0] = direction.getOpposite();
        }
        cir.setReturnValue(adirection);
    }

    @Inject(method = "getNearestLookingDirection", at = @At("HEAD"), cancellable = true)
    private void playerengine$getNearestLookingDirection(CallbackInfoReturnable<Direction> cir) {
        BlockPlaceContext self = (BlockPlaceContext) (Object) this;
        if (self.getPlayer() != null) {
            return;
        }
        LivingEntity le = LivingEntityPlacementContext.peek();
        if (le == null) {
            return;
        }
        cir.setReturnValue(Direction.fromYRot(le.getYRot()));
    }

    @Inject(method = "getNearestLookingVerticalDirection", at = @At("HEAD"), cancellable = true)
    private void playerengine$getNearestLookingVerticalDirection(CallbackInfoReturnable<Direction> cir) {
        BlockPlaceContext self = (BlockPlaceContext) (Object) this;
        if (self.getPlayer() != null) {
            return;
        }
        LivingEntity le = LivingEntityPlacementContext.peek();
        if (le == null) {
            return;
        }
        Vec3 look = le.getLookAngle();
        if (look.y > 0.45) {
            cir.setReturnValue(Direction.UP);
        } else if (look.y < -0.45) {
            cir.setReturnValue(Direction.DOWN);
        } else {
            cir.setReturnValue(look.y >= 0 ? Direction.UP : Direction.DOWN);
        }
    }
}
