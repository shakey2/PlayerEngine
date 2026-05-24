package com.player2.playerengine.modintelligence.capability;

import java.util.List;
import java.util.Set;

/**
 * Version constants and capability id taxonomy for B4 generic mod intelligence.
 */
public final class CapabilitySchema {
    public static final int VERSION = 1;

    public static final Set<String> ITEM_CAPABILITIES = Set.of(
            "edible", "fuel", "weapon", "tool.pickaxe", "tool.axe", "tool.shovel", "tool.hoe",
            "tool.sword", "armor", "ranged_weapon", "bucket.empty", "bucket.water", "placeable",
            "container_item", "ingredient", "valuable", "quest_like", "unknown_item"
    );

    public static final Set<String> BLOCK_CAPABILITIES = Set.of(
            "container", "crafting_station", "furnace_like", "bed", "door", "button", "lever",
            "sign", "crop", "log", "leaves", "ore", "fluid_source", "breakable", "placeable",
            "hazard", "light_source", "unknown_block"
    );

    public static final Set<String> ENTITY_CAPABILITIES = Set.of(
            "hostile", "passive", "neutral", "animal", "villager_like", "tameable", "rideable",
            "projectile", "boss", "loot_source", "interactable", "container_entity", "unknown_entity"
    );

    public static final Set<String> SAFETY_SENSITIVE = Set.of(
            "edible", "container", "hostile", "passive", "bucket.water", "fuel"
    );

    private CapabilitySchema() {}

    public static Set<String> allowedFor(CapabilitySubjectKind kind) {
        return switch (kind) {
            case ITEM -> ITEM_CAPABILITIES;
            case BLOCK -> BLOCK_CAPABILITIES;
            case ENTITY_TYPE -> ENTITY_CAPABILITIES;
        };
    }

    public static String unknownId(CapabilitySubjectKind kind) {
        return switch (kind) {
            case ITEM -> "unknown_item";
            case BLOCK -> "unknown_block";
            case ENTITY_TYPE -> "unknown_entity";
        };
    }

    public static List<String> allCapabilityIds() {
        return List.of(
                "edible", "fuel", "weapon", "tool.pickaxe", "tool.axe", "tool.shovel", "tool.hoe",
                "tool.sword", "armor", "ranged_weapon", "bucket.empty", "bucket.water", "placeable",
                "container_item", "ingredient", "valuable", "quest_like", "unknown_item",
                "container", "crafting_station", "furnace_like", "bed", "door", "button", "lever",
                "sign", "crop", "log", "leaves", "ore", "fluid_source", "breakable", "hazard",
                "light_source", "unknown_block",
                "hostile", "passive", "neutral", "animal", "villager_like", "tameable", "rideable",
                "projectile", "boss", "loot_source", "interactable", "container_entity", "unknown_entity"
        );
    }
}
