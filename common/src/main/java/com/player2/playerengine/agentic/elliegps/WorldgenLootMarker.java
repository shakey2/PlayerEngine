package com.player2.playerengine.agentic.elliegps;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Quarantined version-divergent helper for Tier-2 worldgen loot detection (Part C5, Decision 12).
 *
 * <p><b>1.21.1 implementation:</b> uses the public
 * {@link RandomizableContainer#getLootTable()} interface method — no mixin required.
 * {@link net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity} implements
 * {@link RandomizableContainer} and exposes {@code getLootTable()} returning
 * {@code @Nullable ResourceKey<LootTable>}; a non-null return value means the container has
 * never been opened and still holds its worldgen loot-table reference.
 *
 * <p><b>1.20.1 divergence:</b> the sibling branch uses a {@code RandomizableContainerAccessor}
 * mixin to read the {@code protected ResourceLocation lootTable} field on
 * {@code RandomizableContainerBlockEntity} because no public getter exists there. All other
 * C5 files are byte-identical across branches except this file and the 1.20.1-only mixin pair.
 *
 * <h3>Scan-order invariant (Decision 4 + DESIGN.md §3)</h3>
 * {@link #hasUnopenedLootTable(BlockEntity)} MUST be called before any {@code getItem()},
 * {@code removeItem()}, or similar call on the block entity. Every scan starts a new
 * {@code ContainerScanService.scan()} session which calls {@code getItem()} — once that
 * runs, the loot table is unpacked and this check becomes a false negative. The
 * {@link WaypointOriginClassifier} enforces this by evaluating Tier 2 before returning
 * anything that could trigger a scan.
 *
 * <h3>Double-chest handling</h3>
 * Each half of a double chest has its own block entity with its own loot-table field.
 * {@link WaypointOriginClassifier} checks BOTH block entities — canonical and secondary
 * positions — via separate calls to this method.
 */
public final class WorldgenLootMarker {

    private WorldgenLootMarker() {}

    /**
     * Returns {@code true} when the given block entity is an un-opened worldgen container,
     * i.e. its {@code lootTable} field is non-null.
     *
     * <p>A non-null loot table is the certain indicator of a never-opened worldgen chest.
     * Once a chest is opened by a player, {@code unpackLootTable()} sets the field to null;
     * after that, this method returns {@code false} and Tier 2 cannot catch the chest.
     * Tier 3 (structure-piece check) provides the heuristic fallback.
     *
     * <p>If the block entity is null, not a {@link RandomizableContainer}, or
     * {@link RandomizableContainer#getLootTable()} returns null, returns {@code false}
     * (conservative default: a missing block entity is not a certain worldgen marker —
     * let Tier 3 catch structure hits).
     *
     * <p>This method never calls {@code getItem()}, {@code removeItem()}, or any other
     * method that would trigger {@code unpackLootTable()}.
     *
     * @param blockEntity the block entity to inspect; may be null
     * @return true iff the loot-table field is non-null
     */
    public static boolean hasUnopenedLootTable(BlockEntity blockEntity) {
        if (!(blockEntity instanceof RandomizableContainer rc)) {
            return false;
        }
        ResourceKey<LootTable> lootTable = rc.getLootTable();
        return lootTable != null;
    }

    /**
     * Convenience overload: looks up the block entity at {@code pos} in the given level and
     * delegates to {@link #hasUnopenedLootTable(BlockEntity)}.
     *
     * <p>Returns {@code false} when there is no block entity at the position (a missing block
     * entity is not a certain worldgen marker — Tier 3 provides the structure heuristic, and
     * the auto-hook's conservative default covers unevaluable cases at the classifier level).
     *
     * <p>Note: on a {@link ServerLevel}, {@code getBlockEntity} can synchronously LOAD the
     * chunk rather than returning null for an unloaded one. Harmless at current call sites
     * (the bot is standing at the container), but do not call this on far-away positions from
     * a hot path.
     *
     * @param level the server level
     * @param pos   the position to check
     * @return true iff the block entity at {@code pos} has a non-null loot-table field
     */
    public static boolean hasUnopenedLootTable(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return hasUnopenedLootTable(be);
    }
}
