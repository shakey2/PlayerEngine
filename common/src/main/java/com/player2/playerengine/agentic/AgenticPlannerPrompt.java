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
                deposit_items always requires a preceding resolve_storage_chest in the same plan.
                label_chest is optional, best-effort, and may only be the LAST step after deposit_items.
                Do not output register_waypoint or elliegps steps; EllieGPS/waypoint support is not available yet.
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
                  "plannerNote": "EllieGPS waypoints are not available yet."
                }
                """);
        return sb.toString();
    }
}
