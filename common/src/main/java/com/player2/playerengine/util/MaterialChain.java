package com.player2.playerengine.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ordered wood→stone→iron→diamond tier ladder used by the tool-acquisition pipeline.
 *
 * <p>Tier ordering is expressed purely via {@link MiningRequirement} enum-constant order
 * ({@code Comparable} via ordinal) — no {@code Tier.getLevel()}, {@code Tier.getTag()},
 * or {@code TierSortingRegistry} is referenced here, which would be 1.20.1-only API absent
 * in 1.21.1 NeoForge builds. NETHERITE is intentionally absent: {@link MiningRequirement}
 * has no NETHERITE constant and {@code getMinimumRequirementForBlock} never returns one
 * ("netherite is not required anywhere" — netherite pickaxes are accepted as satisfying a
 * DIAMOND requirement inside {@code StorageHelper}, but are never a requirement tier).
 *
 * <p>Each constant carries the {@link MiningRequirement} it represents and the
 * {@code TaskCatalogue} pickaxe name used to acquire it via
 * {@code TaskCatalogue.getItemTask(pickaxeCatalogueName, 1)}.
 */
public enum MaterialChain {

    /** Wooden pickaxe tier — satisfies {@link MiningRequirement#WOOD}. */
    WOOD(MiningRequirement.WOOD, "wooden_pickaxe"),

    /** Stone pickaxe tier — satisfies {@link MiningRequirement#STONE}. */
    STONE(MiningRequirement.STONE, "stone_pickaxe"),

    /** Iron pickaxe tier — satisfies {@link MiningRequirement#IRON}. */
    IRON(MiningRequirement.IRON, "iron_pickaxe"),

    /** Diamond pickaxe tier — satisfies {@link MiningRequirement#DIAMOND}. Upper ceiling. */
    DIAMOND(MiningRequirement.DIAMOND, "diamond_pickaxe");

    /** Immutable ordered list of all tiers from lowest to highest (wood→stone→iron→diamond). */
    public static final List<MaterialChain> ORDERED = Collections.unmodifiableList(Arrays.asList(values()));

    private final MiningRequirement requirement;
    private final String pickaxeCatalogueName;

    MaterialChain(MiningRequirement requirement, String pickaxeCatalogueName) {
        this.requirement = requirement;
        this.pickaxeCatalogueName = pickaxeCatalogueName;
    }

    /**
     * The {@link MiningRequirement} this tier satisfies.
     * Ordering follows the {@code MiningRequirement} ordinal (WOOD &lt; STONE &lt; IRON &lt; DIAMOND).
     */
    public MiningRequirement getRequirement() {
        return requirement;
    }

    /**
     * The {@code TaskCatalogue} name for the pickaxe at this tier.
     * Pass to {@code TaskCatalogue.getItemTask(pickaxeCatalogueName, 1)} to acquire.
     */
    public String getPickaxeCatalogueName() {
        return pickaxeCatalogueName;
    }

    /**
     * Returns the {@code MaterialChain} entry for the given {@link MiningRequirement}, or
     * {@code null} if the requirement maps to no chain tier (i.e. {@code HAND} — no pickaxe needed).
     */
    public static MaterialChain forRequirement(MiningRequirement req) {
        if (req == null || req == MiningRequirement.HAND) {
            return null;
        }
        for (MaterialChain tier : values()) {
            if (tier.requirement == req) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Returns the tier one step below this one in the chain, or {@code null} if this is
     * {@code WOOD} (the lowest craftable tier — no prerequisite pickaxe is needed to mine wood).
     */
    public MaterialChain prerequisite() {
        int idx = ordinal();
        return idx > 0 ? values()[idx - 1] : null;
    }
}
