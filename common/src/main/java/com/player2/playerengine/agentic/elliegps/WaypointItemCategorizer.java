package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.containeraccess.ItemCount;
import com.player2.playerengine.multiversion.item.ItemVer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic item-to-keyword categorizer for EllieGPS inventory waypoints (Part C5, WS4).
 *
 * <p>For each aggregate {@link ItemCount}, emits:
 * <ol>
 *   <li>Category tokens from class-hierarchy checks ({@link PickaxeItem}, {@link SwordItem},
 *       {@link ArmorItem}, etc.) and registry-id pattern fallbacks ({@code _ore}, {@code _ingot},
 *       {@code raw_}, etc.).</li>
 *   <li>Item-name tokens from the registry path (e.g. {@code iron_ingot} → {@code iron ingot},
 *       {@code iron}, {@code ingot}) plus naive plural variants ({@code +"s"}).</li>
 * </ol>
 *
 * <p>Food checks route through {@link ItemVer#isFood(Item)} so this file stays byte-identical
 * across branches (the 1.20.1/1.21.1 divergence is quarantined there).
 *
 * <p>Unresolvable registry ids (modded items missing from the registry at runtime) contribute
 * name tokens only — degrades, never fails.
 *
 * <p>The keyword set is always deterministic. The LLM contributes description text only
 * (Decision 6). Keywords drive retrieval and the counting term where the workspace's
 * deterministic-beats-model rule applies.
 */
public final class WaypointItemCategorizer {

    private WaypointItemCategorizer() {}

    // -------------------------------------------------------------------------
    // Category token constants
    // -------------------------------------------------------------------------

    private static final String CAT_TOOLS         = "tools";
    private static final String CAT_WEAPONS        = "weapons";
    private static final String CAT_ARMOR          = "armor";
    private static final String CAT_FOOD           = "food";
    private static final String CAT_BUILDING       = "building blocks";
    private static final String CAT_ORES           = "ores";
    private static final String CAT_INGOTS_METALS  = "ingots";
    private static final String CAT_METALS         = "metals";
    private static final String CAT_REDSTONE       = "redstone";
    private static final String CAT_FUEL           = "fuel";
    private static final String CAT_VALUABLES      = "valuables";
    private static final String CAT_CROPS_SEEDS    = "crops";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Derives a de-duplicated ordered keyword list from the given aggregate item counts.
     *
     * <p>Category tokens come first, then item-name tokens. Within each section, order follows
     * first-seen insertion (deterministic across a given snapshot because
     * {@link ContainerSnapshot#aggregate()} is sorted ascending by registryId).
     *
     * @param aggregate sorted aggregate from {@link com.player2.playerengine.containeraccess.ContainerSnapshot}
     * @return immutable ordered keyword list; never null
     */
    public static List<String> categorize(List<ItemCount> aggregate) {
        // Two passes: categories first (in a SequencedSet to preserve insertion order and de-dup),
        // then name tokens. Both accumulated in a single linked set to give categories-first order.
        LinkedHashSet<String> result = new LinkedHashSet<>();
        LinkedHashSet<String> nameTokens = new LinkedHashSet<>();

        for (ItemCount ic : aggregate) {
            if (ic == null || ic.registryId() == null) continue;
            String registryId = ic.registryId();
            Item item = resolveItem(registryId);

            // --- Category tokens ---
            addCategoryTokens(registryId, item, result);

            // --- Name tokens ---
            addNameTokens(registryId, nameTokens);
        }

        // Append name tokens after categories (de-dup across both sets)
        for (String tok : nameTokens) {
            result.add(tok);
        }

        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------
    // Internal: category derivation
    // -------------------------------------------------------------------------

    /**
     * Adds zero or more category tokens to {@code out} for the given item.
     *
     * <p>Checks are ordered from most specific to least specific; multiple categories may apply
     * (e.g. a diamond sword is both "weapons" and "valuables").
     */
    private static void addCategoryTokens(String registryId, Item item, LinkedHashSet<String> out) {
        String path = pathOf(registryId);

        if (item != null) {
            addClassBasedCategories(registryId, path, item, out);
        } else {
            // Registry miss — fall back to id-pattern heuristics only
            addPatternBasedCategories(path, out);
        }
    }

    private static void addClassBasedCategories(String registryId, String path, Item item,
                                                LinkedHashSet<String> out) {
        // Tools — DiggerItem covers picks/axes/shovels/hoes
        if (item instanceof PickaxeItem || item instanceof AxeItem
                || item instanceof ShovelItem || item instanceof HoeItem
                || item instanceof DiggerItem) {
            out.add(CAT_TOOLS);
        }

        // Weapons
        if (item instanceof SwordItem || item instanceof TridentItem) {
            out.add(CAT_WEAPONS);
        }

        // Armor
        if (item instanceof ArmorItem) {
            out.add(CAT_ARMOR);
        }

        // Food — routed through ItemVer so this file stays byte-identical across branches
        if (ItemVer.isFood(item)) {
            out.add(CAT_FOOD);
        }

        // Building blocks — BlockItem covers all placeable block items
        if (item instanceof BlockItem) {
            out.add(CAT_BUILDING);
        }

        // Pattern-based categories always run after class checks for items that resolve
        addPatternBasedCategories(path, out);

        // Valuables: diamonds, netherite, gold-block, etc.
        addValuablesCheck(path, out);

        // Redstone
        addRedstoneCheck(path, out);

        // Fuel
        addFuelCheck(item, path, out);

        // Crops / seeds
        addCropsSeedsCheck(path, out);
    }

    /**
     * Heuristic category assignment from registry path alone (used for unresolvable ids and as a
     * supplement to class-hierarchy checks for items that do resolve).
     */
    private static void addPatternBasedCategories(String path, LinkedHashSet<String> out) {
        // Ores: ends with _ore or contains "_ore_"
        if (path.endsWith("_ore") || path.contains("_ore_") || path.equals("ancient_debris")) {
            out.add(CAT_ORES);
        }

        // Raw metals: raw_iron, raw_gold, raw_copper, raw_<anything>
        if (path.startsWith("raw_")) {
            out.add(CAT_ORES);
            out.add(CAT_INGOTS_METALS);
            out.add(CAT_METALS);
        }

        // Ingots: iron_ingot, gold_ingot, copper_ingot, netherite_ingot, etc.
        if (path.endsWith("_ingot")) {
            out.add(CAT_INGOTS_METALS);
            out.add(CAT_METALS);
        }

        // Nuggets (gold_nugget, iron_nugget)
        if (path.endsWith("_nugget")) {
            out.add(CAT_METALS);
        }

        // Gems — diamond, emerald, amethyst, quartz, etc.
        if (path.equals("diamond") || path.equals("emerald") || path.equals("amethyst_shard")
                || path.equals("quartz") || path.equals("lapis_lazuli") || path.equals("prismarine_crystals")) {
            out.add(CAT_VALUABLES);
            out.add(CAT_INGOTS_METALS); // treat gems as crafting-ingredient metals category too
        }

        // Blocks of metal/gem (block of iron, block of gold, diamond block, etc.)
        if (path.startsWith("block_of_") || (path.startsWith("block") && path.contains("_"))
                || path.endsWith("_block")) {
            // Only add metals/valuables for metal/gem blocks
            if (path.contains("iron") || path.contains("gold") || path.contains("copper")
                    || path.contains("netherite") || path.contains("diamond")
                    || path.contains("emerald") || path.contains("lapis") || path.contains("coal")
                    || path.contains("redstone") || path.contains("quartz")) {
                out.add(CAT_METALS);
            }
        }
    }

    private static void addValuablesCheck(String path, LinkedHashSet<String> out) {
        if (path.contains("diamond") || path.contains("netherite") || path.contains("emerald")
                || path.contains("gold") || path.contains("ancient_debris")) {
            out.add(CAT_VALUABLES);
        }
    }

    private static void addRedstoneCheck(String path, LinkedHashSet<String> out) {
        if (path.contains("redstone") || path.contains("piston") || path.contains("repeater")
                || path.contains("comparator") || path.contains("observer") || path.contains("dropper")
                || path.contains("dispenser") || path.contains("hopper") || path.contains("lever")
                || path.contains("daylight") || path.contains("tripwire")
                || path.contains("target_block") || path.contains("note_block")) {
            out.add(CAT_REDSTONE);
        }
    }

    private static void addFuelCheck(Item item, String path, LinkedHashSet<String> out) {
        if (path.contains("coal") || path.contains("charcoal") || path.contains("log")
                || path.contains("planks") || path.contains("stick") || path.contains("wood")
                || path.contains("blaze_rod")) {
            out.add(CAT_FUEL);
        }
    }

    private static void addCropsSeedsCheck(String path, LinkedHashSet<String> out) {
        if (path.endsWith("_seeds") || path.endsWith("_seed") || path.contains("wheat")
                || path.contains("potato") || path.contains("carrot") || path.contains("beetroot")
                || path.contains("melon") || path.contains("pumpkin") || path.contains("cocoa")
                || path.contains("nether_wart") || path.contains("sweet_berries")
                || path.contains("glow_berries") || path.contains("bamboo")) {
            out.add(CAT_CROPS_SEEDS);
        }
    }

    // -------------------------------------------------------------------------
    // Internal: name-token derivation
    // -------------------------------------------------------------------------

    /**
     * Adds item-name tokens from the registry path to {@code out}.
     *
     * <p>For {@code iron_ingot}: adds {@code "iron ingot"}, {@code "iron ingots"},
     * {@code "iron"}, {@code "ingot"}, {@code "ingots"}.
     */
    private static void addNameTokens(String registryId, LinkedHashSet<String> out) {
        String path = pathOf(registryId);
        // Full name with underscores → spaces
        String fullName = path.replace('_', ' ');
        out.add(fullName);
        out.add(fullName + "s"); // naive plural of the full phrase

        // Individual word tokens from the path parts
        String[] parts = path.split("_");
        for (String part : parts) {
            if (part.length() < 2) continue; // skip single-char parts (noise)
            out.add(part);
            out.add(part + "s"); // naive plural
        }
    }

    // -------------------------------------------------------------------------
    // Internal: helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves an item from its full registry id (e.g. {@code "minecraft:iron_ingot"}).
     * Returns {@code null} on any failure (modded item missing, invalid id) — degrade, don't fail.
     */
    private static Item resolveItem(String registryId) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(registryId);
            if (rl == null) return null;
            // BuiltInRegistries.ITEM.get() returns Items.AIR for unknown ids on 1.20.1
            Item item = BuiltInRegistries.ITEM.get(rl);
            // AIR means not found — return null so we fall back to pattern heuristics
            if (item == net.minecraft.world.item.Items.AIR
                    && !"minecraft:air".equals(registryId)) {
                return null;
            }
            return item;
        } catch (Exception e) {
            PlayerEngine.LOGGER.debug("WaypointItemCategorizer: could not resolve item '{}': {}", registryId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the path component of a registry id (everything after the first {@code ":"}).
     * Falls back to the whole string when there is no namespace.
     */
    private static String pathOf(String registryId) {
        int colon = registryId.indexOf(':');
        return (colon >= 0) ? registryId.substring(colon + 1).toLowerCase(Locale.ROOT)
                            : registryId.toLowerCase(Locale.ROOT);
    }
}
