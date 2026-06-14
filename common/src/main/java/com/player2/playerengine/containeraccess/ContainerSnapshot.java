package com.player2.playerengine.containeraccess;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * The structured result of a fresh container read — and the in-memory inventory-indexing
 * contract Part C5 (EllieGPS waypoint create/audit/compare) builds on. C4.5 persists nothing;
 * snapshots are values handed to the caller, C5 owns storing them.
 *
 * <p>C5 contract guarantees (do not weaken):
 * <ul>
 *   <li><b>Stable slot index:</b> vanilla {@code Container} indices; double chests are one
 *       54-slot space, 0-26 = combiner-FIRST ({@code ChestType.RIGHT}) half, 27-53 = second
 *       half; {@code canonicalPos} is the FIRST half's pos no matter which half was queried.
 *       Two snapshots of the same container are field-by-field comparable.</li>
 *   <li><b>Aggregate shape:</b> full registry ids ({@code minecraft:iron_ingot}) sorted
 *       ascending by registryId, plus {@code emptySlots}/{@code totalSlots}.</li>
 *   <li><b>Freshness:</b> {@code gameTime} stamps the read; it is per-dimension game time —
 *       compare freshness only within the same dimension.</li>
 *   <li><b>Known limit:</b> aggregation is by Item registry id, so NBT/component-only drift is
 *       undetectable in C4.5 ({@link IndexedSlot#hasExtraData} ships always-{@code false}).</li>
 *   <li><b>Worldgen-origin marker:</b> the scan that produced this snapshot may have unpacked a
 *       pending loot table (irreversibly). C5's create pipeline must run worldgen-origin checks
 *       BEFORE scanning; nothing here records the pre-unpack state (the accessor is
 *       version-divergent — see the plan's C5 contract section).</li>
 * </ul>
 *
 * @param dimensionId  e.g. {@code "minecraft:overworld"}
 * @param canonicalPos FIRST half for double chests; the queried pos otherwise
 * @param secondaryPos nullable; the other (non-canonical) half of a double chest
 * @param kind         container classification
 * @param totalSlots   {@code container.getContainerSize()} of the resolved view (54 for doubles)
 * @param emptySlots   exact live empty-slot count at read time
 * @param aggregate    per-item totals, sorted by registryId ascending (deterministic). For
 *                     TARGETED scans: one entry per requested item with its live count (0 when
 *                     absent), still sorted ascending.
 * @param slots        non-empty slots, ascending slot index; non-null ONLY for DEEP scans
 *                     ({@code null} for light and targeted)
 * @param gameTime     {@code level.getGameTime()} at read — the C5 freshness marker
 */
public record ContainerSnapshot(
        String dimensionId,
        BlockPos canonicalPos,
        BlockPos secondaryPos,
        ContainerKind kind,
        int totalSlots,
        int emptySlots,
        List<ItemCount> aggregate,
        List<IndexedSlot> slots,
        long gameTime
) {
}
