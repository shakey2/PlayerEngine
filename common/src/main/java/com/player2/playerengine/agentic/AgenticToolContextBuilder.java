package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.SeedToolMetadata;
import com.player2.playerengine.retrieval.ToolDocument;
import com.player2.playerengine.retrieval.ToolRetriever;
import com.player2.playerengine.agentic.steps.ResolveStorageChestParams;
import com.player2.playerengine.agentic.storage.ChestPlacementSelector;
import com.player2.playerengine.agentic.storage.StorageChestScanner;
import com.player2.playerengine.tasks.crafting.CraftMacroSupport;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Owner-scoped RAG tool cards for planner prompts (excludes {@code agentic}). */
public final class AgenticToolContextBuilder {

    private static final Logger LOGGER = LogManager.getLogger(AgenticToolContextBuilder.class);

    private AgenticToolContextBuilder() {}

    public record AgenticToolContext(
            List<AgenticToolCard> tools,
            AgenticWorldSnapshot world,
            List<String> retrievedToolIds
    ) {}

    public static AgenticToolContext build(
            PlayerEngineController mod,
            String goalText,
            PlayerEngineSettings settings) {
        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        int topK = settings.getAgenticPlannerRagTopK();
        Map<String, ToolDocument> seedById = SeedToolMetadata.all().stream()
                .collect(Collectors.toMap(ToolDocument::id, d -> d, (a, b) -> a, LinkedHashMap::new));

        List<RetrievalHit> hits = List.of();
        ToolRetriever retriever = server != null ? RagIndex.getForOwner(server, ownerUuid) : null;
        if (retriever != null && goalText != null && !goalText.isBlank()) {
            try {
                hits = retriever.retrieve(goalText, topK);
            } catch (Exception e) {
                LOGGER.warn("[Agentic] RAG retrieve failed: {}", e.getMessage());
            }
        }

        List<AgenticToolCard> cards = new ArrayList<>();
        List<String> toolIds = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            if (hit == null || hit.toolId() == null) {
                continue;
            }
            String id = hit.toolId().trim();
            if (AgenticSchemas.PLANNER_COMMAND_ID.equals(id)) {
                continue;
            }
            ToolDocument doc = seedById.get(id);
            if (doc == null) {
                continue;
            }
            toolIds.add(id);
            cards.add(toCard(doc, hit.score()));
        }

        if (cards.isEmpty()) {
            ToolDocument pickup = seedById.get("pickup_drops");
            if (pickup != null) {
                cards.add(toCard(pickup, 0.0));
                toolIds.add("pickup_drops");
            }
        }

