package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.containeraccess.ItemCount;
import java.util.List;

/**
 * The {@code data} payload for {@link WaypointTypes#INVENTORY} waypoint records.
 *
 * <p>This type corresponds directly to the {@code "data"} JSON object in the schema-v1 envelope.
 * Fields:
 * <ul>
 *   <li>{@link #secondaryPos} — nullable; the non-canonical half of a double chest, as
 *       {@code [x, y, z]}.</li>
 *   <li>{@link #containerKind} — the {@link com.player2.playerengine.containeraccess.ContainerKind#token()}
 *       lowercase string (e.g. {@code "double_chest"}).</li>
 *   <li>{@link #snapshot} — nullable; omitted when used slots exceed the snapshot slot threshold
 *       ({@code ellieGpsSnapshotSlotThreshold}, default 45). When null the record is keyword-only.</li>
 * </ul>
 *
 * <p>No per-slot data is stored — only the aggregate shape from
 * {@link com.player2.playerengine.containeraccess.ContainerSnapshot#aggregate()}.
 */
public final class InventoryWaypointData {

    /** Nullable; the non-canonical half of a double chest, as {@code [x, y, z]}. */
    public int[] secondaryPos;

    /** Lowercase {@link com.player2.playerengine.containeraccess.ContainerKind#token()} token. */
    public String containerKind;

    /**
     * Nullable inline item snapshot. Absent (null) for keyword-only records.
     * Items sorted ascending by {@code registryId} (the C4.5 aggregate shape verbatim).
     */
    public Snapshot snapshot;

    /** Gson-compatible default constructor. */
    public InventoryWaypointData() {}

    public InventoryWaypointData(int[] secondaryPos, String containerKind, Snapshot snapshot) {
        this.secondaryPos  = secondaryPos;
        this.containerKind = containerKind;
        this.snapshot      = snapshot;
    }

    /**
     * Inline item-count snapshot for an inventory waypoint.
     *
     * <p>Only the aggregate is stored (no per-slot data), keeping the schema immune to
     * {@code IndexedSlot.hasExtraData} changes. Items are the raw C4.5 aggregate:
     * full registry ids sorted ascending by {@code registryId}.
     */
    public static final class Snapshot {
        public int totalSlots;
        public int emptySlots;
        /** Per-item totals; full registry ids, sorted ascending by registryId. */
        public List<ItemCount> items;
        /** {@code level.getGameTime()} at read time (per-dimension; not cross-dimension-comparable). */
        public long gameTime;

        /** Gson-compatible default constructor. */
        public Snapshot() {}

        public Snapshot(int totalSlots, int emptySlots, List<ItemCount> items, long gameTime) {
            this.totalSlots = totalSlots;
            this.emptySlots = emptySlots;
            this.items      = items;
            this.gameTime   = gameTime;
        }
    }
}
