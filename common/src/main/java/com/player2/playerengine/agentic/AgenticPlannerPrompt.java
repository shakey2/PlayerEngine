package com.player2.playerengine.agentic;

import com.player2.playerengine.agentic.AgenticToolContextBuilder.AgenticToolContext;

public final class AgenticPlannerPrompt {

    private AgenticPlannerPrompt() {}

    public static String systemPrompt() {
        return """
                You are a Minecraft gameplay planner for an NPC bot.
                Output exactly one JSON object matching schema version 1.
                Do not wrap in markdown. Do not emit command strings to execute.
                Use only the allowed step kinds and sequences listed in the user message.
                A plan may have up to four steps in one of these orders only:
                  - gather_loose_items alone
                  - resolve_storage_chest alone
                  - gather_loose_items then resolve_storage_chest
                  - resolve_storage_chest then deposit_items
                  - gather_loose_items then resolve_storage_chest then deposit_items
                  - resolve_storage_chest then deposit_items then label_chest
                  - gather_loose_items then resolve_storage_chest then deposit_items then label_chest
                  - smelt_items alone
                  - smith_items alone
                  - mine_block alone
                  - gather_loose_items then mine_block
                  - mine_block then gather_loose_items
                  - mine_block then resolve_storage_chest then deposit_items
                  - mine_block then resolve_storage_chest then deposit_items then label_chest
                deposit_items always requires a preceding resolve_storage_chest in the same plan.
                label_chest is optional, best-effort, and may only be the LAST step after deposit_items.
                smelt_items smelts/blast-furnaces/smokes a raw item into a processed output (e.g. raw iron -> iron ingot). Use smelt_items when the goal is to cook or smelt something in a furnace.
                smith_items upgrades an item at a smithing table using the resolved template, base, and addition from the recipe registry (e.g. diamond pickaxe -> netherite pickaxe). Use smith_items when the goal is to upgrade gear using netherite or another smithing-table upgrade. Item arg is the desired OUTPUT item (e.g. netherite_pickaxe). Required args: item. Optional: count (default 1).
                mine_block breaks the nearest matching block of a named type within a radius and collects its drops (e.g. mine the nearby iron ore, dig some cobblestone). The bot resolves and acquires the required pickaxe automatically, so do NOT add a separate get/craft step for the tool. Required arg: blockId (registry id or tag token, e.g. minecraft:iron_ore). Optional: maxBlocks, radius, timeoutSeconds. mine_block NEVER stores the result — the mined materials stay in the bot's inventory, so a bare mine_block plan is complete and you must NOT append resolve_storage_chest/deposit_items unless the player explicitly asked to store it. gather_loose_items and mine_block may BOTH appear in one plan (either order) so the bot also sweeps up block drops that auto-pickup missed.
                Waypoints are managed automatically after deposits and via the create/delete/audit/compare/locate waypoint bot commands — never output waypoint plan steps (no register_waypoint or elliegps step kinds).
                """;
    }

    public static String userPrompt(String goalText, AgenticToolContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Owner goal:\n").append(goalText).append("\n\n");
        sb.append("Retrieved command/tool context (reference only — do not output command names as steps):\n");
        sb.append(AgenticToolContextBuilder.formatToolCards(context.tools())).append('\n');
        sb.append('\n').append(AgenticToolContextBuilder.allowedStepsSchema()).append('\n');
        AgenticWorldSnapshot world = context.world();
        if (world != null) {
            sb.append("\nWorld snapshot:\n");
            sb.append("dimension: ").append(world.dimension()).append('\n');
            sb.append("position: ").append(world.position()).append('\n');
            sb.append("nearby_drops_within_radius: ").append(world.nearbyDropCount()).append('\n');
            sb.append("sample_drops: ").append(String.join(", ", world.sampleDropItems())).append('\n');
            sb.append("free_inventory_slots: ").append(world.freeInventorySlots()).append('\n');
            sb.append("nearby_valid_chests: ").append(world.nearbyValidChestCount()).append('\n');
            sb.append("nearest_chest_hint: ").append(world.nearestChestHint()).append('\n');
            sb.append("has_chest_item: ").append(world.hasChestItemInInventory()).append('\n');
            sb.append("craft_macro_supports_chest: ").append(world.craftMacroSupportsChest()).append('\n');
            sb.append("quick_placement_possible: ").append(world.quickPlacementPossible()).append('\n');
            sb.append("has_depositable_items: ").append(world.hasDepositableItems()).append('\n');
            sb.append("sign_item_in_inventory: ").append(world.signItemInInventory()).append('\n');
            sb.append("label_chest crafts a sign itself when none is held (if wood is obtainable nearby), so sign_item_in_inventory=false does not preclude a label_chest step.\n");
            sb.append("When a sign must be crafted, request the generic \"sign\" (the mod picks the wood species deterministically from available wood); only name a specific \"<wood>_sign\" if the owner explicitly asked for that species.\n");
            sb.append("storage_target_resolvable: ").append(world.storageTargetResolvable()).append('\n');
        }
        sb.append("""
                
                Respond with JSON only. Example gather/resolve/deposit/label plan:
                {
                  "schemaVersion": 1,
                  "goalSummary": "Collect nearby drops, store them, and label the chest",
                  "steps": [
                    {"id":"gather-1","kind":"gather_loose_items","args":{"radius":"16","maxItems":"64","settleSeconds":"3","timeoutSeconds":"60"},"rationale":"Collect drops."},
                    {"id":"storage-1","kind":"resolve_storage_chest","args":{"searchRadius":"20","placementRadius":"8","preferExisting":"true","allowPlacement":"true"},"rationale":"Prepare chest."},
                    {"id":"deposit-1","kind":"deposit_items","args":{"depositAll":"true","keepTools":"true","timeoutSeconds":"120"},"rationale":"Store the collected items."},
                    {"id":"label-1","kind":"label_chest","args":{"autoLabel":"true","timeoutSeconds":"60"},"rationale":"Label the storage chest (best-effort)."}
                  ],
                  "plannerNote": "Storage waypoints are registered automatically after the deposit."
                }
                """);
        return sb.toString();
    }
}
