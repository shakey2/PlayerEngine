package com.player2.playerengine.containeraccess;

/**
 * One non-empty slot of a deep scan, addressed in the stable vanilla {@code Container} index
 * space (double chests: 0-26 = combiner-FIRST half, 27-53 = second half).
 *
 * @param slot         vanilla container slot index (0-based, canonical 54-slot space for doubles)
 * @param registryId   full item registry id, e.g. {@code "minecraft:iron_ingot"} (namespace kept
 *                     here; the AI-text formatter strips {@code minecraft:} for display)
 * @param count        exact live stack count at read time
 * @param hasExtraData reserved drift marker for NBT/component-carrying stacks. Ships
 *                     always-{@code false} in C4.5: the 1.20.1 {@code stack.hasTag()} vs 1.21.1
 *                     component check is version-divergent and would break common byte-parity
 *                     (Decision 8). The field exists so the C5 snapshot schema does not break if
 *                     component awareness lands later; the deep-scan {@code *} marker is reserved
 *                     for it.
 */
public record IndexedSlot(int slot, String registryId, int count, boolean hasExtraData) {
}
