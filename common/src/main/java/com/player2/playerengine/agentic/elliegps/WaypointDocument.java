package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.retrieval.ToolDocument;
import java.util.List;

/**
 * Adapter from a {@link WaypointRecord} to a {@link ToolDocument} for the EllieGPS retrieval
 * index (Part C5, WS2).
 *
 * <p>Follows the {@code CapabilityDocument.toToolDocument()} adapter template. The document
 * id is the waypoint id; the name is a short kind+position string for human readability in
 * logs; description and keywords are taken directly from the record. {@code whenToUse} and
 * {@code examples} are left empty (the keywords carry synonym coverage). {@code categoryTags}
 * carries {@code [type, dimension]} for potential future category post-filtering.
 *
 * <p>Only {@link WaypointTypes#INVENTORY} records are adapted and indexed. The caller is
 * responsible for filtering.
 */
public final class WaypointDocument {

    private final String id;
    private final String name;
    private final String description;
    private final List<String> keywords;
    private final List<String> categoryTags;

    /**
     * Creates a {@link WaypointDocument} from the given inventory waypoint record.
     *
     * @param record an inventory-type {@link WaypointRecord}; must not be null
     */
    public WaypointDocument(WaypointRecord record) {
        this.id = record.id;

        // Short positional name: "inventory chest at (x,y,z)" — kind if available, else type
        String kind = kindToken(record);
        this.name = kind + " at " + posString(record);

        this.description = record.description != null ? record.description : "";
        this.keywords    = record.keywords    != null ? List.copyOf(record.keywords) : List.of();
        this.categoryTags = List.of(
                record.type      != null ? record.type      : "inventory",
                record.dimension != null ? record.dimension : "");
    }

    /** Returns the {@link ToolDocument} representation for use by {@link com.player2.playerengine.retrieval.LexicalIndex} / {@link com.player2.playerengine.retrieval.MinHashIndex}. */
    public ToolDocument toToolDocument() {
        return new ToolDocument(id, name, description, "", List.of(), keywords, categoryTags);
    }

    public String id()           { return id; }
    public String name()         { return name; }
    public String description()  { return description; }
    public List<String> keywords()     { return keywords; }
    public List<String> categoryTags() { return categoryTags; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String kindToken(WaypointRecord record) {
        InventoryWaypointData inv = record.inventoryData();
        if (inv != null && inv.containerKind != null && !inv.containerKind.isEmpty()) {
            return inv.containerKind.replace('_', ' ');
        }
        return "inventory";
    }

    private static String posString(WaypointRecord record) {
        if (record.pos != null && record.pos.length >= 3) {
            return "(" + record.pos[0] + "," + record.pos[1] + "," + record.pos[2] + ")";
        }
        return "(?,?,?)";
    }
}
