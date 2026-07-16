package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.PlayerEngineController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exact, synchronous farming item-use boundary.
 *
 * <p>Farm mutation tasks equip an exact item before calling this helper. The live ray gate,
 * task-owned receipt baseline, block interaction, and item-use fallback all execute inline during
 * the task tick. This prevents later inventory maintenance from replacing the selected item and
 * avoids counting a queued input pulse that {@code BlockPlaceHelper}'s cooldown never dispatched.
 */
final class FarmInteractionDispatcher {
    private FarmInteractionDispatcher() {
    }

    static boolean isExpectedBlockHit(HitResult hit, BlockPos expectedBlock) {
        return hit instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(expectedBlock);
    }

    static boolean isExpectedBlockFace(
            HitResult hit,
            BlockPos expectedBlock,
            Direction expectedFace) {
        return isExpectedBlockHit(hit, expectedBlock)
                && ((BlockHitResult) hit).getDirection() == expectedFace;
    }

    static Optional<InteractionResult> dispatchExactBlock(
            HitResult hit,
            BlockPos expectedBlock,
            Runnable beforeDispatch,
            Function<BlockHitResult, InteractionResult> interaction) {
        if (!isExpectedBlockHit(hit, expectedBlock)) {
            return Optional.empty();
        }
        return dispatch((BlockHitResult) hit, beforeDispatch, interaction);
    }

    static Optional<InteractionResult> dispatchExactFace(
            HitResult hit,
            BlockPos expectedBlock,
            Direction expectedFace,
            Runnable beforeDispatch,
            Function<BlockHitResult, InteractionResult> interaction) {
        if (!isExpectedBlockFace(hit, expectedBlock, expectedFace)) {
            return Optional.empty();
        }
        return dispatch((BlockHitResult) hit, beforeDispatch, interaction);
    }

    static Optional<InteractionResult> dispatchMainHandBlockThenItemAtBlock(
            PlayerEngineController controller,
            HitResult hit,
            BlockPos expectedBlock,
            Runnable beforeDispatch) {
        return dispatchExactBlock(
                hit,
                expectedBlock,
                beforeDispatch,
                blockHit -> useMainHandBlockThenItem(controller, blockHit));
    }

    static Optional<InteractionResult> dispatchMainHandBlockUseAtFace(
            PlayerEngineController controller,
            HitResult hit,
            BlockPos expectedBlock,
            Direction expectedFace,
            Runnable beforeDispatch) {
        return dispatchExactFace(
                hit,
                expectedBlock,
                expectedFace,
                beforeDispatch,
                blockHit -> useMainHandBlock(controller, blockHit));
    }

    static Optional<InteractionResult> dispatchMainHandBlockThenItemAtFace(
            PlayerEngineController controller,
            HitResult hit,
            BlockPos expectedBlock,
            Direction expectedFace,
            Runnable beforeDispatch) {
        return dispatchExactFace(
                hit,
                expectedBlock,
                expectedFace,
                beforeDispatch,
                blockHit -> useMainHandBlockThenItem(controller, blockHit));
    }

    /** Synchronous exact block-use path for dirt, hoes, and planting block items. */
    static InteractionResult useMainHandBlock(
            PlayerEngineController controller,
            BlockHitResult hit) {
        Objects.requireNonNull(controller, "controller");
        return Objects.requireNonNull(
                controller.getBaritone().getEntityContext().playerController()
                        .processRightClickBlock(
                                controller.getPlayer(),
                                controller.getWorld(),
                                InteractionHand.MAIN_HAND,
                                hit),
                "block interaction result");
    }

    /** Mirrors one main-hand pass of BlockPlaceHelper for bucket callers that require item use. */
    static InteractionResult useMainHandBlockThenItem(
            PlayerEngineController controller,
            BlockHitResult hit) {
        return blockThenItemUse(
                hit,
                blockHit -> useMainHandBlock(controller, blockHit),
                () -> !controller.getPlayer().getMainHandItem().isEmpty(),
                () -> controller.getBaritone().getEntityContext().playerController()
                        .processRightClick(
                                controller.getPlayer(),
                                controller.getWorld(),
                                InteractionHand.MAIN_HAND));
    }

    static InteractionResult blockThenItemUse(
            BlockHitResult hit,
            Function<BlockHitResult, InteractionResult> blockInteraction,
            BooleanSupplier hasHeldItem,
            Supplier<InteractionResult> itemInteraction) {
        InteractionResult blockResult = Objects.requireNonNull(
                Objects.requireNonNull(blockInteraction, "blockInteraction").apply(hit),
                "block interaction result");
        if (blockResult.consumesAction()
                || !Objects.requireNonNull(hasHeldItem, "hasHeldItem").getAsBoolean()) {
            return blockResult;
        }
        return Objects.requireNonNull(
                Objects.requireNonNull(itemInteraction, "itemInteraction").get(),
                "item interaction result");
    }

    private static Optional<InteractionResult> dispatch(
            BlockHitResult hit,
            Runnable beforeDispatch,
            Function<BlockHitResult, InteractionResult> interaction) {
        Objects.requireNonNull(beforeDispatch, "beforeDispatch").run();
        InteractionResult result = Objects.requireNonNull(
                Objects.requireNonNull(interaction, "interaction").apply(hit),
                "interaction result");
        return Optional.of(result);
    }
}
