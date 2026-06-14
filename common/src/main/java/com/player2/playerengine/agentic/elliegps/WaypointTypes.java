package com.player2.playerengine.agentic.elliegps;

/**
 * Waypoint type constants for the EllieGPS type-extensible record schema.
 *
 * <p>Only {@link #INVENTORY} records are indexed and queried in C5. Records with unknown
 * {@code type} values are preserved verbatim on load/save (forward compatibility).
 */
public final class WaypointTypes {
    /** Inventory container waypoint — a chest/barrel/etc. with optional item snapshot. */
    public static final String INVENTORY = "inventory";

    private WaypointTypes() {}
}
