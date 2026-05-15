package com.player2.playerengine.automaton.api.entity;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.entity.LivingEntity;

/**
 * While {@link LivingEntityInteractionManager} runs {@link net.minecraft.world.item.ItemStack#useOn}
 * for a non-{@link net.minecraft.world.entity.player.Player} mob, vanilla {@link
 * net.minecraft.world.item.context.BlockPlaceContext} still keeps a null player reference; wall signs
 * then call {@code Direction.fromYRot(player.getYRot())} on null. We push the real {@link LivingEntity}
 * here so mixins can substitute look direction safely (supports nested calls via a stack).
 */
public final class LivingEntityPlacementContext {
    private static final ThreadLocal<Deque<LivingEntity>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private LivingEntityPlacementContext() {
    }

    public static void push(LivingEntity entity) {
        STACK.get().push(entity);
    }

    public static void pop() {
        Deque<LivingEntity> d = STACK.get();
        if (!d.isEmpty()) {
            d.pop();
        }
    }

    public static LivingEntity peek() {
        return STACK.get().peek();
    }
}
