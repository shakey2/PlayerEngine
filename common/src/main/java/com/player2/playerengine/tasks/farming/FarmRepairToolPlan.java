package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Highest-tier, single-tool-per-family preflight derived from one frozen repair manifest. */
public final class FarmRepairToolPlan {

    public record Requirement(
            FarmBreakToolRequirement.Family family,
            FarmBreakToolRequirement.StandardTier minimumTier,
            int useCount,
            List<FarmBreakToolRequirement> blockRequirements) {
        public Requirement {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(minimumTier, "minimumTier");
            if (useCount < 1) {
                throw new IllegalArgumentException("repair tool use count must be positive");
            }
            blockRequirements = List.copyOf(Objects.requireNonNull(
                    blockRequirements, "blockRequirements"));
            if (blockRequirements.size() != useCount
                    || blockRequirements.stream().anyMatch(requirement ->
                    requirement == null
                            || !requirement.isStandard()
                            || requirement.family() != family)) {
                throw new IllegalArgumentException(
                        "repair tool requirement blocks do not match their family/use count");
            }
        }

        public boolean isSatisfiedBy(ItemStack stack) {
            return FarmBreakToolRequirement.remainingUses(stack) >= useCount
                    && acceptsEveryBlock(this, stack);
        }

        /** Exact minimum-or-stronger vanilla items searched in EllieGPS snapshots. */
        public List<Item> markedStorageItems() {
            ArrayList<Item> compatible = new ArrayList<>();
            for (Item candidate : FarmBreakToolRequirement.standardItemsAtOrAbove(
                    family, minimumTier, useCount)) {
                if (isSatisfiedBy(candidate.getDefaultInstance())) {
                    compatible.add(candidate);
                }
            }
            return List.copyOf(compatible);
        }

        /** Lowest standard tier which both satisfies the blocks and lasts for the whole repair. */
        public Item craftFallbackItem() {
            for (FarmBreakToolRequirement.StandardTier tier
                    : FarmBreakToolRequirement.StandardTier.values()) {
                if (tier.ordinal() < minimumTier.ordinal()) {
                    continue;
                }
                Item candidate = FarmBreakToolRequirement.itemFor(family, tier);
                if (isSatisfiedBy(candidate.getDefaultInstance())) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private record CustomNeed(
            BlockState state,
            int useCount,
            FarmBreakToolRequirement requirement) {
        private CustomNeed {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(requirement, "requirement");
            if (useCount < 1 || !requirement.isCustom()) {
                throw new IllegalArgumentException("invalid custom repair tool need");
            }
        }
    }

    private record AllocationResult(
            boolean customUnsatisfied,
            List<Requirement> missingStandard) {
        private AllocationResult {
            missingStandard = List.copyOf(missingStandard);
        }
    }

    private static final class StandardDraft {
        private FarmBreakToolRequirement.StandardTier minimumTier;
        private final List<FarmBreakToolRequirement> blocks = new ArrayList<>();

        private StandardDraft(FarmBreakToolRequirement requirement) {
            minimumTier = requirement.minimumTier();
            add(requirement);
        }

        private void add(FarmBreakToolRequirement requirement) {
            if (requirement.minimumTier().ordinal() > minimumTier.ordinal()) {
                minimumTier = requirement.minimumTier();
            }
            blocks.add(requirement);
        }
    }

    private static final FarmRepairToolPlan EMPTY = new FarmRepairToolPlan(
            List.of(), List.of());

    private final List<Requirement> standardRequirements;
    private final List<CustomNeed> customNeeds;
    private final String identity;

    private FarmRepairToolPlan(
            List<Requirement> standardRequirements,
            List<CustomNeed> customNeeds) {
        this.standardRequirements = List.copyOf(Objects.requireNonNull(
                standardRequirements, "standardRequirements"));
        this.customNeeds = List.copyOf(Objects.requireNonNull(customNeeds, "customNeeds"));
        this.identity = buildIdentity(this.standardRequirements, this.customNeeds);
    }

    static FarmRepairToolPlan fromClearActions(
            List<FarmPlotRepairPlan.ClearAction> actions) {
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            return EMPTY;
        }
        EnumMap<FarmBreakToolRequirement.Family, StandardDraft> standard =
                new EnumMap<>(FarmBreakToolRequirement.Family.class);
        LinkedHashMap<BlockState, Integer> customCounts = new LinkedHashMap<>();
        LinkedHashMap<BlockState, FarmBreakToolRequirement> customRequirements =
                new LinkedHashMap<>();
        for (FarmPlotRepairPlan.ClearAction action : actions) {
            FarmBreakToolRequirement requirement = Objects.requireNonNull(
                    action.breakToolRequirement(), "clear action breakToolRequirement");
            if (requirement.isStandard()) {
                StandardDraft draft = standard.get(requirement.family());
                if (draft == null) {
                    standard.put(requirement.family(), new StandardDraft(requirement));
                } else {
                    draft.add(requirement);
                }
            } else if (requirement.isCustom()) {
                BlockState state = requirement.expectedState();
                customCounts.merge(state, 1, Integer::sum);
                customRequirements.putIfAbsent(state, requirement);
            }
        }

        ArrayList<Requirement> resolvedStandard = new ArrayList<>();
        for (FarmBreakToolRequirement.Family family
                : FarmBreakToolRequirement.Family.values()) {
            StandardDraft draft = standard.get(family);
            if (draft != null) {
                resolvedStandard.add(new Requirement(
                        family, draft.minimumTier, draft.blocks.size(), draft.blocks));
            }
        }
        ArrayList<CustomNeed> resolvedCustom = new ArrayList<>();
        for (Map.Entry<BlockState, Integer> entry : customCounts.entrySet()) {
            resolvedCustom.add(new CustomNeed(
                    entry.getKey(), entry.getValue(), customRequirements.get(entry.getKey())));
        }
        return new FarmRepairToolPlan(resolvedStandard, resolvedCustom);
    }

    public List<Requirement> standardRequirements() {
        return standardRequirements;
    }

    public String identity() {
        return identity;
    }

    public boolean isSatisfied(LivingEntityInventory inventory) {
        AllocationResult allocation = allocate(inventory);
        return !allocation.customUnsatisfied() && allocation.missingStandard().isEmpty();
    }

    public List<Requirement> missingStandardRequirements(LivingEntityInventory inventory) {
        return allocate(inventory).missingStandard();
    }

    public boolean hasUnsatisfiedCustom(LivingEntityInventory inventory) {
        return allocate(inventory).customUnsatisfied();
    }

    /**
     * Selects the exact main-inventory slot for one frozen block requirement. Required tools are
     * ranked by live speed then durability. Tool-free clears prefer empty/non-damageable hand,
     * then a damageable non-hoe, and finally any slot so a full inventory is never terminal.
     */
    public static int bestSlot(
            LivingEntityInventory inventory,
            FarmBreakToolRequirement requirement,
            int requiredUses) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(requirement, "requirement");
        if (requiredUses < 1) {
            throw new IllegalArgumentException("requiredUses must be positive");
        }
        if (!requirement.requiresTool()) {
            return bestNoToolSlot(inventory);
        }

        int best = -1;
        float bestSpeed = Float.NEGATIVE_INFINITY;
        int bestRemaining = -1;
        for (int i = 0; i < inventory.main.size(); i++) {
            ItemStack stack = inventory.main.get(i);
            int remaining = FarmBreakToolRequirement.remainingUses(stack);
            if (remaining < requiredUses || !requirement.accepts(stack)) {
                continue;
            }
            float speed = stack.getDestroySpeed(requirement.expectedState());
            if (best < 0 || speed > bestSpeed
                    || (speed == bestSpeed && remaining > bestRemaining)) {
                best = i;
                bestSpeed = speed;
                bestRemaining = remaining;
            }
        }
        return best;
    }

    /**
     * Deterministically reserves live per-slot durability. Custom, inventory-only contracts are
     * allocated first; standard families then consume the residual capacity. This prevents one
     * multitool from independently appearing to satisfy several needs whose summed uses would
     * break it part-way through the manifest.
     */
    private AllocationResult allocate(LivingEntityInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        int[] remaining = new int[inventory.main.size()];
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            remaining[slot] = FarmBreakToolRequirement.remainingUses(
                    inventory.main.get(slot));
        }

        ArrayList<CustomNeed> pendingCustom = new ArrayList<>(customNeeds);
        boolean customUnsatisfied = false;
        while (!pendingCustom.isEmpty()) {
            int needIndex = mostConstrainedCustom(
                    pendingCustom, inventory, remaining);
            CustomNeed need = pendingCustom.remove(needIndex);
            int slot = bestCustomSlot(
                    need, pendingCustom, inventory, remaining);
            if (slot < 0) {
                customUnsatisfied = true;
            } else {
                remaining[slot] -= need.useCount();
            }
        }

        ArrayList<Requirement> pendingStandard = new ArrayList<>(standardRequirements);
        EnumSet<FarmBreakToolRequirement.Family> missingFamilies =
                EnumSet.noneOf(FarmBreakToolRequirement.Family.class);
        while (!pendingStandard.isEmpty()) {
            int requirementIndex = mostConstrainedStandard(
                    pendingStandard, inventory, remaining);
            Requirement requirement = pendingStandard.remove(requirementIndex);
            int slot = bestStandardSlot(
                    requirement, pendingStandard, inventory, remaining);
            if (slot < 0) {
                missingFamilies.add(requirement.family());
            } else {
                remaining[slot] -= requirement.useCount();
            }
        }

        ArrayList<Requirement> missing = new ArrayList<>();
        for (Requirement requirement : standardRequirements) {
            if (missingFamilies.contains(requirement.family())) {
                missing.add(requirement);
            }
        }
        return new AllocationResult(customUnsatisfied, missing);
    }

