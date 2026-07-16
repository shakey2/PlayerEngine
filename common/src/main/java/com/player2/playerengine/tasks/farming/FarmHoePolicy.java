package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.List;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;

/** Shared eligibility and live-durability policy for farm hoe acquisition and use. */
public final class FarmHoePolicy {

    /**
     * Exact vanilla items that can be looked up in EllieGPS inventory snapshots. Inventory
     * eligibility remains tier-based through {@link #isAccepted(ItemStack)}, so compatible
     * modded hoes that use one of vanilla's stone-or-better tiers still count once carried.
     */
    private static final List<Item> MARKED_STORAGE_ITEMS = List.of(
            Items.STONE_HOE,
            Items.IRON_HOE,
            Items.DIAMOND_HOE,
            Items.NETHERITE_HOE);

    private FarmHoePolicy() {
    }

    /** Deterministic, cheapest-first exact-item order used for EllieGPS snapshot searches. */
    public static List<Item> markedStorageItems() {
        return MARKED_STORAGE_ITEMS;
    }

    /** True when the stack can be consumed by the farm tilling task's tier contract. */
    public static boolean isAccepted(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof HoeItem hoe
                && isStoneOrBetter(hoe);
    }

    public static boolean isStoneOrBetter(HoeItem hoe) {
        if (hoe == null) {
            return false;
        }
        return hoe.getTier() == Tiers.STONE
                || hoe.getTier() == Tiers.IRON
                || hoe.getTier() == Tiers.DIAMOND
                || hoe.getTier() == Tiers.NETHERITE;
    }

    /**
     * Sums usable durability across all accepted hoes in main inventory, saturating instead of
     * overflowing if another mod supplies unusually large durability values.
     */
    public static int remainingDurability(LivingEntityInventory inventory) {
        if (inventory == null) {
            return 0;
        }
        return remainingDurability(inventory.main);
    }

    static int remainingDurability(Iterable<ItemStack> stacks) {
        if (stacks == null) {
            return 0;
        }
        long remaining = 0L;
        for (ItemStack stack : stacks) {
            if (!isAccepted(stack)) {
                continue;
            }
            remaining += Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
            if (remaining >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) remaining;
    }

    public static boolean hasDurability(LivingEntityInventory inventory, int requiredDurability) {
        return requiredDurability >= 0
                && remainingDurability(inventory) >= requiredDurability;
    }
}
