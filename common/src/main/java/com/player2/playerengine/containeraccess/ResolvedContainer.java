package com.player2.playerengine.containeraccess;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;

/**
 * A live, canonically-indexed container view produced by {@link ContainerResolver}.
 *
 * <p><b>Engine rule (Decision 4):</b> validation and transfer must both operate on the
 * {@link #container()} object of the SAME resolution — for double chests the 54-slot
 * {@code CompoundContainer} whose slot ops delegate to the correct half and fire
 * {@code setChanged}. Never pair this view with the legacy single-half deposit engine in
 * {@code tasks/container} — it writes and counts only the block entity at the named pos.
 *
 * @param container    the vanilla container view; a 54-slot {@code CompoundContainer} for
 *                     double chests (combiner-FIRST half = slots 0-26)
 * @param kind         container classification
 * @param canonicalPos FIRST half's pos for double chests ({@code ChestType.RIGHT} rule),
 *                     the queried pos otherwise — the stable C5 catalogue key
 * @param secondaryPos nullable; the other (non-canonical) half of a double chest
 * @param totalSlots   {@code container.getContainerSize()}
 */
public record ResolvedContainer(
        Container container,
        ContainerKind kind,
        BlockPos canonicalPos,
        BlockPos secondaryPos,
        int totalSlots
) {
}
