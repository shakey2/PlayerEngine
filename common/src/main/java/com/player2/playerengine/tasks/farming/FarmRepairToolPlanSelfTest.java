package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Pure aggregation, tier, capacity, and full-inventory repair-tool checks. */
public final class FarmRepairToolPlanSelfTest {
    private FarmRepairToolPlanSelfTest() {
    }

    public static void runAll() {
        strongestTierCollapsesPerFamily();
        mixedFamiliesRemainIndependent();
        capacityAndFullInventorySelectionAreExact();
        customRequirementsAreInventoryOnly();
        customDurabilityIsReservedBeforeStandards();
        multitoolDurabilityIsSharedAcrossFamilies();
        constrainedCustomNeedsPreserveSpecializedSlots();
        incompatibleVanillaFallbacksAreFiltered();
        noToolSelectionAlwaysFindsTheSafestAvailableHand();
    }

    private static void strongestTierCollapsesPerFamily() {
        FarmBreakToolRequirement cobble = standard(
                Blocks.COBBLESTONE.defaultBlockState(),
                FarmBreakToolRequirement.Family.PICKAXE,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmBreakToolRequirement obsidian = standard(
                Blocks.OBSIDIAN.defaultBlockState(),
                FarmBreakToolRequirement.Family.PICKAXE,
                FarmBreakToolRequirement.StandardTier.DIAMOND);
        require(cobble.isStandard()
                        && cobble.family() == FarmBreakToolRequirement.Family.PICKAXE
                        && cobble.minimumTier() == FarmBreakToolRequirement.StandardTier.WOOD,
                "cobblestone requires a wooden-or-better pickaxe");
        require(obsidian.isStandard()
                        && obsidian.family() == FarmBreakToolRequirement.Family.PICKAXE
                        && obsidian.minimumTier()
                        == FarmBreakToolRequirement.StandardTier.DIAMOND,
                "obsidian requires a diamond-or-better pickaxe");

        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, cobble), action(1, obsidian)));
        require(plan.standardRequirements().size() == 1,
                "one strongest requirement is retained per tool family");
        FarmRepairToolPlan.Requirement pickaxe = plan.standardRequirements().get(0);
        require(pickaxe.family() == FarmBreakToolRequirement.Family.PICKAXE
                        && pickaxe.minimumTier()
                        == FarmBreakToolRequirement.StandardTier.DIAMOND
                        && pickaxe.useCount() == 2,
                "cobblestone plus obsidian becomes one two-use diamond pickaxe need");
        if (pickaxe.isSatisfiedBy(Items.DIAMOND_PICKAXE.getDefaultInstance())) {
            require(pickaxe.craftFallbackItem() == Items.DIAMOND_PICKAXE,
                    "the crafting fallback is the exact strongest compatible tier");
            require(pickaxe.markedStorageItems().equals(List.of(
                            Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)),
                    "EllieGPS searches each compatible required-or-stronger item");
        } else {
            require(pickaxe.craftFallbackItem() == null
                            && pickaxe.markedStorageItems().isEmpty(),
                    "headless missing tags cannot produce an unverified fallback");
        }
    }

    private static void mixedFamiliesRemainIndependent() {
        FarmBreakToolRequirement pickaxe = standard(
                Blocks.COBBLESTONE.defaultBlockState(),
                FarmBreakToolRequirement.Family.PICKAXE,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmBreakToolRequirement shovel = standard(
                Blocks.SNOW_BLOCK.defaultBlockState(),
                FarmBreakToolRequirement.Family.SHOVEL,
                FarmBreakToolRequirement.StandardTier.STONE);
        require(shovel.isStandard()
                        && shovel.family() == FarmBreakToolRequirement.Family.SHOVEL,
                "snow uses the standard shovel family");
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, pickaxe), action(1, shovel)));
        require(plan.standardRequirements().stream().map(
                        FarmRepairToolPlan.Requirement::family).toList().equals(List.of(
                        FarmBreakToolRequirement.Family.PICKAXE,
                        FarmBreakToolRequirement.Family.SHOVEL)),
                "different tool families remain separate deterministic requirements");
    }

    private static void capacityAndFullInventorySelectionAreExact() {
        FarmBreakToolRequirement obsidian = standard(
                Blocks.OBSIDIAN.defaultBlockState(),
                FarmBreakToolRequirement.Family.PICKAXE,
                FarmBreakToolRequirement.StandardTier.DIAMOND);
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, obsidian), action(1, obsidian)));
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        ItemStack almostBrokenDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
        almostBrokenDiamond.setDamageValue(almostBrokenDiamond.getMaxDamage() - 1);
        inventory.main.set(3, almostBrokenDiamond);
        require(FarmBreakToolRequirement.remainingUses(almostBrokenDiamond) == 1,
                "a one-use pickaxe does not satisfy a frozen two-break manifest");

        ItemStack netherite = new ItemStack(Items.NETHERITE_PICKAXE);
        inventory.main.set(20, netherite);
        // Bootstrap.bootStrap() initializes registries but does not load data-pack mining tags.
        // Run the exact live compatibility assertions only when that tag data is present; the
        // aggregation and durability assertions above remain deterministic in the headless suite.
        if (obsidian.accepts(netherite)) {
            require(plan.isSatisfied(inventory),
                    "a stronger tool with enough remaining uses satisfies the requirement");
            require(FarmRepairToolPlan.bestSlot(inventory, obsidian, 1) == 20,
                    "repair selection scans and returns the exact non-hotbar slot");
        }
    }

    private static void customRequirementsAreInventoryOnly() {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        FarmBreakToolRequirement custom = FarmBreakToolRequirement.custom(planks);
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(action(0, custom)));
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        require(plan.hasUnsatisfiedCustom(inventory),
                "an unknown custom tool contract cannot silently fall back to hand");
        inventory.main.set(17, new ItemStack(Items.IRON_AXE));
        if (custom.accepts(inventory.main.get(17))) {
            require(!plan.hasUnsatisfiedCustom(inventory) && plan.isSatisfied(inventory),
                    "an actual compatible carried tool may satisfy a custom block contract");
        }
    }

    private static void customDurabilityIsReservedBeforeStandards() {
        FarmBreakToolRequirement custom = FarmBreakToolRequirement.custom(
                Blocks.VINE.defaultBlockState());
        FarmBreakToolRequirement axe = standard(
                Blocks.COBWEB.defaultBlockState(),
                FarmBreakToolRequirement.Family.AXE,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, custom), action(1, custom),
                action(2, axe), action(3, axe)));
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        ItemStack threeUseMultitool = withRemainingUses(Items.SHEARS, 3);
        inventory.main.set(29, threeUseMultitool);

        require(!plan.hasUnsatisfiedCustom(inventory),
                "the carried multitool can reserve both inventory-only custom uses");
        require(plan.missingStandardRequirements(inventory).stream().map(
                        FarmRepairToolPlan.Requirement::family).toList().equals(List.of(
                        FarmBreakToolRequirement.Family.AXE)),
                "custom reservations leave only one residual use and expose the missing axe need");
        require(!plan.isSatisfied(inventory),
                "one three-use stack cannot cover two custom plus two standard breaks");
    }

    private static void multitoolDurabilityIsSharedAcrossFamilies() {
        FarmBreakToolRequirement axe = standard(
                Blocks.COBWEB.defaultBlockState(),
                FarmBreakToolRequirement.Family.AXE,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmBreakToolRequirement shovel = standard(
                Blocks.VINE.defaultBlockState(),
                FarmBreakToolRequirement.Family.SHOVEL,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, axe), action(1, axe),
                action(2, shovel), action(3, shovel)));
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        ItemStack multitool = withRemainingUses(Items.SHEARS, 3);
        inventory.main.set(35, multitool);

        require(plan.missingStandardRequirements(inventory).stream().map(
                        FarmRepairToolPlan.Requirement::family).toList().equals(List.of(
                        FarmBreakToolRequirement.Family.SHOVEL)),
                "a three-use multitool is allocated once and cannot promise four family uses");
        multitool.setDamageValue(multitool.getMaxDamage() - 4);
        require(plan.missingStandardRequirements(inventory).isEmpty()
                        && plan.isSatisfied(inventory),
                "one full four-use multitool may cover two families when summed capacity fits");
    }

    private static void constrainedCustomNeedsPreserveSpecializedSlots() {
        FarmBreakToolRequirement cobweb = FarmBreakToolRequirement.custom(
                Blocks.COBWEB.defaultBlockState());
        FarmBreakToolRequirement vine = FarmBreakToolRequirement.custom(
                Blocks.VINE.defaultBlockState());
        FarmRepairToolPlan plan = FarmRepairToolPlan.fromClearActions(List.of(
                action(0, cobweb), action(1, cobweb),
                action(2, vine), action(3, vine)));
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        inventory.main.set(4, withRemainingUses(Items.DIAMOND_SWORD, 2));
        inventory.main.set(31, withRemainingUses(Items.SHEARS, 2));

        require(!plan.hasUnsatisfiedCustom(inventory) && plan.isSatisfied(inventory),
                "most-constrained custom allocation reserves the only vine tool first");
    }

    private static void incompatibleVanillaFallbacksAreFiltered() {
        FarmBreakToolRequirement mislabeledPickaxe = standard(
                Blocks.OAK_PLANKS.defaultBlockState(),
                FarmBreakToolRequirement.Family.PICKAXE,
                FarmBreakToolRequirement.StandardTier.WOOD);
        FarmRepairToolPlan.Requirement requirement =
                FarmRepairToolPlan.fromClearActions(List.of(action(0, mislabeledPickaxe)))
                        .standardRequirements().get(0);
        require(requirement.markedStorageItems().isEmpty()
                        && requirement.craftFallbackItem() == null,
                "a family label never authorizes an actually incompatible vanilla fallback");
    }

    private static void noToolSelectionAlwaysFindsTheSafestAvailableHand() {
        FarmBreakToolRequirement noTool = FarmBreakToolRequirement.none(
                Blocks.TALL_GRASS.defaultBlockState());
        LivingEntityInventory inventory = new LivingEntityInventory(null);
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            inventory.main.set(slot, new ItemStack(Items.IRON_HOE));
        }
        inventory.selectedSlot = 5;
        inventory.main.set(31, new ItemStack(Items.DIRT));
        require(FarmRepairToolPlan.bestSlot(inventory, noTool, 1) == 31,
                "a non-damageable full-inventory stack wins over every damageable tool");

        inventory.main.set(31, new ItemStack(Items.IRON_HOE));
        inventory.main.set(22, new ItemStack(Items.IRON_PICKAXE));
        require(FarmRepairToolPlan.bestSlot(inventory, noTool, 1) == 22,
                "a damageable non-hoe is the bounded fallback when no safe stack exists");

        inventory.main.set(22, new ItemStack(Items.IRON_HOE));
        require(FarmRepairToolPlan.bestSlot(inventory, noTool, 1) == 5,
                "an all-hoe inventory still returns a deterministic selected slot instead of failing");
    }

    private static ItemStack withRemainingUses(Item item, int remainingUses) {
        if (remainingUses < 1) {
            throw new IllegalArgumentException("remainingUses must be positive");
        }
        ItemStack stack = new ItemStack(item);
        if (stack.getMaxDamage() < remainingUses) {
            throw new IllegalArgumentException("item has insufficient test durability");
        }
        stack.setDamageValue(stack.getMaxDamage() - remainingUses);
        return stack;
    }

    private static FarmBreakToolRequirement standard(
            BlockState state,
            FarmBreakToolRequirement.Family family,
            FarmBreakToolRequirement.StandardTier minimumTier) {
        return new FarmBreakToolRequirement(
                FarmBreakToolRequirement.Kind.STANDARD,
                family,
                minimumTier,
                state);
    }

    private static FarmPlotRepairPlan.ClearAction action(
            int offset,
            FarmBreakToolRequirement requirement) {
        BlockPos target = new BlockPos(offset, 64, 0);
        return new FarmPlotRepairPlan.ClearAction(
                target,
                target.offset(0, 1, 1),
                FarmSiteWorldView.stateFingerprint(requirement.expectedState()),
                FarmPlotRepairPlan.ClearPurpose.HEADROOM,
                requirement);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Farm repair tool self-test failed: " + message);
        }
    }
}
