package com.player2.playerengine.modintelligence.inspect;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StablePropertyCollector {
    private StablePropertyCollector() {}

    public static Map<String, String> forItem(Item item) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("maxStackSize", String.valueOf(item.getMaxStackSize()));
        if (item.canFitInsideContainerItems()) {
            props.put("fitsInContainer", "true");
        }
        return props;
    }

    public static Map<String, String> forBlock(Block block) {
        Map<String, String> props = new LinkedHashMap<>();
        BlockState state = block.defaultBlockState();
        props.put("lightEmission", String.valueOf(state.getLightEmission()));
        props.put("destroyTime", String.valueOf(block.defaultDestroyTime()));
        props.put("explosionResistance", String.valueOf(block.getExplosionResistance()));
        return props;
    }

    public static Map<String, String> forEntityType(EntityType<?> type) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("category", type.getCategory().getName());
        props.put("fireImmune", String.valueOf(type.fireImmune()));
        props.put("trackingRange", String.valueOf(type.clientTrackingRange()));
        props.put("updateInterval", String.valueOf(type.updateInterval()));
        return props;
    }
}
