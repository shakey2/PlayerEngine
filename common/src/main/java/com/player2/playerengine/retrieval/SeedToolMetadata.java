package com.player2.playerengine.retrieval;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Phase B1 seed set — hand-authored {@link ToolDocument} records for ~15 representative
 * commands from {@code PlayerEngineCommands}.
 *
 * <p>The {@code keywords} field on each entry provides synonym/paraphrase coverage at
 * authoring time. Full authoring of all registered commands is deferred to Phase B2.
 *
 * <p>Command IDs match the strings passed to {@code super(name, ...)} in each Command
 * subclass.
 */
public final class SeedToolMetadata {

    private SeedToolMetadata() {}

    /** Returns all seed documents as an immutable list. */
    public static Collection<ToolDocument> all() {
        return List.of(

            doc("get",
                "get",
                "Gather resources or craft an item. Handles mining, chopping, mob drops, and crafting automatically.",
                "Use when the NPC needs a specific item or resource in its inventory. Works for raw materials (logs, stone, ores) and crafted items (tools, armor, food). Provide item ID and optional quantity.",
                list("get log 20", "get diamond_chestplate 1", "get iron_ingot 64", "get wooden_pickaxe 1"),
                list("gather", "collect", "acquire", "obtain", "fetch", "mine", "chop", "harvest",
                     "craft", "make", "create", "build item", "resource", "material", "wood", "stone",
                     "ore", "log", "item", "pickaxe", "sword", "tool", "ingredient"),
                list("inventory", "crafting")),

            doc("goto",
                "goto",
                "Travel to a specific location by coordinates or dimension name.",
                "Use when the NPC needs to move to a specific position in the world. Accepts full XYZ coordinates, XZ only, Y only, or just a dimension name.",
                list("goto 100 64 200", "goto 0 nether", "goto 100 200 overworld", "goto 50 200"),
                list("go", "move", "travel", "walk", "navigate", "head to", "coordinates", "position",
                     "location", "teleport", "nether", "end", "overworld", "dimension", "x y z", "xz"),
                list("movement", "navigation")),

            doc("deposit",
                "deposit",
                "Store items in a nearby chest, barrel, or shulker box. Creates a chest if none exists within range.",
                "Use to move items from the NPC's inventory into a container. Pass no arguments to deposit all non-tool items, or specify items and quantities to deposit a subset.",
                list("deposit", "deposit diamond 5", "deposit iron_ingot 32", "deposit log 20"),
                list("store", "put", "chest", "container", "stash", "save items", "drop off",
                     "storage", "barrel", "shulker", "put away", "store items", "inventory transfer"),
                list("inventory", "storage")),

            doc("attack",
                "attack",
                "Attack a nearby player or mob by name or type.",
                "Use when the NPC should engage in combat with a specific target. Provide the player username or mob type. Optionally specify a kill count.",
                list("attack zombie", "attack Creeper", "attack Steve", "attack skeleton 5"),
                list("fight", "kill", "hit", "engage", "combat", "slay", "defeat", "damage",
                     "mob", "hostile", "enemy", "monster", "player", "aggressive", "PvP"),
                list("combat", "attack")),

            doc("follow",
                "follow",
                "Follow the owner or a specified player continuously.",
                "Use when the NPC should shadow or accompany a player. The NPC continuously paths to stay close. Provide a username or leave blank to follow the owner.",
                list("follow", "follow Steve", "follow PlayerName"),
                list("accompany", "shadow", "escort", "trail", "stick with", "walk with", "come with",
                     "stay close", "partner", "tag along", "pursue", "owner", "player", "nearby"),
                list("movement", "social")),

            doc("scan",
                "scan",
                "Scan the nearby area and locate the nearest instance of a specified block type.",
                "Use to give the NPC situational awareness or to find a specific block. Returns the position of the nearest matching block.",
                list("scan CHEST", "scan DIRT", "scan OAK_LOG"),
                list("look", "find", "search", "locate", "detect", "observe", "check nearby",
                     "nearest", "closest", "block", "area", "surroundings", "environment", "survey"),
                list("observation", "scanning")),

            doc("eat_food",
                "eat_food",
                "Eat a food item from the NPC's inventory to restore hunger.",
                "Use when the NPC is hungry (hunger bar below 20) and needs to restore saturation. The NPC picks the best available food from its inventory.",
                list("eat_food"),
                list("eat", "consume", "hungry", "hunger", "food", "health", "saturation",
                     "restore hunger", "feed", "meal", "snack", "nourish"),
                list("inventory", "utility")),

            doc("equip",
                "equip",
                "Equip an item from the NPC's inventory into its armor slots or active hand.",
                "Use to put on armor, hold a weapon, or wield a tool. Provide the item ID.",
                list("equip iron_sword", "equip diamond_helmet", "equip bow", "equip iron_chestplate"),
                list("wear", "hold", "arm", "armor", "weapon", "tool", "gear", "wield",
                     "put on", "slot", "hand", "sword", "helmet", "chestplate", "boots", "shield",
                     "leggings", "pickaxe"),
                list("inventory", "combat")),

            doc("give",
                "give",
                "Give items from the NPC's inventory to a specified nearby player.",
                "Use when the NPC should transfer items it is carrying to a particular player.",
                list("give iron_ingot 10 Steve", "give log 5 PlayerName", "give diamond 1 Owner"),
                list("hand over", "transfer", "share", "offer", "provide", "deliver", "trade",
                     "toss", "player", "owner", "pass", "inventory"),
                list("inventory", "social")),

            doc("fish",
                "fish",
                "Go fishing at a nearby body of water.",
                "Use when the NPC should find water, cast a fishing rod, and collect fish or treasure. Requires a fishing rod in inventory.",
                list("fish"),
                list("fishing", "catch", "rod", "water", "lake", "river", "pond", "angling",
                     "lure", "cast", "seafood", "ocean"),
                list("utility")),

            doc("farm",
                "farm",
                "Automate farming — plant, tend, and harvest crops within a specified radius.",
                "Use when the NPC should manage a crop farm in the area: it finds farmland, plants seeds, waits for maturity, and harvests ready crops.",
                list("farm 10", "farm 20"),
                list("farming", "harvest crops", "plant seeds", "agriculture", "wheat", "potato",
                     "carrot", "grow", "cultivate", "garden", "food production", "crops", "seeds"),
                list("utility", "inventory")),

            doc("explore",
                "explore",
                "Explore the surrounding area without a specific destination.",
                "Use when the NPC should wander to discover new terrain, structures, or resources nearby. Useful before a more targeted task.",
                list("explore"),
                list("wander", "roam", "adventure", "scout", "discover", "search area",
                     "look around", "find new area", "patrol", "survey area", "investigate"),
                list("movement", "navigation", "observation")),

            doc("pickup_drops",
                "pickup_drops",
                "Pick up all item drops on the ground nearby.",
                "Use when the NPC should collect dropped items from the ground rather than gathering or crafting them.",
                list("pickup_drops"),
                list("collect drops", "loot", "grab items", "ground items", "item drops",
                     "pick up", "looting", "nearby drops", "floor items", "dropped items"),
                list("inventory")),

            doc("place_sign",
                "place_sign",
                "Place a sign with specified text at a given location.",
                "Use to label a chest, room, or area with a sign. Provide the sign text and the target block coordinates.",
                list("place_sign \"Chest 1\" 100 64 200", "place_sign \"Storage Room\" 50 65 50"),
                list("sign", "label", "tag", "write", "mark", "note", "text on block",
                     "annotate", "name location", "identify", "signpost"),
                list("building", "placement", "utility")),

            doc("read_signs",
                "read_signs",
                "Read the text on all nearby signs and report their contents.",
                "Use to gather information from signs in the world — useful for finding labeled storage locations, reading instructions, or understanding area context.",
                list("read_signs"),
                list("read sign", "sign text", "information", "label", "note", "nearby signs",
                     "what does sign say", "sign content", "observe signs", "check signs"),
                list("observation", "scanning", "utility"))

        );
    }

    /**
     * Returns the baseline {@link ToolDocument} for {@code id}, or {@code null} if the id
     * is not in the seed set.
     *
     * <p>Used by the {@code /playerengine rag inspect} command to diff overlay-added keywords
     * and examples from the hand-authored baseline without tagging documents at construction time.
     */
    public static ToolDocument byId(String id) {
        if (id == null) return null;
        for (ToolDocument doc : all()) {
            if (doc.id().equals(id)) return doc;
        }
        return null;
    }

    private static ToolDocument doc(String id, String name, String description, String whenToUse,
                                    List<String> examples, List<String> keywords,
                                    List<String> categoryTags) {
        return new ToolDocument(id, name, description, whenToUse, examples, keywords, categoryTags);
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }
}
