package com.player2.playerengine.agentic.elliegps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Explicit versioned payload for an EllieGPS {@code farm} waypoint.
 *
 * <p>The waypoint envelope remains schema version 1. Legacy farm-data v1 remains readable, but it
 * has no authoritative open-slot snapshot. Current v2 data records exact live open capacity and an
 * optional future bot-planting restriction. Unknown members of readable payloads are retained in
 * {@link #extras}; unsupported future payloads retain their complete raw object and are never
 * rewritten into an older shape.
 */
public final class FarmWaypointData {

    public static final int LEGACY_DATA_VERSION = 1;
    public static final int DATA_VERSION = 2;
    public static final int MAX_HYDRATION_RADIUS = 4;
    public static final int MAX_FARMLAND_BLOCKS = 80;

    private static final String FIELD_DATA_VERSION = "dataVersion";
    private static final String FIELD_RADIUS = "radius";
    private static final String FIELD_FARMLAND_COUNT = "farmlandCount";
    private static final String FIELD_OPEN_SLOTS = "openSlots";
    private static final String FIELD_CROPS = "crops";
    private static final String FIELD_PLANTING_RESTRICTION = "plantingRestrictionItemId";

    private final int dataVersion;
    private final int radius;
    private final int farmlandCount;
    private final Integer openSlots;
    private final List<FarmCropCount> crops;
    private final String plantingRestrictionItemId;
    private final JsonObject extras;
    private final JsonObject unsupportedRawSource;

    /**
     * Creates readable legacy-v1 data whose open capacity is deliberately unknown.
     *
     * <p>This source-compatible constructor remains for temporary scan records and legacy fixtures.
     * Persisted live observations should use the current constructor that supplies {@code openSlots}.
     */
    public FarmWaypointData(int radius, int farmlandCount, List<FarmCropCount> crops) {
        this(LEGACY_DATA_VERSION, radius, farmlandCount, null, crops, null,
                null, null, true);
    }

    /** Creates current v2 data with unrestricted mixed planting. */
    public FarmWaypointData(
            int radius,
            int farmlandCount,
            int openSlots,
            List<FarmCropCount> crops) {
        this(radius, farmlandCount, openSlots, crops, null);
    }

    /** Creates current v2 data with an optional canonical planting-item restriction. */
    public FarmWaypointData(
            int radius,
            int farmlandCount,
            int openSlots,
            List<FarmCropCount> crops,
            String plantingRestrictionItemId) {
        this(DATA_VERSION, radius, farmlandCount, openSlots, crops, plantingRestrictionItemId,
                null, null, true);
    }

    static FarmWaypointData current(
            int radius,
            int farmlandCount,
            int openSlots,
            List<FarmCropCount> crops,
            String plantingRestrictionItemId,
            JsonObject extras) {
        return new FarmWaypointData(
                DATA_VERSION,
                radius,
                farmlandCount,
                openSlots,
                crops,
                plantingRestrictionItemId,
                removeKnownCurrentFields(extras),
                null,
                true);
    }

    private FarmWaypointData(
            int dataVersion,
            int radius,
            int farmlandCount,
            Integer openSlots,
            List<FarmCropCount> crops,
            String plantingRestrictionItemId,
            JsonObject extras,
            JsonObject unsupportedRawSource,
            boolean validateReadable) {
        this.dataVersion = dataVersion;
        if (validateReadable) {
            validateRadius(radius);
            validateFarmlandCount(farmlandCount);
        }
        this.radius = radius;
        this.farmlandCount = farmlandCount;
        this.crops = normalizeCrops(crops, validateReadable);

        if (validateReadable && dataVersion == DATA_VERSION) {
            if (openSlots == null) {
                throw new IllegalArgumentException("openSlots is required for farm data v2");
            }
            validateOpenSlots(openSlots);
            if (openSlots > farmlandCount) {
                throw new IllegalArgumentException(
                        "open slots cannot exceed observed farmland");
            }
            if (openSlots + totalCropCount(this.crops) > MAX_FARMLAND_BLOCKS) {
                throw new IllegalArgumentException(
                        "open slots plus recognized crops cannot exceed 80");
            }
            if (plantingRestrictionItemId != null) {
                plantingRestrictionItemId = FarmCropCount.requireCanonicalId(
                        plantingRestrictionItemId, FIELD_PLANTING_RESTRICTION);
            }
        } else if (validateReadable && dataVersion == LEGACY_DATA_VERSION) {
            if (openSlots != null || plantingRestrictionItemId != null) {
                throw new IllegalArgumentException(
                        "legacy farm data cannot expose v2 planting metadata");
            }
        }
        this.openSlots = openSlots;
        this.plantingRestrictionItemId = plantingRestrictionItemId;
        this.extras = extras == null ? new JsonObject() : extras.deepCopy();
        this.unsupportedRawSource = unsupportedRawSource == null
                ? null
                : unsupportedRawSource.deepCopy();
    }

    public int dataVersion() {
        return dataVersion;
    }

    public int radius() {
        return radius;
    }

    public int farmlandCount() {
        return farmlandCount;
    }

    /** Empty for readable legacy v1 and malformed/unsupported data without a safe value. */
    public OptionalInt openSlots() {
        return openSlots == null ? OptionalInt.empty() : OptionalInt.of(openSlots);
    }

    public List<FarmCropCount> crops() {
        return crops;
    }

    /** Absence permits mixed future bot planting. */
    public Optional<String> plantingRestrictionItemId() {
        return Optional.ofNullable(plantingRestrictionItemId);
    }

    /** Returns a defensive deep copy of the unknown readable-version nested members. */
    public JsonObject extras() {
        return extras.deepCopy();
    }

    /** Readable versions may be observed and safely refreshed into current v2 data. */
    public boolean isSupportedVersion() {
        return dataVersion == LEGACY_DATA_VERSION || dataVersion == DATA_VERSION;
    }

    public boolean hasCurrentPlantingMetadata() {
        return dataVersion == DATA_VERSION && openSlots != null;
    }

    public boolean acceptsPlantingItem(String canonicalItemId) {
        String checked = FarmCropCount.requireCanonicalId(canonicalItemId, "plantingItemId");
        return isSupportedVersion()
                && (plantingRestrictionItemId == null
                || plantingRestrictionItemId.equals(checked));
    }

    /** Explicit parser used by {@link WaypointRecord}; malformed readable data is rejected. */
    public static FarmWaypointData fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("farm waypoint data must be a JSON object");
        }
        JsonObject source = element.getAsJsonObject().deepCopy();
        int version = requiredInt(source, FIELD_DATA_VERSION);

        if (version == LEGACY_DATA_VERSION) {
            JsonObject extras = source.deepCopy();
            removeFields(extras,
                    FIELD_DATA_VERSION, FIELD_RADIUS, FIELD_FARMLAND_COUNT, FIELD_CROPS);
            return new FarmWaypointData(
                    version,
                    requiredInt(source, FIELD_RADIUS),
                    requiredInt(source, FIELD_FARMLAND_COUNT),
                    null,
                    parseSupportedCrops(source.get(FIELD_CROPS)),
                    null,
                    extras,
                    null,
                    true);
        }

        if (version == DATA_VERSION) {
            JsonObject extras = removeKnownCurrentFields(source);
            return new FarmWaypointData(
                    version,
                    requiredInt(source, FIELD_RADIUS),
                    requiredInt(source, FIELD_FARMLAND_COUNT),
                    requiredInt(source, FIELD_OPEN_SLOTS),
                    parseSupportedCrops(source.get(FIELD_CROPS)),
                    nullableString(source, FIELD_PLANTING_RESTRICTION),
                    extras,
                    null,
                    true);
        }

        Integer bestEffortOpenSlots = optionalInteger(source, FIELD_OPEN_SLOTS);
        return new FarmWaypointData(
                version,
                optionalInt(source, FIELD_RADIUS, 0),
                optionalInt(source, FIELD_FARMLAND_COUNT, 0),
                bestEffortOpenSlots,
                parseCropsBestEffort(source.get(FIELD_CROPS)),
                optionalString(source, FIELD_PLANTING_RESTRICTION),
                new JsonObject(),
                source,
                false);
    }

    /** Serializes readable data explicitly while retaining unknown nested members. */
    public JsonObject toJson() {
        if (!isSupportedVersion() && unsupportedRawSource != null) {
            return unsupportedRawSource.deepCopy();
        }

        JsonObject out = extras.deepCopy();
        out.addProperty(FIELD_DATA_VERSION, dataVersion);
        out.addProperty(FIELD_RADIUS, radius);
        out.addProperty(FIELD_FARMLAND_COUNT, farmlandCount);
        if (dataVersion == DATA_VERSION) {
            out.addProperty(FIELD_OPEN_SLOTS, openSlots);
            if (plantingRestrictionItemId != null) {
                out.addProperty(FIELD_PLANTING_RESTRICTION, plantingRestrictionItemId);
            } else {
                out.remove(FIELD_PLANTING_RESTRICTION);
            }
        }
        JsonArray cropArray = new JsonArray();
        for (FarmCropCount crop : crops) {
            JsonObject entry = new JsonObject();
            entry.addProperty("blockId", crop.blockId());
            if (crop.plantingItemId() != null) {
                entry.addProperty("plantingItemId", crop.plantingItemId());
            }
            entry.addProperty("count", crop.count());
            cropArray.add(entry);
        }
        out.add(FIELD_CROPS, cropArray);
        return out;
    }

    FarmWaypointData copy() {
        return fromJson(toJson());
    }

    private static List<FarmCropCount> parseSupportedCrops(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("farm crops must be a JSON array");
        }
        ArrayList<FarmCropCount> parsed = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("farm crop entry must be a JSON object");
            }
            JsonObject crop = value.getAsJsonObject();
            parsed.add(new FarmCropCount(
                    requiredString(crop, "blockId"),
                    nullableString(crop, "plantingItemId"),
                    requiredInt(crop, "count")));
        }
        return parsed;
    }

    private static List<FarmCropCount> parseCropsBestEffort(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        ArrayList<FarmCropCount> parsed = new ArrayList<>();
        int aggregate = 0;
        for (JsonElement value : element.getAsJsonArray()) {
            try {
                if (!value.isJsonObject()) {
                    continue;
                }
                JsonObject crop = value.getAsJsonObject();
                FarmCropCount entry = new FarmCropCount(
                        requiredString(crop, "blockId"),
                        nullableString(crop, "plantingItemId"),
                        requiredInt(crop, "count"));
                if (aggregate + entry.count() > MAX_FARMLAND_BLOCKS) {
                    break;
                }
                aggregate += entry.count();
                parsed.add(entry);
            } catch (RuntimeException ignored) {
                // Unsupported versions are preserved verbatim; accessors expose only safe entries.
            }
        }
        return parsed;
    }

    private static List<FarmCropCount> normalizeCrops(
            List<FarmCropCount> input,
            boolean validateAggregate) {
        Objects.requireNonNull(input, "crops");
        Map<CropKey, Integer> merged = new LinkedHashMap<>();
        int aggregate = 0;
        for (FarmCropCount crop : input) {
            FarmCropCount checked = Objects.requireNonNull(crop, "crop");
            if (validateAggregate) {
                aggregate = Math.addExact(aggregate, checked.count());
                if (aggregate > MAX_FARMLAND_BLOCKS) {
                    throw new IllegalArgumentException("aggregate crop count cannot exceed 80");
                }
            }
            CropKey key = new CropKey(checked.blockId(), checked.plantingItemId());
            merged.merge(key, checked.count(), Math::addExact);
        }

        ArrayList<FarmCropCount> normalized = new ArrayList<>(merged.size());
        for (Map.Entry<CropKey, Integer> entry : merged.entrySet()) {
            int count = entry.getValue();
            if (count < 1 || count > MAX_FARMLAND_BLOCKS) {
                if (validateAggregate) {
                    throw new IllegalArgumentException("merged crop count cannot exceed 80");
                }
                continue;
            }
            normalized.add(new FarmCropCount(
                    entry.getKey().blockId,
                    entry.getKey().plantingItemId,
                    count));
        }
        normalized.sort(Comparator.comparing(FarmCropCount::blockId)
                .thenComparing(c -> c.plantingItemId() == null ? "" : c.plantingItemId()));
        return List.copyOf(normalized);
    }

    private static int totalCropCount(List<FarmCropCount> crops) {
        int total = 0;
        for (FarmCropCount crop : crops) {
            total = Math.addExact(total, crop.count());
        }
        return total;
    }

    private static void validateRadius(int radius) {
        if (radius < 0 || radius > MAX_HYDRATION_RADIUS) {
            throw new IllegalArgumentException("radius must be between 0 and 4");
        }
    }

    private static void validateFarmlandCount(int farmlandCount) {
        if (farmlandCount < 0 || farmlandCount > MAX_FARMLAND_BLOCKS) {
            throw new IllegalArgumentException("farmlandCount must be between 0 and 80");
        }
    }

    private static void validateOpenSlots(int openSlots) {
        if (openSlots < 0 || openSlots > MAX_FARMLAND_BLOCKS) {
            throw new IllegalArgumentException("openSlots must be between 0 and 80");
        }
    }

    private static JsonObject removeKnownCurrentFields(JsonObject source) {
        JsonObject extras = source == null ? new JsonObject() : source.deepCopy();
        removeFields(extras,
                FIELD_DATA_VERSION,
                FIELD_RADIUS,
                FIELD_FARMLAND_COUNT,
                FIELD_OPEN_SLOTS,
                FIELD_CROPS,
                FIELD_PLANTING_RESTRICTION);
        return extras;
    }

    private static void removeFields(JsonObject object, String... fields) {
        for (String field : fields) {
            object.remove(field);
        }
    }

    private static int requiredInt(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing farm data field: " + field);
        }
        try {
            return object.get(field).getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid farm data integer: " + field, e);
        }
    }

    private static int optionalInt(JsonObject object, String field, int fallback) {
        Integer value = optionalInteger(object, field);
        return value == null ? fallback : value;
    }

    private static Integer optionalInteger(JsonObject object, String field) {
        try {
            return object.has(field) && !object.get(field).isJsonNull()
                    ? object.get(field).getAsInt()
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String requiredString(JsonObject object, String field) {
        String value = nullableString(object, field);
        if (value == null) {
            throw new IllegalArgumentException("missing farm crop field: " + field);
        }
        return value;
    }

    private static String nullableString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid farm data string: " + field, e);
        }
    }

    private static String optionalString(JsonObject object, String field) {
        try {
            return nullableString(object, field);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record CropKey(String blockId, String plantingItemId) {}
}
