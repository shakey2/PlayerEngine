package com.player2.playerengine.agentic.elliegps;

/**
 * Waypoint type constants for the EllieGPS type-extensible record schema.
 *
 * <p>{@link #INVENTORY} and supported {@link #FARM} records are indexable. Records with unknown
 * {@code type} values are preserved verbatim on load/save (forward compatibility).
 */
public final class WaypointTypes {
    /** Inventory container waypoint — a chest/barrel/etc. with optional item snapshot. */
    public static final String INVENTORY = "inventory";

    /** Hydrated crop plot waypoint centered on its source-water block. */
    public static final String FARM = "farm";

    private WaypointTypes() {}
}
