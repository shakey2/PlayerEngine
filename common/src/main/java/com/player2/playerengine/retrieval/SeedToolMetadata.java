package com.player2.playerengine.retrieval;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Phase B2 — complete hand-authored {@link ToolDocument} records for every command
 * registered in {@code PlayerEngineCommands.init()}.
 *
 * <p>B1 authored 15 representative entries as a spike. B2 fills out all 29 registered
 * commands and patches keyword gaps identified in the B1 spike report:
 * <ul>
 *   <li>Singular/plural forms added (e.g. "logs", "trees", "mobs").
 *   <li>Individual mob-type keywords added to {@code attack}.
 *   <li>{@code explore} {@code whenToUse} tightened to reduce false-positive recall.
 * </ul>
 *
 * <p>Commands intentionally excluded (not registered in {@code PlayerEngineCommands}):
 * {@code inventory}, {@code list}, {@code pause}, {@code unpause}, {@code status},
 * and dev-only stubs ({@code CycleTestCommand}, {@code DummyTaskCommand}).
 *
 * <p>The {@code keywords} field on each entry is the primary mechanism for
 * paraphrase/synonym coverage at retrieval time.
 *
 * <p>Command IDs match the strings passed to {@code super(name, ...)} in each Command
 * subclass.
 */
public final class SeedToolMetadata {

    private SeedToolMetadata() {}

