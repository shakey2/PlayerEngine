package com.player2.playerengine.mixins;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor for the protected {@code lootTable} field on
 * {@link RandomizableContainerBlockEntity} (1.20.1 only).
 *
 * <p>In 1.20.1 the field is {@code protected ResourceLocation lootTable} with no public
 * getter. The 1.21.1 branch uses the public {@code RandomizableContainer.getLootTable()}
 * interface method instead — no mixin needed. This file exists ONLY on the 1.20.1 branch
 * (documented C5 branch divergence; see Part C5 plan Decision 12).
 *
 * <p>Consumed only by {@link com.player2.playerengine.agentic.elliegps.WorldgenLootMarker}
 * to read the field before any {@code getItem()} call would destroy it via
 * {@code unpackLootTable()}.
 */
@Mixin(RandomizableContainerBlockEntity.class)
public interface RandomizableContainerAccessor {

    /**
     * Returns the loot-table {@link ResourceLocation} for this container, or {@code null} when
     * the container has already been opened (its loot was unpacked).
     *
     * <p>A non-null value is the single cheapest indicator of an un-opened worldgen container.
     * Reading this field does NOT trigger loot unpacking.
     */
    @Accessor("lootTable")
    ResourceLocation getLootTable();
}