    private int mostConstrainedCustom(
            List<CustomNeed> pending,
            LivingEntityInventory inventory,
            int[] remaining) {
        int selected = 0;
        int selectedCandidates = Integer.MAX_VALUE;
        for (int index = 0; index < pending.size(); index++) {
            CustomNeed need = pending.get(index);
            int candidates = customCandidateCount(need, inventory, remaining);
            CustomNeed previous = pending.get(selected);
            if (candidates < selectedCandidates
                    || (candidates == selectedCandidates
                    && need.useCount() > previous.useCount())
                    || (candidates == selectedCandidates
                    && need.useCount() == previous.useCount()
                    && stateIdentity(need.state()).compareTo(
                    stateIdentity(previous.state())) < 0)) {
                selected = index;
                selectedCandidates = candidates;
            }
        }
        return selected;
    }

    private int customCandidateCount(
            CustomNeed need,
            LivingEntityInventory inventory,
            int[] remaining) {
        int count = 0;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (remaining[slot] >= need.useCount()
                    && accepts(need.requirement(), inventory.main.get(slot))) {
                count++;
            }
        }
        return count;
    }

    private int bestCustomSlot(
            CustomNeed need,
            List<CustomNeed> otherCustom,
            LivingEntityInventory inventory,
            int[] remaining) {
        int best = -1;
        int bestCustomFlexibility = Integer.MAX_VALUE;
        int bestStandardFlexibility = Integer.MAX_VALUE;
        int bestResidual = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (remaining[slot] < need.useCount()
                    || !accepts(need.requirement(), inventory.main.get(slot))) {
                continue;
            }
            int residual = remaining[slot] - need.useCount();
            int customFlexibility = 0;
            for (CustomNeed other : otherCustom) {
                if (residual >= other.useCount()
                        && accepts(other.requirement(), inventory.main.get(slot))) {
                    customFlexibility++;
                }
            }
            int standardFlexibility = 0;
            for (Requirement standard : standardRequirements) {
                if (residual >= standard.useCount()
                        && acceptsEveryBlock(standard, inventory.main.get(slot))) {
                    standardFlexibility++;
                }
            }
            if (best < 0
                    || customFlexibility < bestCustomFlexibility
                    || (customFlexibility == bestCustomFlexibility
                    && standardFlexibility < bestStandardFlexibility)
                    || (customFlexibility == bestCustomFlexibility
                    && standardFlexibility == bestStandardFlexibility
                    && residual < bestResidual)) {
                best = slot;
                bestCustomFlexibility = customFlexibility;
                bestStandardFlexibility = standardFlexibility;
                bestResidual = residual;
            }
        }
        return best;
    }

    private int mostConstrainedStandard(
            List<Requirement> pending,
            LivingEntityInventory inventory,
            int[] remaining) {
        int selected = 0;
        int selectedCandidates = Integer.MAX_VALUE;
        for (int index = 0; index < pending.size(); index++) {
            Requirement requirement = pending.get(index);
            int candidates = standardCandidateCount(requirement, inventory, remaining);
            Requirement previous = pending.get(selected);
            if (candidates < selectedCandidates
                    || (candidates == selectedCandidates
                    && requirement.useCount() > previous.useCount())
                    || (candidates == selectedCandidates
                    && requirement.useCount() == previous.useCount()
                    && requirement.family().ordinal() < previous.family().ordinal())) {
                selected = index;
                selectedCandidates = candidates;
            }
        }
        return selected;
    }

    private int standardCandidateCount(
            Requirement requirement,
            LivingEntityInventory inventory,
            int[] remaining) {
        int count = 0;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (remaining[slot] >= requirement.useCount()
                    && acceptsEveryBlock(requirement, inventory.main.get(slot))) {
                count++;
            }
        }
        return count;
    }

    private int bestStandardSlot(
            Requirement requirement,
            List<Requirement> otherStandard,
            LivingEntityInventory inventory,
            int[] remaining) {
        int best = -1;
        int bestFlexibility = Integer.MAX_VALUE;
        int bestResidual = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (remaining[slot] < requirement.useCount()
                    || !acceptsEveryBlock(requirement, stack)) {
                continue;
            }
            int residual = remaining[slot] - requirement.useCount();
            int flexibility = 0;
            for (Requirement other : otherStandard) {
                if (residual >= other.useCount()
                        && acceptsEveryBlock(other, stack)) {
                    flexibility++;
                }
            }
            if (best < 0
                    || flexibility < bestFlexibility
                    || (flexibility == bestFlexibility && residual < bestResidual)) {
                best = slot;
                bestFlexibility = flexibility;
                bestResidual = residual;
            }
        }
        return best;
    }

    private static boolean acceptsEveryBlock(Requirement requirement, ItemStack stack) {
        for (FarmBreakToolRequirement block : requirement.blockRequirements()) {
            if (!accepts(block, stack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean accepts(
            FarmBreakToolRequirement requirement,
            ItemStack stack) {
        try {
            return stack != null && requirement.accepts(stack);
        } catch (RuntimeException incompatibleModItem) {
            return false;
        }
    }

    private static int bestNoToolSlot(LivingEntityInventory inventory) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            int rank;
            try {
                if (stack != null && (stack.isEmpty() || !stack.isDamageableItem())) {
                    rank = 0;
                } else if (stack != null && !(stack.getItem() instanceof HoeItem)) {
                    rank = 1;
                } else {
                    rank = 2;
                }
            } catch (RuntimeException invalidModItem) {
                rank = 2;
            }
            if (best < 0 || rank < bestRank
                    || (rank == bestRank && slot == inventory.selectedSlot)) {
                best = slot;
                bestRank = rank;
            }
        }
        return best;
    }

    private static String buildIdentity(
            List<Requirement> standard,
            List<CustomNeed> custom) {
        StringBuilder result = new StringBuilder("repair-tools-v1");
        for (Requirement requirement : standard) {
            result.append('|')
                    .append(requirement.family().name())
                    .append(':')
                    .append(requirement.minimumTier().name())
                    .append(':')
                    .append(requirement.useCount());
            ArrayList<String> states = new ArrayList<>();
            for (FarmBreakToolRequirement block : requirement.blockRequirements()) {
                states.add(stateIdentity(block.expectedState()));
            }
            states.sort(Comparator.naturalOrder());
            for (String state : states) {
                result.append(':').append(state);
            }
        }
        for (CustomNeed need : custom) {
            result.append("|CUSTOM:")
                    .append(need.useCount())
                    .append(':')
                    .append(stateIdentity(need.state()));
        }
        return "repair-tools-v1:"
                + UUID.nameUUIDFromBytes(
                result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String stateIdentity(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "@" + state;
    }
}
