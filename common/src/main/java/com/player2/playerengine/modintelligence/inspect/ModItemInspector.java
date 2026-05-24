package com.player2.playerengine.modintelligence.inspect;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidence;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidenceKind;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.multiversion.FoodComponentWrapper;
import com.player2.playerengine.multiversion.item.ItemVer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModItemInspector implements CapabilityInspector<Item> {
    @Override
    public CapabilityMap inspect(InspectionContext context, ResourceLocation id, Item item) {
        CapabilityMap map = new CapabilityMap();
        map.setSchemaVersion(CapabilitySchema.VERSION);
        map.setInspectorVersion(CapabilityInspectors.INSPECTOR_VERSION);
        map.setSubjectKind(CapabilitySubjectKind.ITEM);
        map.setSubjectId(id.toString());
        map.setSourceModId(context.sourceModId(id));
        map.setMinecraftVersion(context.getMinecraftVersion());
        map.setLoader(context.getLoader());
        map.setUpdatedAtEpochMillis(System.currentTimeMillis());

        List<String> warnings = new ArrayList<>();
        List<CapabilityDecl> capabilities = new ArrayList<>();
        List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.ITEM, id);
        map.setTags(tags);
        map.setStableProperties(StablePropertyCollector.forItem(item));
        map.setSanitizedNbt(SanitizedNbtSnapshot.fromDefaultStack(item, context.getRegistryAccess()));

        try {
            inspectFood(item, tags, capabilities);
            inspectBuckets(item, tags, capabilities);
            inspectTools(item, tags, capabilities);
            inspectArmor(item, capabilities);
            inspectRanged(item, capabilities);
            inspectPlaceable(item, id, capabilities);
            inspectContainerItem(item, tags, capabilities, warnings);
            inspectFuel(item, tags, capabilities);
            inspectTags(item, tags, capabilities);

            if (capabilities.isEmpty()) {
                capabilities.add(unknownDecl(CapabilitySubjectKind.ITEM));
                map.setStatus(CapabilityStatus.UNKNOWN);
            } else {
                map.setStatus(CapabilityStatus.READY);
            }
        } catch (Exception e) {
            warnings.add("inspect_error:" + e.getClass().getSimpleName());
            capabilities.clear();
            capabilities.add(unknownDecl(CapabilitySubjectKind.ITEM));
            map.setStatus(CapabilityStatus.FAILED);
        }

        map.setCapabilities(capabilities);
        map.setWarnings(warnings);
        return map;
    }

    private static void inspectFood(Item item, List<String> tags, List<CapabilityDecl> out) {
        if (!ItemVer.isFood(item)) {
            return;
        }
        FoodComponentWrapper food = ItemVer.getFoodComponent(item);
        Map<String, String> attrs = new LinkedHashMap<>();
        if (food != null) {
            attrs.put("nutrition", String.valueOf(food.getHunger()));
            attrs.put("saturationModifier", String.valueOf(food.getSaturationModifier()));
        }
        List<CapabilityEvidence> evidence = new ArrayList<>();
        evidence.add(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY, "food",
                attrs.toString(), 0.95, "Item is edible"));
        if (tags.stream().anyMatch(t -> t.contains("foods"))) {
            evidence.add(new CapabilityEvidence(CapabilityEvidenceKind.TAG, "minecraft:foods",
                    "present", 0.9, null));
        }
        out.add(new CapabilityDecl("edible", "survival", 0.95, attrs, evidence));
    }

    private static void inspectBuckets(Item item, List<String> tags, List<CapabilityDecl> out) {
        if (item instanceof BucketItem) {
            boolean water = tags.contains(PlayerEngine.WATER_BUCKETS.location().toString())
                    || item == Items.WATER_BUCKET;
            String capId = water ? "bucket.water" : "bucket.empty";
            double conf = water ? 0.95 : 0.9;
            List<CapabilityEvidence> evidence = List.of(
                    new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY, "BucketItem",
                            item.toString(), conf, null));
            out.add(new CapabilityDecl(capId, "fluid", conf, Map.of(), evidence));
            return;
        }
        if (tags.contains(PlayerEngine.EMPTY_BUCKETS.location().toString())) {
            out.add(new CapabilityDecl("bucket.empty", "fluid", 0.88, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG,
                            PlayerEngine.EMPTY_BUCKETS.location().toString(), "member", 0.88, null))));
        }
        if (tags.contains(PlayerEngine.WATER_BUCKETS.location().toString())) {
            out.add(new CapabilityDecl("bucket.water", "fluid", 0.88, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG,
                            PlayerEngine.WATER_BUCKETS.location().toString(), "member", 0.88, null))));
        }
    }

    private static void inspectTools(Item item, List<String> tags, List<CapabilityDecl> out) {
        addToolIf(item instanceof PickaxeItem, "tool.pickaxe", item, tags, out);
        addToolIf(item instanceof AxeItem, "tool.axe", item, tags, out);
        addToolIf(item instanceof ShovelItem, "tool.shovel", item, tags, out);
        addToolIf(item instanceof HoeItem, "tool.hoe", item, tags, out);
        addToolIf(item instanceof SwordItem, "tool.sword", item, tags, out);
        if (item instanceof SwordItem || item instanceof TridentItem) {
            out.add(new CapabilityDecl("weapon", "combat", 0.85, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY,
                            item.getClass().getSimpleName(), "weapon", 0.85, null))));
        }
        addTagTool(tags, "pickaxes", "tool.pickaxe", out);
        addTagTool(tags, "axes", "tool.axe", out);
        addTagTool(tags, "shovels", "tool.shovel", out);
        addTagTool(tags, "hoes", "tool.hoe", out);
        addTagTool(tags, "swords", "tool.swords", out);
    }

    private static void addToolIf(boolean cond, String capId, Item item, List<String> tags,
                                  List<CapabilityDecl> out) {
        if (!cond) {
            return;
        }
        if (out.stream().anyMatch(c -> capId.equals(c.getId()))) {
            return;
        }
        List<CapabilityEvidence> evidence = new ArrayList<>();
        evidence.add(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY,
                item.getClass().getSimpleName(), capId, 0.9, null));
        out.add(new CapabilityDecl(capId, "tool", 0.9, Map.of(), evidence));
    }

    private static void addTagTool(List<String> tags, String tagSuffix, String capId,
                                   List<CapabilityDecl> out) {
        if (out.stream().anyMatch(c -> capId.equals(c.getId()))) {
            return;
        }
        for (String tag : tags) {
            if (tag.endsWith("/" + tagSuffix) || tag.contains(":" + tagSuffix)) {
                out.add(new CapabilityDecl(capId, "tool", 0.88, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, tag,
                                "member", 0.88, null))));
                return;
            }
        }
    }

    private static void inspectArmor(Item item, List<CapabilityDecl> out) {
        if (item instanceof ArmorItem) {
            out.add(new CapabilityDecl("armor", "combat", 0.9, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY,
                            "ArmorItem", "true", 0.9, null))));
        }
    }

    private static void inspectRanged(Item item, List<CapabilityDecl> out) {
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            out.add(new CapabilityDecl("ranged_weapon", "combat", 0.9, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY,
                            item.getClass().getSimpleName(), "ranged", 0.9, null))));
        }
    }

    private static void inspectPlaceable(Item item, ResourceLocation id, List<CapabilityDecl> out) {
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            Map<String, String> attrs = Map.of("blockId", BuiltInRegistries.BLOCK.getKey(block).toString());
            out.add(new CapabilityDecl("placeable", "building", 0.92, attrs,
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ITEM_PROPERTY,
                            "BlockItem", id.toString(), 0.92, null))));
        }
    }

    private static void inspectContainerItem(Item item, List<String> tags, List<CapabilityDecl> out,
                                             List<String> warnings) {
        if (tags.stream().anyMatch(t -> t.contains("shulker") || t.contains("bundle"))) {
            out.add(new CapabilityDecl("container_item", "storage", 0.85, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, "container_tag",
                            "present", 0.85, null))));
            return;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.contains("shulker") || path.contains("bundle")) {
            warnings.add("possible_container_heuristic");
        }
    }

    private static void inspectFuel(Item item, List<String> tags, List<CapabilityDecl> out) {
        if (tags.stream().anyMatch(t -> t.contains("fuel") || t.contains("smelts"))) {
            out.add(new CapabilityDecl("fuel", "crafting", 0.8, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, "fuel_tag",
                            "present", 0.8, null))));
        }
    }

    private static void inspectTags(Item item, List<String> tags, List<CapabilityDecl> out) {
        for (String tag : tags) {
            if (tag.contains("ingots") || tag.contains("gems") || tag.contains("ores")) {
                if (out.stream().noneMatch(c -> "ingredient".equals(c.getId()))) {
                    out.add(new CapabilityDecl("ingredient", "crafting", 0.75, Map.of(),
                            List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, tag,
                                    "member", 0.75, null))));
                }
            }
        }
    }

    private static CapabilityDecl unknownDecl(CapabilitySubjectKind kind) {
        String id = CapabilitySchema.unknownId(kind);
        return new CapabilityDecl(id, "unknown", 0.1, Map.of(),
                List.of(new CapabilityEvidence(CapabilityEvidenceKind.HEURISTIC, "inspector",
                        "no_match", 0.1, "No deterministic capability inferred")));
    }
}
