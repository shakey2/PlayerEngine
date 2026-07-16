package com.player2.playerengine.tasks.farming;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Minecraft 1.20.1 adapter for the NPC-owned one-damage-per-fresh-till contract. */
public final class HoeDurabilityCompat {
    private HoeDurabilityCompat() {
    }

    public static boolean damageOnce(ItemStack stack, LivingEntity npc) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(npc, "npc");
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        stack.hurtAndBreak(1, npc,
                broken -> broken.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        // This reports that exactly one vanilla durability attempt was issued. Unbreaking may
        // legitimately absorb the point; unenchanted fixtures verify the observable +1/break shape.
        return true;
    }
}
