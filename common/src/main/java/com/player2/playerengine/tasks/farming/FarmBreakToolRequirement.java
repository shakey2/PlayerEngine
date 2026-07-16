package com.player2.playerengine.tasks.farming;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable tool fact captured from the same block-state read as a farm repair action. */
public record FarmBreakToolRequirement(
        Kind kind,
        Family family,
        StandardTier minimumTier,
        BlockState expectedState) {

    public enum Kind {
        NONE,
        STANDARD,
        CUSTOM
    }

    public enum Family {
        PICKAXE,
        AXE,
        SHOVEL,
        HOE
    }

    public enum StandardTier {
        WOOD,
        STONE,
        IRON,
        DIAMOND
    }

    public FarmBreakToolRequirement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expectedState, "expectedState");
        if (kind == Kind.STANDARD) {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(minimumTier, "minimumTier");
        } else if (family != null || minimumTier != null) {
            throw new IllegalArgumentException(
                    "only a standard repair-tool requirement has a family and tier");
        }
    }

    public static FarmBreakToolRequirement resolve(BlockState state) {
        BlockState expected = Objects.requireNonNull(state, "state");
        if (!expected.requiresCorrectToolForDrops()) {
            return none(expected);
        }

        // A block using the vanilla mining contract declares its family and minimum tier through
        // tags. Read those declarations directly so the frozen manifest is independent of the
        // current player and inventory. ItemStack methods remain the final authority when checking
        // an actual carried tool, preserving loader tier sorting and 1.21 tool-component behavior.
        for (Family candidateFamily : Family.values()) {
            if (expected.is(tagFor(candidateFamily))) {
                FarmBreakToolRequirement tagged = new FarmBreakToolRequirement(
                        Kind.STANDARD,
                        candidateFamily,
                        minimumTaggedTier(expected),
                        expected);
                for (Item item : standardItemsAtOrAbove(
                        candidateFamily, tagged.minimumTier(), 1)) {
                    if (tagged.accepts(item.getDefaultInstance())) {
                        return tagged;
                    }
                }
                // The block uses a vanilla family tag but rejects every vanilla candidate (for
                // example a mod-specific harvest rule). Keep it inventory-only instead of
                // promising a standard craft fallback that cannot work.
                return custom(expected);
            }
        }

        // Some mod blocks require a correct tool without joining a vanilla mining-family tag.
        // Recognize those only when a vanilla tool actually proves compatible; otherwise keep the
        // exact state as a custom, inventory-only requirement instead of guessing a craft target.
        ArrayList<Family> order = new ArrayList<>(List.of(Family.values()));
        for (Family candidateFamily : order) {
            for (StandardTier candidateTier : StandardTier.values()) {
                ItemStack candidate = new ItemStack(itemFor(candidateFamily, candidateTier));
                if (effectiveAndCorrect(candidate, expected)) {
                    return new FarmBreakToolRequirement(
                            Kind.STANDARD, candidateFamily, candidateTier, expected);
                }
            }
        }
        return custom(expected);
    }

    private static StandardTier minimumTaggedTier(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return StandardTier.DIAMOND;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return StandardTier.IRON;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return StandardTier.STONE;
        }
        return StandardTier.WOOD;
    }

    public static FarmBreakToolRequirement none(BlockState state) {
        return new FarmBreakToolRequirement(
                Kind.NONE, null, null, Objects.requireNonNull(state, "state"));
    }

    public static FarmBreakToolRequirement custom(BlockState state) {
        return new FarmBreakToolRequirement(
                Kind.CUSTOM, null, null, Objects.requireNonNull(state, "state"));
    }

    public boolean requiresTool() {
        return kind != Kind.NONE;
    }

    public boolean isStandard() {
        return kind == Kind.STANDARD;
    }

    public boolean isCustom() {
        return kind == Kind.CUSTOM;
    }

    /** Uses the exact frozen state so modded tools and loader tier rules remain authoritative. */
    public boolean accepts(ItemStack stack) {
        if (!requiresTool()) {
            return true;
        }
        return stack != null
                && !stack.isEmpty()
                && remainingUses(stack) > 0
                && effectiveAndCorrect(stack, expectedState);
    }

    static boolean effectiveAndCorrect(ItemStack stack, BlockState state) {
        return stack != null
                && !stack.isEmpty()
                && stack.getDestroySpeed(state) > 1.0F
                && (!state.requiresCorrectToolForDrops()
                || stack.isCorrectToolForDrops(state));
    }

    public static int remainingUses(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int maximum = stack.getMaxDamage();
        return maximum <= 0
                ? Integer.MAX_VALUE
                : Math.max(0, maximum - stack.getDamageValue());
    }

    static List<Item> standardItemsAtOrAbove(
            Family family,
            StandardTier minimumTier,
            int requiredUses) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(minimumTier, "minimumTier");
        if (requiredUses < 1) {
            throw new IllegalArgumentException("requiredUses must be positive");
        }
        ArrayList<Item> result = new ArrayList<>();
        for (StandardTier tier : StandardTier.values()) {
            if (tier.ordinal() < minimumTier.ordinal()) {
                continue;
            }
            Item item = itemFor(family, tier);
            if (remainingUses(item.getDefaultInstance()) >= requiredUses) {
                result.add(item);
            }
        }
        Item netherite = netheriteItemFor(family);
        if (remainingUses(netherite.getDefaultInstance()) >= requiredUses) {
            result.add(netherite);
        }
        return List.copyOf(result);
    }

    static Item fallbackItem(
            Family family,
            StandardTier minimumTier,
            int requiredUses) {
        for (StandardTier tier : StandardTier.values()) {
            if (tier.ordinal() < minimumTier.ordinal()) {
                continue;
            }
            Item item = itemFor(family, tier);
            if (remainingUses(item.getDefaultInstance()) >= requiredUses) {
                return item;
            }
        }
        return null;
    }

    static Item itemFor(Family family, StandardTier tier) {
        return switch (Objects.requireNonNull(family, "family")) {
            case PICKAXE -> switch (Objects.requireNonNull(tier, "tier")) {
                case WOOD -> Items.WOODEN_PICKAXE;
                case STONE -> Items.STONE_PICKAXE;
                case IRON -> Items.IRON_PICKAXE;
                case DIAMOND -> Items.DIAMOND_PICKAXE;
            };
            case AXE -> switch (Objects.requireNonNull(tier, "tier")) {
                case WOOD -> Items.WOODEN_AXE;
                case STONE -> Items.STONE_AXE;
                case IRON -> Items.IRON_AXE;
                case DIAMOND -> Items.DIAMOND_AXE;
            };
            case SHOVEL -> switch (Objects.requireNonNull(tier, "tier")) {
                case WOOD -> Items.WOODEN_SHOVEL;
                case STONE -> Items.STONE_SHOVEL;
                case IRON -> Items.IRON_SHOVEL;
                case DIAMOND -> Items.DIAMOND_SHOVEL;
            };
            case HOE -> switch (Objects.requireNonNull(tier, "tier")) {
                case WOOD -> Items.WOODEN_HOE;
                case STONE -> Items.STONE_HOE;
                case IRON -> Items.IRON_HOE;
                case DIAMOND -> Items.DIAMOND_HOE;
            };
        };
    }

    private static Item netheriteItemFor(Family family) {
        return switch (family) {
            case PICKAXE -> Items.NETHERITE_PICKAXE;
            case AXE -> Items.NETHERITE_AXE;
            case SHOVEL -> Items.NETHERITE_SHOVEL;
            case HOE -> Items.NETHERITE_HOE;
        };
    }

    private static TagKey<Block> tagFor(Family family) {
        return switch (family) {
            case PICKAXE -> BlockTags.MINEABLE_WITH_PICKAXE;
            case AXE -> BlockTags.MINEABLE_WITH_AXE;
            case SHOVEL -> BlockTags.MINEABLE_WITH_SHOVEL;
            case HOE -> BlockTags.MINEABLE_WITH_HOE;
        };
    }
}