        AgenticWorldSnapshot world = buildWorldSnapshot(mod, settings);
        LOGGER.info("[Agentic] planner tool context: ids={}", toolIds);
        return new AgenticToolContext(List.copyOf(cards), world, List.copyOf(toolIds));
    }

    public static String formatToolCards(List<AgenticToolCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "(no retrieved tools)";
        }
        StringBuilder sb = new StringBuilder();
        for (AgenticToolCard card : cards) {
            sb.append("- ").append(card.toolId()).append(": ").append(card.summary()).append('\n');
            sb.append("  when: ").append(card.whenToUse()).append('\n');
            if (!card.examples().isEmpty()) {
                sb.append("  examples: ").append(String.join("; ", card.examples())).append('\n');
            }
        }
        return sb.toString();
    }

    public static String allowedStepsSchema() {
        return """
                Allowed executable step kinds (schema v1, C3):
                - gather_loose_items: collect nearby item entities on the ground.
                  args: radius, maxItems, maxStacks, settleSeconds, timeoutSeconds, freeInventoryIfFull, itemFilters
                - resolve_storage_chest: find an accessible nearby chest or place exactly one chest.
                  args: searchRadius, placementRadius, preferExisting, allowPlacement, avoidLootChests, timeoutSeconds
                - deposit_items: move items from the bot inventory into the chest resolved by resolve_storage_chest.
                  args: itemIds (comma/space separated, e.g. "minecraft:cobblestone"), depositAll, keepTools, timeoutSeconds
                  Requires a preceding resolve_storage_chest in the same plan.
                - label_chest: optional, best-effort. Place and write a sign on or near the labeled chest; crafts a sign first when none is held (if wood is obtainable).
                  args: lines (comma separated) OR line0..line3, autoLabel, signItemId, timeoutSeconds
                  Must be the LAST step and only directly after deposit_items. Never fails the run.
                - smelt_items: smelt, blast, or smoke a raw item into a processed output (e.g. raw_iron -> iron_ingot). Resolves furnace type and fuel automatically.
                  args: item (required, registry id of the INPUT item), count (optional, default 1)
                - smith_items: upgrade an item at a smithing table using the recipe registry. The bot gathers the required template, base, and addition items automatically and performs the upgrade. Use for netherite upgrades and any registered SmithingTransformRecipe. The item arg is the desired OUTPUT (e.g. netherite_pickaxe). Modded upgrades resolve automatically via the registry.
                  args: item (required, registry id of the OUTPUT item, e.g. "netherite_pickaxe" or "minecraft:netherite_chestplate"), count (optional, default 1)
                - mine_block: break the nearest matching block of a named type within a radius and collect its drops. The bot deterministically resolves the required pickaxe tier and acquires a sufficient pickaxe automatically (inventory, then marked/nearby chests, then crafting one up the chain) before digging — do NOT add a separate get/craft step for the tool. v1 mines ONLY the nearest matching block within the radius (no coordinate, area, or vein mining). The mined materials stay in the bot's inventory; mine_block NEVER stores or deposits them, so it does NOT imply a resolve_storage_chest/deposit_items step. Add storage steps only if the player explicitly asked to store the result. Never target minecraft:chest with mine_block; use resolve_storage_chest to obtain or place a chest.
                  args: blockId (required, registry id or tag token, e.g. "minecraft:iron_ore" or "cobblestone"), maxBlocks (optional, default 16), radius (optional), timeoutSeconds (optional)
                - setup_farm: create or resume one finite 9x9 irrigated farm plot through normal movement, water-bucket, block, and hoe interactions, then save its typed farm waypoint. Use as a standalone step only.
                  args: omit all to search compatible unfinished EllieGPS farms in range and resume the nearest whose complete repair envelope is loaded. If such farms are known but none has a fully loaded repair envelope, setup stops instead of creating a duplicate. Automatic safe-surface selection, prioritizing grass, occurs only when no compatible unfinished farm exists in range. For ordinary conversational requests such as 'make a farm here' without a precise block, use coordinate-less setup_farm; use an exact anchor only when the owner explicitly requests exact feet or coordinates. Alternatively, provide x, y, and z together as exact integer coordinates in the current dimension. Direct bot-command aliases (not JSON step args): setup_farm bot/setup_farm here selects the exact block beneath the NPC; setup_farm owner/setup_farm player selects the exact block beneath the owner. These aliases never use player or NPC gaze, and every exact anchor retains the hard safety gates
                - harvest_farm: perform one finite mature-only harvest pass over a recorded farm, gather newly dropped items best-effort, and refresh the typed farm waypoint. It never replants and must be a standalone step.
                  args: omit all for the nearest non-stale farm in the current dimension, or provide x, y, and z together as an exact farm center
                - plant_farm: plant ordered crop groups on one recorded 9x9 farm, acquiring each planting item from inventory, EllieGPS storage, wheat short/tall grass where applicable, then planted village crops before village chests, and refresh exact open-slot/crop metadata. It must be a standalone step. Use planting item ids (carrot/potato roots or seed items), not crop block ids. Horizontal melon/pumpkin-style stems are unsupported. If no farm exists, tell the player and ask whether they want one set up; do not add setup_farm automatically.
                  args: requests (required comma-separated canonical item=count list, e.g. "minecraft:carrot=23,minecraft:wheat_seeds=10"); optionally all of x, y, z for an exact farm; farm_policy defaults to preserve, while mixed or single:<canonical-item-id> may be used only with exact coordinates
                Allowed sequences only:
                  gather_loose_items
                  resolve_storage_chest
                  gather_loose_items then resolve_storage_chest
                  resolve_storage_chest then deposit_items
                  gather_loose_items then resolve_storage_chest then deposit_items
                  resolve_storage_chest then deposit_items then label_chest
                  gather_loose_items then resolve_storage_chest then deposit_items then label_chest
                  smelt_items
                  smith_items
                  mine_block
                  gather_loose_items then mine_block
                  mine_block then gather_loose_items
                  mine_block then resolve_storage_chest then deposit_items
                  mine_block then resolve_storage_chest then deposit_items then label_chest
                  setup_farm
                  harvest_farm
                  plant_farm
                Plans may have at most four steps. label_chest is optional and may be omitted.
                Bare "mine_block" is a complete plan (mined materials simply stay in inventory). Pair mine_block with gather_loose_items (either order) to also sweep up drops that auto-pickup missed. Use the mine_block-then-store sequences ONLY when storage was explicitly requested.
                Waypoints are managed automatically after deposits and via the create/delete/audit/compare/locate waypoint bot commands — never output waypoint plan steps (no register_waypoint or elliegps step kinds).
                """;
    }

    private static AgenticToolCard toCard(ToolDocument doc, double score) {
        return new AgenticToolCard(
                doc.id(),
                doc.description(),
                doc.whenToUse(),
                doc.examples(),
                doc.categoryTags(),
                score);
    }

    private static AgenticWorldSnapshot buildWorldSnapshot(PlayerEngineController mod, PlayerEngineSettings settings) {
        Vec3 pos = mod.getPlayer().position();
        String dimension = mod.getWorld().dimension().location().toString();
        double radius = settings.getGatherLooseItemsRadius();
        List<ItemEntity> drops = mod.getEntityTracker().getItemDropsWithin(pos, radius, e -> !e.isRemoved());
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < Math.min(5, drops.size()); i++) {
            ItemEntity entity = drops.get(i);
            if (!entity.getItem().isEmpty()) {
                samples.add(ItemHelper.stripItemName(entity.getItem().getItem()) + "x" + entity.getItem().getCount());
            }
        }
        int freeSlots = countFreeInventorySlots(mod);
        ResolveStorageChestParams storageParams = new ResolveStorageChestParams(
                settings.getAgenticStorageSearchRadius(),
                settings.getAgenticStoragePlacementRadius(),
                settings.isAgenticStoragePreferExisting(),
                settings.isAgenticStorageAllowPlacement(),
                settings.isAgenticStorageAvoidLootChests(),
                settings.getAgenticStorageResolveTimeoutSeconds());
        StorageChestScanner.ScanResult scan = StorageChestScanner.scan(mod, storageParams);
        String nearest = scan.best().map(c -> c.pos().getX() + "," + c.pos().getY() + "," + c.pos().getZ())
                .orElse("none");
        boolean hasChest = mod.getItemStorage().hasItem(net.minecraft.world.item.Items.CHEST);
        boolean macroChest = CraftMacroSupport.isMacroEnabled(mod)
                && CraftMacroSupport.isSupportedTarget(mod, new ItemTarget(net.minecraft.world.item.Items.CHEST, 1));
        boolean canPlace = ChestPlacementSelector.select(mod, pos, settings.getAgenticStoragePlacementRadius())
                .best().isPresent();
        boolean hasDepositableItems = hasDepositableItems(mod);
        boolean signItem = hasSignItemInInventory(mod);
        boolean storageTargetResolvable = scan.usableCount() > 0 || canPlace;
        return new AgenticWorldSnapshot(
                dimension,
                String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z),
                drops.size(),
                List.copyOf(samples),
                freeSlots,
                scan.usableCount(),
                nearest,
                hasChest,
                macroChest,
                canPlace,
                hasDepositableItems,
                signItem,
                storageTargetResolvable);
    }

    private static boolean hasDepositableItems(PlayerEngineController mod) {
        var inv = mod.getBaritone().getEntityContext().inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSignItemInInventory(PlayerEngineController mod) {
        var inv = mod.getBaritone().getEntityContext().inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.SignItem) {
                return true;
            }
        }
        return false;
    }

    private static int countFreeInventorySlots(PlayerEngineController mod) {
        int free = 0;
        var inv = mod.getBaritone().getEntityContext().inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }
}
