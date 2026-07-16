package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.retrieval.ToolDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Type-aware adapter from a supported waypoint record to an EllieGPS retrieval document. */
public final class WaypointDocument {

    private final String id;
    private final String name;
    private final String description;
    private final List<String> keywords;
    private final List<String> categoryTags;

    public WaypointDocument(WaypointRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("waypoint record is required");
        }
        if (WaypointTypes.INVENTORY.equals(record.type)) {
            if (record.inventoryData() == null) {
                throw new IllegalArgumentException("inventory waypoint data is missing");
            }
        } else if (WaypointTypes.FARM.equals(record.type)) {
            FarmWaypointData farm = record.farmData();
            if (farm == null || !farm.isSupportedVersion()) {
                throw new IllegalArgumentException("farm waypoint data version is unsupported");
            }
        } else {
            throw new IllegalArgumentException("waypoint type is not indexable");
        }
        this.id = record.id;
        this.name = kindToken(record) + " at " + posString(record);
        this.description = record.description == null ? "" : record.description;
        this.keywords = typeAwareKeywords(record);
        this.categoryTags = List.of(
                record.type == null ? "" : record.type,
                record.dimension == null ? "" : record.dimension);
    }

    public ToolDocument toToolDocument() {
        return new ToolDocument(id, name, description, "", List.of(), keywords, categoryTags);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<String> keywords() {
        return keywords;
    }

    public List<String> categoryTags() {
        return categoryTags;
    }

    private static String kindToken(WaypointRecord record) {
        if (WaypointTypes.FARM.equals(record.type)) {
            return "farm";
        }
        InventoryWaypointData inventory = record.inventoryData();
        if (inventory != null
                && inventory.containerKind != null
                && !inventory.containerKind.isEmpty()) {
            return inventory.containerKind.replace('_', ' ');
        }
        return "inventory";
    }

    private static List<String> typeAwareKeywords(WaypointRecord record) {
        Set<String> tokens = new LinkedHashSet<>();
        if (record.keywords != null) {
            tokens.addAll(record.keywords);
        }
        if (WaypointTypes.FARM.equals(record.type)) {
            tokens.add("farm");
            tokens.add("crops");
            FarmWaypointData farm = record.farmData();
            if (farm != null && farm.isSupportedVersion()) {
                if (farm.openSlots().isPresent()) {
                    tokens.add("open slots");
                    tokens.add(farm.openSlots().getAsInt() > 0 ? "available" : "full");
                } else {
                    tokens.add("capacity unknown");
                }
                farm.plantingRestrictionItemId().ifPresent(restriction -> {
                    tokens.add("single crop");
                    tokens.add("planting restricted");
                    addIdTokens(tokens, restriction);
                });
                for (FarmCropCount crop : farm.crops()) {
                    addIdTokens(tokens, crop.blockId());
                    if (crop.plantingItemId() != null) {
                        addIdTokens(tokens, crop.plantingItemId());
                    }
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static void addIdTokens(Set<String> tokens, String id) {
        tokens.add(id);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        tokens.add(path.replace('_', ' '));
    }

    private static String posString(WaypointRecord record) {
        if (record.pos != null && record.pos.length >= 3) {
            return "(" + record.pos[0] + "," + record.pos[1] + "," + record.pos[2] + ")";
        }
        return "(?,?,?)";
    }
}