    /** Returns all seed documents as an immutable list. */
    public static Collection<ToolDocument> all() {
        return List.of(

            // ------------------------------------------------------------------
            // B1 entries — revised with B2 gap-fills
            // ------------------------------------------------------------------

            doc("get",
                "get",
                "Gather resources or craft an item. Handles mining, chopping, mob drops, and crafting automatically.",
                "Use when the NPC needs a specific item or resource in its inventory. Works for raw materials (logs, stone, ores) and crafted items (tools, armor, food). Provide item ID and optional quantity.",
                list("get log 20", "get diamond_chestplate 1", "get iron_ingot 64", "get wooden_pickaxe 1"),
                list("gather", "collect", "acquire", "obtain", "fetch", "mine", "chop", "harvest",
                     "craft", "make", "create", "build item", "resource", "material", "wood", "stone",
                     "ore", "log", "logs", "trees", "tree", "lumber", "cut trees", "chop trees",
                     "cut wood", "item", "pickaxe", "sword", "tool", "ingredient",
                     "coal", "iron ore", "gravel", "sand", "dirt", "cobblestone", "planks", "sticks"),
                list("inventory", "crafting")),

            doc("goto",
                "goto",
                "Travel to a specific location by coordinates or dimension name.",
                "Use when the NPC needs to move to a specific position in the world. Accepts full XYZ coordinates, XZ only, Y only, or just a dimension name.",
                list("goto 100 64 200", "goto 0 nether", "goto 100 200 overworld", "goto 50 200"),
                list("go", "move", "travel", "walk", "navigate", "head to", "coordinates", "position",
                     "location", "teleport", "nether", "end", "overworld", "dimension", "x y z", "xz",
                     "go here", "come here", "head over", "walk over", "teleport to"),
                list("movement", "navigation")),

            doc("deposit",
                "deposit",
                "Directly store items into the nearest container in one shot, creating one if simple. Best for a quick put-away when a chest is, or can easily be, nearby.",
                "Use for a one-shot direct put-away of items from the NPC's inventory into the nearest chest, barrel, or shulker box. Pass no arguments to deposit all non-tool/armor/weapon items, or specify items and quantities. Unlike 'stash', deposit auto-finds the nearest container without a coordinate range. If it cannot finish (no reachable or creatable container, container full, unreachable, or it times out), it reports the failure and recommends retrying via 'agentic', which can gather materials, craft or place a chest, and search farther.",
                list("deposit", "deposit diamond 5", "deposit iron_ingot 32", "deposit log 20"),
                list("store", "put", "chest", "container", "save items", "drop off",
                     "storage", "barrel", "shulker", "put away", "store items", "inventory transfer",
                     "put in chest", "put everything away", "drop off items", "offload", "unload",
                     "direct store", "quick store", "one shot store"),
                list("inventory", "storage")),

            doc("attack",
                "attack",
                "Attack a nearby player or mob by name or type.",
                "Use when the NPC should engage in combat with a specific named target. Provide the player username or mob type. Optionally specify a kill count. Use 'hero' instead to target ALL hostile mobs indiscriminately.",
                list("attack zombie", "attack Creeper", "attack Steve", "attack skeleton 5"),
                list("fight", "kill", "hit", "engage", "combat", "slay", "defeat", "damage",
                     "mob", "mobs", "hostile", "hostiles", "enemy", "enemies", "monster", "monsters",
                     "player", "aggressive", "PvP",
                     "zombie", "zombies", "creeper", "creepers", "skeleton", "skeletons",
                     "spider", "spiders", "enderman", "endermen", "blaze", "blazes",
                     "witch", "pillager", "pillagers", "drowned", "phantom", "phantoms"),
                list("combat", "attack")),

            doc("follow",
                "follow",
                "Follow the owner or a specified player continuously.",
                "Use when the NPC should shadow or accompany a player. The NPC continuously paths to stay close. Provide a username or leave blank to follow the owner.",
                list("follow", "follow Steve", "follow PlayerName"),
                list("accompany", "shadow", "escort", "trail", "stick with", "walk with", "come with",
                     "stay close", "partner", "tag along", "pursue", "owner", "player", "nearby",
                     "come with me", "walk behind me", "don't leave", "bodyguard", "protect me"),
                list("movement", "social")),

            doc("scan",
                "scan",
                "Scan the nearby area and locate the nearest instance of a specified block type.",
                "Use to give the NPC situational awareness or to find a specific block. Returns the position of the nearest matching block.",
                list("scan CHEST", "scan DIRT", "scan OAK_LOG"),
                list("look", "find", "search", "locate", "detect", "observe", "check nearby",
                     "nearest", "closest", "block", "blocks", "area", "surroundings", "environment",
                     "survey", "where is", "find block", "look for block", "search for block"),
                list("observation", "scanning")),

            doc("eat_food",
                "eat_food",
                "Eat a food item from the NPC's inventory to restore hunger.",
                "Use when the NPC is hungry (hunger bar below 20) and needs to restore saturation. The NPC picks the best available food from its inventory. Use 'food' to first collect food and 'eat_food' to consume it.",
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
                "Use when the NPC should wander to discover new terrain, structures, or resources nearby. This is undirected wandering — use 'goto' for a specific position, 'scan' to find a specific block, or 'locate_structure' for world-generated structures.",
                list("explore"),
                list("wander", "roam", "adventure", "scout", "discover",
                     "look around", "find new area", "patrol", "investigate"),
                list("movement", "navigation", "observation")),

            doc("pickup_drops",
                "pickup_drops",
                "Pick up item drops on the ground within a bounded nearby radius.",
                "Use for a simple direct pickup of nearby floor items. Prefer agentic for broader multi-step gather goals.",
                list("pickup_drops"),
                list("collect drops", "loot", "grab items", "ground items", "item drops",
                     "pick up", "looting", "nearby drops", "floor items", "dropped items"),
                list("inventory")),

            doc("agentic",
                "agentic",
                "Plan and run multi-step storage goals: gather loose drops, find OR craft/place a chest even when none is nearby, deposit, and optionally label the chest with a sign.",
                "Use for multi-step gather-and-store goals, or when a direct 'deposit' could not finish. It can collect nearby drops first, find or craft/place one chest (even when none exists nearby), deposit the selected (or all non-tool) items, and best-effort place a labeled sign. This is the recommended escalation when a one-shot 'deposit' reports it could not complete. Prefer pickup_drops for simple floor pickup only.",
                list("agentic store my cobblestone", "agentic pick up nearby drops",
                     "agentic find a chest nearby",
                     "agentic collect the drops and prepare a chest",
                     "agentic deposit my items and label the chest"),
                list("task", "plan", "multi step", "gather and store", "prepare storage", "find chest",
                     "place chest", "craft chest", "collect nearby drops", "gather drops", "organize",
                     "label chest", "sign the chest", "store farther", "make a chest"),
                list("inventory", "planning")),

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
                list("observation", "scanning", "utility")),

            // ------------------------------------------------------------------
            // B2 new entries — all remaining registered commands
            // ------------------------------------------------------------------

            doc("idle",
                "idle",
                "Stand in place and do nothing. The NPC stops all movement and active tasks.",
                "Use when the owner wants the NPC to pause and wait without stopping the task runner permanently. Unlike 'stop', idle does not shut down the task runner — it keeps the NPC stationary until a new task is given.",
                list("idle"),
                list("stand still", "wait", "do nothing", "stay", "hold position", "halt",
                     "stop moving", "be still", "pause", "rest", "freeze", "stand by"),
                list("utility")),

            doc("stop",
                "stop",
                "Stop all running automation and the task runner entirely. Also stops the idle task until a new task is started.",
                "Use when the owner wants to fully halt the NPC and clear its current task. Unlike 'idle', stop shuts down the task runner so the NPC will not resume any activity until explicitly given a new command.",
                list("stop"),
                list("cancel", "abort", "halt", "end task", "stop everything", "quit",
                     "cease", "terminate", "kill task", "shut down", "do not continue",
                     "stop now", "drop what you are doing"),
                list("utility")),

            doc("hero",
                "hero",
                "Automatically find and kill all nearby hostile mobs in the area.",
                "Use when the owner wants the NPC to clear ALL hostile mobs from the surrounding area without specifying a single target. Unlike 'attack' which targets one named mob or player, 'hero' sweeps the entire area.",
                list("hero"),
                list("clear mobs", "kill all mobs", "protect area", "defend", "clear hostiles",
                     "kill everything nearby", "mob clear", "sweep area", "hostile clear",
                     "exterminate", "purge mobs", "kill all", "clean up mobs",
                     "mob sweep", "area clear", "combat patrol"),
                list("combat", "attack")),

            doc("locate_structure",
                "locate_structure",
                "Locate and travel to a world-generated structure. Supports stronghold and desert temple.",
                "Use when the owner asks the NPC to find or travel to a specific vanilla world structure such as a stronghold (End portal room) or a desert temple. The NPC will navigate to the nearest matching structure.",
                list("locate_structure stronghold", "locate_structure desert_temple"),
                list("find structure", "stronghold", "end portal", "portal", "eye of ender",
                     "desert temple", "temple", "go to stronghold",
                     "navigate to stronghold", "find desert temple", "structure",
                     "biome structure", "vanilla structure"),
                list("movement", "navigation", "observation")),

            doc("build_structure",
                "build_structure",
                "Build a structure at specified coordinates using a schematic search query and description. The NPC does not need to collect materials beforehand.",
                "Use when the owner asks the NPC to build or construct something at a specific location. Provide XYZ coordinates for the build center-bottom, a short schematic search query, and a longer description. If no coordinates are specified, use the owner's or NPC's current position.",
                list(
                    "build_structure -305 406 72 \"gray modern house\" \"a gray modern house with a rose garden\"",
                    "build_structure 100 64 200 \"small barn\" \"a wooden barn for storing hay\""
                ),
                list("build", "construct", "create structure", "make building", "place building",
                     "erect", "architect", "schematic", "house", "base", "shelter", "cabin",
                     "tower", "wall", "build house", "build base", "build something",
                     "put up", "place structure"),
                list("building", "placement")),

            doc("bodylang",
                "bodylang",
                "Perform a body language or emote animation. Supported actions: greeting, nod_head, shake_head, victory.",
                "Use when the owner or social context calls for a non-verbal physical reaction — greeting an arrival, nodding agreement, shaking head in disagreement, or celebrating a victory. Do NOT use for movement or task commands.",
                list("bodylang greeting", "bodylang nod_head", "bodylang shake_head", "bodylang victory"),
                list("emote", "animation", "gesture", "wave", "nod", "shake head", "celebrate",
                     "victory dance", "greet", "hello gesture", "non-verbal", "body motion",
                     "react", "cheer", "disagree", "agree"),
                list("social", "utility")),

            doc("food",
                "food",
                "Collect a target number of food units from any available source — farming, hunting, or crafting.",
                "Use when the owner wants the NPC to stock up on food of any type. Provide the desired food unit count. The NPC accounts for food already in its inventory. Use 'meat' if the owner specifically wants meat-based food, or 'eat_food' to consume food already in inventory.",
                list("food 10", "food 20"),
                list("get food", "collect food", "gather food", "stock food", "food supply",
                     "provisioning", "hunger", "feed", "prepare food", "rations",
                     "supplies", "cook", "bread", "wheat", "berries", "vegetables", "sustenance"),
                list("inventory", "utility")),

            doc("meat",
                "meat",
                "Collect a target number of food units specifically from meat sources by hunting animals.",
                "Use when the owner explicitly wants meat-based food such as beef, pork, chicken, or mutton. Unlike 'food', this command hunts animals specifically rather than accepting any food source.",
                list("meat 10", "meat 20"),
                list("hunt", "kill animals", "get meat", "collect meat", "beef", "pork",
                     "chicken", "mutton", "animal food", "hunting", "butcher",
                     "raw beef", "cooked meat", "steak", "porkchop", "protein"),
                list("inventory", "combat", "utility")),

            doc("gamer",
                "gamer",
                "Execute the full sequence to beat Minecraft — gather resources, find and activate the End portal, and defeat the Ender Dragon.",
                "Use only when the owner explicitly wants the NPC to attempt to beat the game autonomously from start to finish. This is a very long-running, multi-stage task that controls the NPC for an extended period.",
                list("gamer"),
                list("beat the game", "speedrun", "finish game", "complete minecraft",
                     "ender dragon", "kill dragon", "end game", "beat minecraft",
                     "win", "credits", "beat it", "complete the game", "finish",
                     "stronghold run", "end portal run"),
                list("utility", "movement", "combat")),

            doc("leaveboat",
                "leaveboat",
                "Dismount from a boat or any other vehicle the NPC is currently riding.",
                "Use when the NPC is a passenger in a boat, minecart, or other vehicle and needs to get out. Safe to call even if not currently riding — it completes immediately with no effect.",
                list("leaveboat"),
                list("get off boat", "exit boat", "dismount", "get out of boat", "disembark",
                     "stop riding", "leave vehicle", "exit vehicle", "exit minecart",
                     "get off", "unboard", "off boat"),
                list("movement", "utility")),

            doc("reload_settings",
                "reload_settings",
                "Reload the bot's configuration files and the butler allow/block lists without restarting.",
                "Use when configuration files have been updated on disk and need to take effect immediately. This is a management command — only use when the owner explicitly requests a settings reload. The agent should NOT call this autonomously.",
                list("reload_settings"),
                list("reload config", "refresh settings", "update settings", "reload whitelist",
                     "reload blacklist", "apply config", "load config", "config reload",
                     "butler list reload", "settings update"),
                list("utility")),

            doc("resetmemory",
                "resetmemory",
                "Clear the NPC's conversation history and memory without stopping the current task.",
                "Use when the owner wants to wipe the NPC's chat context for a fresh conversation without halting active automation. The agent must NOT call this on itself — this is a user-initiated command only.",
                list("resetmemory"),
                list("clear memory", "forget", "wipe memory", "reset context", "fresh start",
                     "clear chat", "clear history", "reset conversation", "amnesia",
                     "start over", "clear conversation", "forget everything"),
                list("utility")),

            doc("chatclef",
                "chatclef",
                "Enable or disable the AI chat bridge (chatclef). When disabled, the NPC does not intercept or respond to player messages.",
                "Use only when the owner explicitly toggles the AI bridge on or off. The agent must NOT call this autonomously — it is a user-managed control toggle.",
                list("chatclef ON", "chatclef OFF"),
                list("ai bridge", "enable ai", "disable ai", "toggle chat", "turn off ai",
                     "turn on ai", "mute npc", "unmute npc", "silence", "chat toggle",
                     "ai on", "ai off", "chat intercept", "disable chat"),
                list("utility")),

            doc("set_attack_hostiles",
                "set_attack_hostiles",
                "Enable or disable automatic hostile mob attack behavior. When off, the NPC will not attack hostile mobs automatically.",
                "Use when the owner explicitly wants to change whether the NPC automatically engages nearby hostile mobs. Turning this OFF is appropriate in contexts where combat is unwanted. The agent should only call this when the owner explicitly asks.",
                list("set_attack_hostiles ON", "set_attack_hostiles OFF"),
                list("auto attack", "attack toggle", "stop attacking", "disable attack",
                     "enable attack", "hostile attack", "peaceful mode", "passive mode",
                     "combat off", "combat on", "don't attack", "no attacking",
                     "aggressive mode", "attack mobs automatically"),
                list("combat", "utility")),

            doc("stash",
                "stash",
                "Store items in a chest or container within a specified coordinate bounding box. Deposits all non-equipped items if no item list is given.",
                "Use when the owner wants items deposited into a specific coordinate-bounded storage region rather than the nearest container. Unlike 'deposit' which automatically finds the nearest chest, 'stash' targets a precise XYZ range. Provide start XYZ and end XYZ coordinates, then optionally a list of specific items and quantities.",
                list(
                    "stash 100 64 200 110 66 210",
                    "stash 100 64 200 110 66 210 iron_ingot 32 diamond 5"
                ),
                list("store in range", "deposit range", "put in storage", "coordinate chest",
                     "specific chest", "targeted storage", "region storage", "stash items",
                     "store here", "drop off at", "storage region", "bounded chest"),
                list("inventory", "storage"))

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
