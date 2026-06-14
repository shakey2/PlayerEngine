package com.player2.playerengine.containeraccess;

/**
 * One aggregate line of a container snapshot: total live count of an item across all slots.
 *
 * @param registryId full item registry id, e.g. {@code "minecraft:iron_ingot"} (namespace kept
 *                   in the snapshot for the C5 index contract; AI text strips {@code minecraft:})
 * @param count      exact live total at read time (targeted snapshots may carry 0 for a
 *                   requested-but-absent item — a successful scan result, not an error)
 */
public record ItemCount(String registryId, int count) {
}
