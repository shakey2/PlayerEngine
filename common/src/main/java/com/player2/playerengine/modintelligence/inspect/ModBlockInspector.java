package com.player2.playerengine.modintelligence.inspect;

import com.player2.playerengine.modintelligence.capability.CapabilityDecl;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidence;
import com.player2.playerengine.modintelligence.capability.CapabilityEvidenceKind;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ModBlockInspector implements CapabilityInspector<Block> {
    @Override
    public CapabilityMap inspect(InspectionContext context, ResourceLocation id, Block block) {
        CapabilityMap map = new CapabilityMap();
        map.setSchemaVersion(CapabilitySchema.VERSION);
        map.setInspectorVersion(CapabilityInspectors.INSPECTOR_VERSION);
        map.setSubjectKind(CapabilitySubjectKind.BLOCK);
        map.setSubjectId(id.toString());
        map.setSourceModId(context.sourceModId(id));
        map.setMinecraftVersion(context.getMinecraftVersion());
        map.setLoader(context.getLoader());
        map.setUpdatedAtEpochMillis(System.currentTimeMillis());

        List<String> warnings = new ArrayList<>();
        List<CapabilityDecl> capabilities = new ArrayList<>();
        List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.BLOCK, id);
        map.setTags(tags);
        map.setStableProperties(StablePropertyCollector.forBlock(block));
        map.setSanitizedNbt(Map.of());

        try {
            BlockState state = block.defaultBlockState();
            inspectTags(tags, capabilities);
            inspectClasses(block, capabilities, warnings);
            inspectLight(state, capabilities);
            inspectFluids(block, capabilities);

            if (capabilities.isEmpty()) {
                capabilities.add(unknownDecl());
                map.setStatus(CapabilityStatus.UNKNOWN);
            } else {
                map.setStatus(CapabilityStatus.READY);
            }
        } catch (Exception e) {
            warnings.add("inspect_error:" + e.getClass().getSimpleName());
            capabilities.clear();
            capabilities.add(unknownDecl());
            map.setStatus(CapabilityStatus.FAILED);
        }

        map.setCapabilities(capabilities);
        map.setWarnings(warnings);
        return map;
    }

    private static void inspectTags(List<String> tags, List<CapabilityDecl> out) {
        addTagCap(tags, "logs", "log", 0.92, out);
        addTagCap(tags, "leaves", "leaves", 0.9, out);
        addTagCap(tags, "ores", "ore", 0.9, out);
        addTagCap(tags, "crops", "crop", 0.88, out);
        addTagCap(tags, "planks", "breakable", 0.6, out);
        addTagCap(tags, "beds", "bed", 0.9, out);
        addTagCap(tags, "doors", "door", 0.9, out);
        addTagCap(tags, "buttons", "button", 0.88, out);
        addTagCap(tags, "signs", "sign", 0.88, out);
    }

    private static void addTagCap(List<String> tags, String suffix, String capId, double conf,
                                  List<CapabilityDecl> out) {
        if (out.stream().anyMatch(c -> capId.equals(c.getId()))) {
            return;
        }
        for (String tag : tags) {
            if (tag.endsWith("/" + suffix) || tag.contains(":" + suffix)) {
                out.add(new CapabilityDecl(capId, "world", conf, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, tag,
                                "member", conf, null))));
                return;
            }
        }
    }

    private static void inspectClasses(Block block, List<CapabilityDecl> out, List<String> warnings) {
        if (block instanceof CraftingTableBlock) {
            out.add(new CapabilityDecl("crafting_station", "crafting", 0.92, Map.of(),
                    List.of(ev(CapabilityEvidenceKind.BLOCK_PROPERTY, "CraftingTableBlock", 0.92))));
        }
        if (block instanceof AbstractFurnaceBlock) {
            out.add(new CapabilityDecl("furnace_like", "crafting", 0.92, Map.of(),
                    List.of(ev(CapabilityEvidenceKind.BLOCK_PROPERTY, "FurnaceBlock", 0.92))));
        }
        if (block instanceof AbstractChestBlock) {
            out.add(new CapabilityDecl("container", "storage", 0.9, Map.of(),
                    List.of(ev(CapabilityEvidenceKind.BLOCK_PROPERTY, "ChestBlock", 0.9))));
        } else if (block instanceof BaseEntityBlock) {
            warnings.add("possible_container:block_entity_block");
        }
        if (block instanceof DoorBlock) {
            addIfAbsent(out, "door", 0.9, "DoorBlock");
        }
        if (block instanceof ButtonBlock) {
            addIfAbsent(out, "button", 0.88, "ButtonBlock");
        }
        if (block instanceof LeverBlock) {
            addIfAbsent(out, "lever", 0.88, "LeverBlock");
        }
        if (block instanceof SignBlock) {
            addIfAbsent(out, "sign", 0.88, "SignBlock");
        }
        if (block instanceof BedBlock) {
            addIfAbsent(out, "bed", 0.9, "BedBlock");
        }
        if (block instanceof CropBlock) {
            addIfAbsent(out, "crop", 0.9, "CropBlock");
        }
    }

    private static void inspectLight(BlockState state, List<CapabilityDecl> out) {
        int light = state.getLightEmission();
        if (light > 0) {
            out.add(new CapabilityDecl("light_source", "world", 0.9,
                    Map.of("lightLevel", String.valueOf(light)),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.BLOCK_PROPERTY,
                            "lightEmission", String.valueOf(light), 0.9, null))));
        }
    }

    private static void inspectFluids(Block block, List<CapabilityDecl> out) {
        if (block instanceof LiquidBlock) {
            out.add(new CapabilityDecl("fluid_source", "fluid", 0.9, Map.of(),
                    List.of(ev(CapabilityEvidenceKind.BLOCK_PROPERTY, "LiquidBlock", 0.9))));
        }
    }

    private static void addIfAbsent(List<CapabilityDecl> out, String capId, double conf, String src) {
        if (out.stream().noneMatch(c -> capId.equals(c.getId()))) {
            out.add(new CapabilityDecl(capId, "world", conf, Map.of(),
                    List.of(ev(CapabilityEvidenceKind.BLOCK_PROPERTY, src, conf))));
        }
    }

    private static CapabilityEvidence ev(CapabilityEvidenceKind kind, String source, double conf) {
        return new CapabilityEvidence(kind, source, "true", conf, null);
    }

    private static CapabilityDecl unknownDecl() {
        return new CapabilityDecl("unknown_block", "unknown", 0.1, Map.of(),
                List.of(new CapabilityEvidence(CapabilityEvidenceKind.HEURISTIC, "inspector",
                        "no_match", 0.1, null)));
    }
}
