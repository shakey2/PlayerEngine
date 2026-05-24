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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ModEntityTypeInspector implements CapabilityInspector<EntityType<?>> {
    @Override
    public CapabilityMap inspect(InspectionContext context, ResourceLocation id, EntityType<?> type) {
        CapabilityMap map = new CapabilityMap();
        map.setSchemaVersion(CapabilitySchema.VERSION);
        map.setInspectorVersion(CapabilityInspectors.INSPECTOR_VERSION);
        map.setSubjectKind(CapabilitySubjectKind.ENTITY_TYPE);
        map.setSubjectId(id.toString());
        map.setSourceModId(context.sourceModId(id));
        map.setMinecraftVersion(context.getMinecraftVersion());
        map.setLoader(context.getLoader());
        map.setUpdatedAtEpochMillis(System.currentTimeMillis());

        List<String> warnings = new ArrayList<>();
        List<CapabilityDecl> capabilities = new ArrayList<>();
        List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.ENTITY_TYPE, id);
        map.setTags(tags);
        map.setStableProperties(StablePropertyCollector.forEntityType(type));
        map.setSanitizedNbt(Map.of());

        try {
            inspectCategory(type, capabilities);
            inspectTags(tags, capabilities);
            if (tags.stream().anyMatch(t -> t.contains("projectile") || t.contains("arrows"))) {
                capabilities.add(new CapabilityDecl("projectile", "combat", 0.85, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, "projectile_tag",
                                "present", 0.85, null))));
            }
            if (tags.stream().anyMatch(t -> t.contains("raiders") || t.contains("skeletons"))) {
                addHostileIfAbsent(capabilities, 0.88, "entity_tag");
            }

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

    private static void inspectCategory(EntityType<?> type, List<CapabilityDecl> out) {
        MobCategory cat = type.getCategory();
        if (cat == MobCategory.MONSTER) {
            out.add(new CapabilityDecl("hostile", "combat", 0.85, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ENTITY_PROPERTY,
                            "MobCategory", "MONSTER", 0.85, null))));
        } else if (cat == MobCategory.CREATURE || cat == MobCategory.WATER_CREATURE
                || cat == MobCategory.AMBIENT) {
            out.add(new CapabilityDecl("passive", "world", 0.75, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.ENTITY_PROPERTY,
                            "MobCategory", cat.getName(), 0.75, null))));
            if (cat == MobCategory.CREATURE) {
                out.add(new CapabilityDecl("animal", "world", 0.7, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.ENTITY_PROPERTY,
                                "MobCategory", "CREATURE", 0.7, null))));
            }
        } else if (cat == MobCategory.CREATURE) {
            // covered above
        }
    }

    private static void inspectTags(List<String> tags, List<CapabilityDecl> out) {
        for (String tag : tags) {
            if (tag.contains("villager")) {
                out.add(new CapabilityDecl("villager_like", "npc", 0.8, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, tag,
                                "member", 0.8, null))));
            }
            if (tag.contains("boss")) {
                out.add(new CapabilityDecl("boss", "combat", 0.85, Map.of(),
                        List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, tag,
                                "member", 0.85, null))));
            }
        }
    }

    private static void addHostileIfAbsent(List<CapabilityDecl> out, double conf, String source) {
        if (out.stream().noneMatch(c -> "hostile".equals(c.getId()))) {
            out.add(new CapabilityDecl("hostile", "combat", conf, Map.of(),
                    List.of(new CapabilityEvidence(CapabilityEvidenceKind.TAG, source,
                            "hostile_hint", conf, null))));
        }
    }

    private static CapabilityDecl unknownDecl() {
        return new CapabilityDecl("unknown_entity", "unknown", 0.1, Map.of(),
                List.of(new CapabilityEvidence(CapabilityEvidenceKind.HEURISTIC, "inspector",
                        "no_match", 0.1, null)));
    }
}
