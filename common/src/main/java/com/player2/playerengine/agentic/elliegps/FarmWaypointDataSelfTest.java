package com.player2.playerengine.agentic.elliegps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.player2.playerengine.retrieval.RetrievalHit;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic executable checks for typed farm data and refresh semantics. */
public final class FarmWaypointDataSelfTest {

    private FarmWaypointDataSelfTest() {}

    public static void runAll() throws Exception {
        inventorySerializationShapeIsUnchanged();
        supportedFarmRoundTripPreservesIntegerPositionAndExtras();
        currentPlantingMetadataRoundTripsAndValidates();
        newerFarmPayloadRoundTripsVerbatim();
        observationProviderLifecycleIsScopedAndNonMutating();
        farmRefreshPreservesExtrasAndTimestamps();
        formatterAndCropDisplayBoundsAreExact();
        asyncPolishRejectsMissingPrerequisites();
    }

    private static void inventorySerializationShapeIsUnchanged() {
        JsonElement original = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "id": "minecraft:overworld|4,64,8",
                  "type": "inventory",
                  "dimension": "minecraft:overworld",
                  "pos": [4, 64, 8],
                  "description": "double chest at (4,64,8)",
                  "keywords": ["iron", "storage"],
                  "origin": "explicit_create",
                  "stale": false,
                  "createdGameTime": 10,
                  "updatedGameTime": 12,
                  "data": {
                    "secondaryPos": [5, 64, 8],
                    "containerKind": "double_chest",
                    "snapshot": {
                      "totalSlots": 54,
                      "emptySlots": 54,
                      "items": [],
                      "gameTime": 12
                    }
                  }
                }
                """);

        WaypointRecord parsed = requireRecord(WaypointRecord.fromJson(original), "inventory fixture");
        assertTrue(parsed.inventoryData() != null, "inventory payload must remain typed");
        assertEquals(original, parsed.toJson(), "inventory JSON shape changed");
    }

    private static void supportedFarmRoundTripPreservesIntegerPositionAndExtras() {
        JsonElement original = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "id": "minecraft:overworld|9,63,-2",
                  "type": "farm",
                  "dimension": "minecraft:overworld",
                  "pos": [9, 63, -2],
                  "description": "farm fixture",
                  "keywords": ["farm", "crops"],
                  "origin": "bot_placed",
                  "stale": false,
                  "createdGameTime": 100,
                  "updatedGameTime": 100,
                  "futureEnvelope": {"owner": "ellie"},
                  "data": {
                    "dataVersion": 1,
                    "radius": 4,
                    "farmlandCount": 80,
                    "crops": [
                      {"blockId": "minecraft:wheat", "plantingItemId": "minecraft:wheat_seeds", "count": 2},
                      {"blockId": "example:turnips", "plantingItemId": "example:turnip_seeds", "count": 3},
                      {"blockId": "minecraft:wheat", "plantingItemId": "minecraft:wheat_seeds", "count": 4}
                    ],
                    "futureNested": {"scanner": 7}
                  }
                }
                """);

        WaypointRecord parsed = requireRecord(WaypointRecord.fromJson(original), "supported farm fixture");
        FarmWaypointData farm = requireFarm(parsed);
        assertTrue(farm.isSupportedVersion(), "v1 farm payload must be supported");
        assertEquals(FarmWaypointData.LEGACY_DATA_VERSION, farm.dataVersion(),
                "legacy farm payload changed version during a read-only round trip");
        assertTrue(farm.openSlots().isEmpty(),
                "legacy v1 farm fabricated an authoritative open-slot count");
        assertTrue(farm.plantingRestrictionItemId().isEmpty(),
                "legacy v1 farm fabricated a planting restriction");
        assertEquals(2, farm.crops().size(), "duplicate crop rows must merge deterministically");
        assertEquals("example:turnips", farm.crops().get(0).blockId(),
                "modded crop ids must remain supported and sorted");
        assertEquals(6, farm.crops().get(1).count(), "merged wheat count is wrong");

        JsonObject roundTrip = parsed.toJson().getAsJsonObject();
        JsonArray pos = roundTrip.getAsJsonArray("pos");
        assertEquals("9", pos.get(0).getAsString(), "farm x position must serialize as an integer");
        assertEquals("63", pos.get(1).getAsString(), "farm y position must serialize as an integer");
        assertEquals("-2", pos.get(2).getAsString(), "farm z position must serialize as an integer");
        assertEquals(
                original.getAsJsonObject().get("futureEnvelope"),
                roundTrip.get("futureEnvelope"),
                "unknown farm envelope data was lost");
        assertEquals(
                original.getAsJsonObject().getAsJsonObject("data").get("futureNested"),
                roundTrip.getAsJsonObject("data").get("futureNested"),
                "unknown supported farm payload data was lost");
    }

    private static void currentPlantingMetadataRoundTripsAndValidates() {
        JsonObject current = JsonParser.parseString("""
                {
                  "dataVersion": 2,
                  "radius": 4,
                  "farmlandCount": 80,
                  "openSlots": 47,
                  "crops": [
                    {"blockId": "minecraft:carrots", "plantingItemId": "minecraft:carrot", "count": 23},
                    {"blockId": "minecraft:wheat", "plantingItemId": "minecraft:wheat_seeds", "count": 10}
                  ],
                  "plantingRestrictionItemId": "minecraft:carrot",
                  "futureNested": {"keep": true}
                }
                """).getAsJsonObject();
        FarmWaypointData parsed = FarmWaypointData.fromJson(current);
        assertTrue(parsed.isSupportedVersion(), "v2 farm payload must be supported");
        assertTrue(parsed.hasCurrentPlantingMetadata(),
                "v2 farm payload did not expose current planting metadata");
        assertEquals(47, parsed.openSlots().orElseThrow(), "v2 open-slot count");
        assertEquals("minecraft:carrot", parsed.plantingRestrictionItemId().orElseThrow(),
                "v2 planting restriction");
        assertTrue(parsed.acceptsPlantingItem("minecraft:carrot"),
                "matching planting restriction was rejected");
        assertTrue(!parsed.acceptsPlantingItem("minecraft:wheat_seeds"),
                "nonmatching planting restriction was accepted");
        assertEquals(current, parsed.toJson(), "v2 farm payload changed on round trip");

        assertThrows(IllegalArgumentException.class,
                () -> new FarmWaypointData(4, 80, -1, List.of()),
                "negative v2 open slots were accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new FarmWaypointData(4, 12, 13, List.of()),
                "v2 open slots above observed farmland were accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new FarmWaypointData(
                        4,
                        80,
                        71,
                        List.of(new FarmCropCount(
                                "minecraft:wheat", "minecraft:wheat_seeds", 10))),
                "open slots plus crops above 80 were accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new FarmWaypointData(4, 80, 80, List.of(), "not canonical"),
                "noncanonical v2 planting restriction was accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new FarmPlantingRestrictionUpdate(
                        FarmPlantingRestrictionUpdate.Kind.PRESERVE, "minecraft:carrot"),
                "preserve update carrying a planting item was accepted");
        assertThrows(IllegalArgumentException.class,
                () -> FarmPlantingRestrictionUpdate.set("not canonical"),
                "set update accepted a noncanonical planting item");
        assertThrows(IllegalArgumentException.class,
                () -> FarmWaypointData.fromJson(JsonParser.parseString("""
                        {
                          "dataVersion": 2,
                          "radius": 4,
                          "farmlandCount": 80,
                          "crops": []
                        }
                        """)),
                "v2 payload without openSlots was accepted");
    }

    private static void newerFarmPayloadRoundTripsVerbatim() {
        JsonObject newerPayload = JsonParser.parseString("""
                {
                  "dataVersion": 3,
                  "radius": 19,
                  "farmlandCount": 999,
                  "crops": [{"blockId": "example:future_crop", "count": 500}],
                  "layout": {"shape": "hex", "revision": 3}
                }
                """).getAsJsonObject();
        FarmWaypointData parsedPayload = FarmWaypointData.fromJson(newerPayload);
        assertTrue(!parsedPayload.isSupportedVersion(), "v3 farm payload must remain unsupported");
        assertEquals(newerPayload, parsedPayload.toJson(), "newer farm payload was rewritten");

        JsonObject envelope = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "id": "minecraft:overworld|1,2,3",
                  "type": "farm",
                  "dimension": "minecraft:overworld",
                  "pos": [1, 2, 3],
                  "description": "future farm",
                  "keywords": [],
                  "origin": "future_scanner",
                  "stale": false,
                  "createdGameTime": 1,
                  "updatedGameTime": 2,
                  "futureEnvelope": true,
                  "data": {}
                }
                """).getAsJsonObject();
        envelope.add("data", newerPayload.deepCopy());
        WaypointRecord record = requireRecord(WaypointRecord.fromJson(envelope), "newer farm envelope");
        assertEquals(envelope, record.toJson(), "newer farm record did not round-trip verbatim");
    }

    private static void observationProviderLifecycleIsScopedAndNonMutating() throws Exception {
        WaypointRecord farm = testFarmRecord(new BlockPos(7, 63, 7), List.of(
                new FarmCropCount("minecraft:wheat", "minecraft:wheat_seeds", 8)));
        JsonElement beforeObservation = farm.toJson().deepCopy();

        FarmObservationResult defaultResult = FarmWaypointService.observe(null, farm);
        assertEquals(FarmObservationStatus.HANDLER_UNAVAILABLE, defaultResult.status(),
                "default farm observation status");
        assertEquals(beforeObservation, farm.toJson(),
                "default farm observation mutated the supplied record");

        FarmWaypointObservationProvider production = (controller, defensiveFarm) -> {
            defensiveFarm.description = "provider-mutated-copy";
            return new FarmObservationResult(
                    FarmObservationStatus.UNLOADED, null, "production-provider");
        };
        FarmWaypointObservationProvider differentProduction = (controller, defensiveFarm) ->
                new FarmObservationResult(
                        FarmObservationStatus.STALE_CENTER, null, "different-production-provider");

        FarmWaypointService.installProductionObservationProvider(production);
        FarmWaypointService.installProductionObservationProvider(production);
        assertThrows(
                IllegalStateException.class,
                () -> FarmWaypointService.installProductionObservationProvider(differentProduction),
                "different second production provider was accepted");

        FarmObservationResult productionResult = FarmWaypointService.observe(null, farm);
        assertEquals(FarmObservationStatus.UNLOADED, productionResult.status(),
                "installed production provider was not selected");
        assertEquals("production-provider", productionResult.controlledReason(),
                "installed production provider returned the wrong result");
        assertEquals(beforeObservation, farm.toJson(),
                "production provider mutated the caller's farm instead of its defensive copy");

        FarmWaypointObservationProvider outerProvider = (controller, defensiveFarm) ->
                new FarmObservationResult(FarmObservationStatus.STALE_CENTER, null, "outer-override");
        FarmWaypointObservationProvider innerProvider = (controller, defensiveFarm) ->
                new FarmObservationResult(FarmObservationStatus.HANDLER_UNAVAILABLE, null, "inner-override");
        AutoCloseable outer = FarmWaypointService.overrideObservationProviderForTest(outerProvider);
        AutoCloseable inner = FarmWaypointService.overrideObservationProviderForTest(innerProvider);
        boolean innerClosed = false;
        boolean outerClosed = false;
        try {
            assertEquals("inner-override", FarmWaypointService.observe(null, farm).controlledReason(),
                    "innermost provider override did not take precedence");
            assertThrows(IllegalStateException.class, outer::close,
                    "provider overrides allowed out-of-order close");
            assertEquals("inner-override", FarmWaypointService.observe(null, farm).controlledReason(),
                    "failed out-of-order close changed effective provider");

            inner.close();
            innerClosed = true;
            assertEquals("outer-override", FarmWaypointService.observe(null, farm).controlledReason(),
                    "closing inner override did not restore outer override");

            outer.close();
            outerClosed = true;
            assertEquals("production-provider", FarmWaypointService.observe(null, farm).controlledReason(),
                    "closing all overrides did not restore production provider");
        } finally {
            if (!innerClosed) {
                inner.close();
            }
            if (!outerClosed) {
                outer.close();
            }
        }
    }

    private static void farmRefreshPreservesExtrasAndTimestamps() throws Exception {
        RecordingPersistence persistence = new RecordingPersistence();
        HealthyIndex index = new HealthyIndex();
        EllieGPSStore store = new EllieGPSStore(
                Path.of("build", "farming-self-test", "farm-refresh"), persistence, index);
        WaypointRecord existing = requireRecord(WaypointRecord.fromJson(JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "id": "legacy-farm-id",
                  "type": "farm",
                  "dimension": "minecraft:overworld",
                  "pos": [0, 63, 0],
                  "description": "old description",
                  "keywords": ["farm"],
                  "origin": "explicit_create",
                  "stale": false,
                  "createdGameTime": 10,
                  "updatedGameTime": 10,
                  "futureEnvelope": "keep-me",
                  "data": {
                    "dataVersion": 1,
                    "radius": 2,
                    "farmlandCount": 20,
                    "crops": [{"blockId": "minecraft:wheat", "plantingItemId": "minecraft:wheat_seeds", "count": 5}],
                    "futureNested": {"keep": true}
                  }
                }
                """)), "existing farm");
        injectLegacyRecord(store, existing);
        assertTrue(store.hasTypeAtPosition(
                        WaypointTypes.FARM, "minecraft:overworld", new BlockPos(0, 63, 0)),
                "farm-center guard missed a loaded legacy-id record before refresh");

        FarmWaypointObservation changed = new FarmWaypointObservation(
                "minecraft:overworld",
                 new BlockPos(0, 63, 0),
                 4,
                 80,
                 48,
                 List.of(
                        new FarmCropCount("minecraft:carrots", "minecraft:carrot", 12),
                        new FarmCropCount("minecraft:wheat", "minecraft:wheat_seeds", 20)),
                20L);

        try (AutoCloseable ignored = EllieGPSStore.overrideForTest(store)) {
            WaypointMutationResult preparedResult = FarmWaypointService.registerPrepared(
                    changed, "bot_placed");
            assertStatus(WaypointMutationStatus.COMMITTED, preparedResult, "prepared farm refresh");
            WaypointRecord prepared = requireRecord(
                    preparedResult.current(), "prepared refresh result");
            assertTrue(prepared.stale,
                    "prepared farm marker was exposed as a completed non-stale farm");
            assertEquals("minecraft:overworld|0,63,0", prepared.id,
                    "prepared refresh did not canonicalize the farm id");
            assertEquals(1, store.all().size(),
                    "prepared refresh left a duplicate record at the farm center");
            assertTrue(store.all().stream().noneMatch(record -> "legacy-farm-id".equals(record.id)),
                    "prepared refresh retained the superseded noncanonical farm id");
            assertTrue(store.hasTypeAtPosition(
                            WaypointTypes.FARM, "minecraft:overworld", new BlockPos(0, 63, 0)),
                    "constant-time farm-center guard did not see the canonical record");
            assertTrue(!store.hasTypeAtPosition(
                            WaypointTypes.INVENTORY, "minecraft:overworld", new BlockPos(0, 63, 0)),
                    "constant-time farm-center guard ignored the requested type");
            assertEquals(10L, prepared.createdGameTime,
                    "prepared refresh changed the creation timestamp");
            assertEquals(20L, prepared.updatedGameTime,
                    "prepared refresh did not advance the update timestamp");
            assertEquals("explicit_create", prepared.origin,
                    "prepared refresh changed the original origin");
            FarmWaypointData preparedFarm = requireFarm(prepared);
            assertEquals(FarmWaypointData.DATA_VERSION, preparedFarm.dataVersion(),
                    "live refresh did not migrate legacy farm data to v2");
            assertEquals(48, preparedFarm.openSlots().orElseThrow(),
                    "prepared refresh stored the wrong open capacity");
            assertTrue(preparedFarm.plantingRestrictionItemId().isEmpty(),
                    "prepared refresh fabricated a planting restriction");
            assertTrue(prepared.description.contains("48 open"),
                    "prepared farm description omitted exact open capacity");
            JsonObject preparedJson = prepared.toJson().getAsJsonObject();
            assertEquals("keep-me", preparedJson.get("futureEnvelope").getAsString(),
                    "prepared refresh lost unknown envelope data");
            assertTrue(preparedJson.getAsJsonObject("data")
                            .getAsJsonObject("futureNested").get("keep").getAsBoolean(),
                    "prepared refresh lost unknown nested farm data");

            WaypointRecord lookedUp = requireRecord(store.byId(prepared.id),
                    "prepared farm authoritative id lookup");
            assertEquals(prepared.id, lookedUp.id,
                    "authoritative id lookup returned a different farm identity");
            lookedUp.stale = false;
            lookedUp.description = "caller-mutated defensive copy";
            WaypointRecord authoritativePrepared = requireRecord(store.byId(prepared.id),
                    "prepared farm after caller mutation");
            assertTrue(authoritativePrepared.stale,
                    "caller mutated the authoritative prepared stale state through byId");
            assertTrue(!"caller-mutated defensive copy".equals(authoritativePrepared.description),
                    "caller mutated the authoritative prepared description through byId");
            assertTrue(store.byId("minecraft:overworld|99,99,99") == null,
                    "authoritative id lookup returned a record for a missing id");

            FarmWaypointObservation completedObservation = new FarmWaypointObservation(
                    changed.dimension(),
                     changed.center(),
                     changed.radius(),
                     changed.farmlandCount(),
                     changed.openSlots(),
                     changed.crops(),
                    30L);
            WaypointMutationResult completedResult = FarmWaypointService.registerOrRefresh(
                    completedObservation, "ignored_on_refresh");
            assertStatus(WaypointMutationStatus.COMMITTED, completedResult,
                    "prepared farm final promotion");
            WaypointRecord completed = requireRecord(
                    completedResult.current(), "completed farm promotion result");
            assertTrue(!completed.stale,
                    "verified final refresh did not clear the prepared stale state");
            assertEquals(prepared.id, completed.id,
                    "verified final refresh changed the prepared farm identity");
            assertEquals(10L, completed.createdGameTime,
                    "verified final refresh changed the original creation timestamp");
            assertEquals(30L, completed.updatedGameTime,
                    "verified final refresh did not advance the update timestamp");
            assertEquals("explicit_create", completed.origin,
                    "verified final refresh changed the original origin");
            JsonObject completedJson = completed.toJson().getAsJsonObject();
            assertEquals("keep-me", completedJson.get("futureEnvelope").getAsString(),
                    "verified final refresh lost unknown envelope data");
            assertTrue(completedJson.getAsJsonObject("data")
                            .getAsJsonObject("futureNested").get("keep").getAsBoolean(),
                    "verified final refresh lost unknown nested farm data");

            int writesBeforeNoChange = persistence.writeCount;
            FarmWaypointObservation sameContentLater = new FarmWaypointObservation(
                    completedObservation.dimension(),
                     completedObservation.center(),
                     completedObservation.radius(),
                     completedObservation.farmlandCount(),
                     completedObservation.openSlots(),
                     completedObservation.crops(),
                    40L);
            WaypointMutationResult noChange = FarmWaypointService.registerOrRefresh(
                    sameContentLater, "ignored_on_refresh");
            assertStatus(WaypointMutationStatus.NO_CHANGE, noChange, "semantic no-change refresh");
            assertEquals(writesBeforeNoChange, persistence.writeCount,
                    "semantic no-change refresh rewrote authoritative JSON");
            WaypointRecord authoritative = requireRecord(
                    store.byPosition("minecraft:overworld", new BlockPos(0, 63, 0)),
                    "authoritative farm after no-change");
            assertEquals(10L, authoritative.createdGameTime,
                    "semantic no-change refresh changed creation time");
            assertEquals(30L, authoritative.updatedGameTime,
                    "semantic no-change refresh published scan time alone");

            FarmWaypointObservation policyObservation = new FarmWaypointObservation(
                    completedObservation.dimension(),
                    completedObservation.center(),
                    completedObservation.radius(),
                    completedObservation.farmlandCount(),
                    completedObservation.openSlots(),
                    completedObservation.crops(),
                    50L);
            WaypointMutationResult restrictedResult = FarmWaypointService.registerOrRefresh(
                    policyObservation,
                    "ignored_on_refresh",
                    FarmPlantingRestrictionUpdate.set("minecraft:carrot"));
            assertStatus(WaypointMutationStatus.COMMITTED, restrictedResult,
                    "explicit planting restriction update");
            WaypointRecord restricted = requireRecord(
                    restrictedResult.current(), "restricted farm result");
            assertEquals("minecraft:carrot",
                    requireFarm(restricted).plantingRestrictionItemId().orElseThrow(),
                    "explicit planting restriction was not stored");
            assertTrue(restricted.description.contains("bot planting restricted to minecraft:carrot"),
                    "farm description omitted the planting restriction");
            assertTrue(restricted.keywords.contains("planting restricted")
                            && restricted.keywords.contains("minecraft:carrot"),
                    "farm keywords omitted the planting restriction");

            FarmWaypointObservation ordinaryRefresh = new FarmWaypointObservation(
                    policyObservation.dimension(),
                    policyObservation.center(),
                    policyObservation.radius(),
                    policyObservation.farmlandCount(),
                    policyObservation.openSlots(),
                    policyObservation.crops(),
                    60L);
            WaypointMutationResult preservedResult = FarmWaypointService.registerOrRefresh(
                    ordinaryRefresh, "ignored_on_refresh");
            assertStatus(WaypointMutationStatus.NO_CHANGE, preservedResult,
                    "ordinary refresh preserving planting restriction");
            assertEquals("minecraft:carrot",
                    requireFarm(requireRecord(preservedResult.current(), "preserved farm"))
                            .plantingRestrictionItemId().orElseThrow(),
                    "ordinary refresh cleared the planting restriction");

            FarmWaypointObservation clearObservation = new FarmWaypointObservation(
                    ordinaryRefresh.dimension(),
                    ordinaryRefresh.center(),
                    ordinaryRefresh.radius(),
                    ordinaryRefresh.farmlandCount(),
                    ordinaryRefresh.openSlots(),
                    ordinaryRefresh.crops(),
                    70L);
            WaypointMutationResult clearedResult = FarmWaypointService.registerOrRefresh(
                    clearObservation,
                    "ignored_on_refresh",
                    FarmPlantingRestrictionUpdate.clear());
            assertStatus(WaypointMutationStatus.COMMITTED, clearedResult,
                    "explicit planting restriction clear");
            assertTrue(requireFarm(requireRecord(clearedResult.current(), "cleared farm"))
                            .plantingRestrictionItemId().isEmpty(),
                    "explicit clear retained the planting restriction");
        }
    }

    private static void formatterAndCropDisplayBoundsAreExact() throws Exception {
        String oversized = "x".repeat(800);
        assertEquals(WaypointReportFormatter.MAX_MODEL_LENGTH,
                WaypointReportFormatter.boundModel(oversized).length(),
                "model feedback cap");
        assertEquals(512, WaypointReportFormatter.MAX_MODEL_LENGTH,
                "model feedback contract changed");
        assertEquals(WaypointReportFormatter.MAX_REASON_LENGTH,
                WaypointReportFormatter.boundReason(oversized).length(),
                "controlled reason cap");
        assertEquals(160, WaypointReportFormatter.MAX_REASON_LENGTH,
                "controlled reason contract changed");
        assertEquals(WaypointReportFormatter.MAX_IDENTIFIER_LENGTH,
                WaypointReportFormatter.boundIdentifier(oversized).length(),
                "identifier cap");
        assertEquals(96, WaypointReportFormatter.MAX_IDENTIFIER_LENGTH,
                "identifier contract changed");
        FarmObservationResult boundedResult = new FarmObservationResult(
                FarmObservationStatus.HANDLER_UNAVAILABLE, null, oversized);
        assertEquals(160, boundedResult.controlledReason().length(),
                "farm observation reason bypassed the 160-character cap");

        ArrayList<FarmCropCount> crops = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            String suffix = i < 10 ? "0" + i : Integer.toString(i);
            crops.add(new FarmCropCount(
                    "example:crop_" + suffix,
                    "example:seed_" + suffix,
                    1));
        }
        RecordingPersistence persistence = new RecordingPersistence();
        EllieGPSStore store = new EllieGPSStore(
                Path.of("build", "farming-self-test", "crop-display-cap"),
                persistence,
                new HealthyIndex());
        FarmWaypointObservation observation = new FarmWaypointObservation(
                "minecraft:overworld", new BlockPos(11, 63, 11), 4, 13, 0, crops, 50L);
        try (AutoCloseable ignored = EllieGPSStore.overrideForTest(store)) {
            WaypointMutationResult mutation = FarmWaypointService.registerOrRefresh(
                    observation, WaypointRecord.ORIGIN_BOT_PLACED);
            assertStatus(WaypointMutationStatus.COMMITTED, mutation, "crop-display farm registration");
            String description = requireRecord(mutation.current(), "crop-display result").description;
            assertEquals(12, countOccurrences(description, "example:crop_"),
                    "farm description displayed more than 12 crop types");
            assertTrue(description.contains("+1 more crop types"),
                    "farm description omitted its truncated-crop summary");
            assertTrue(!description.contains("example:crop_12"),
                    "thirteenth crop leaked past the display cap");
        }
    }

    private static void asyncPolishRejectsMissingPrerequisites() {
        WaypointRecord inventory = new WaypointRecord();
        inventory.type = WaypointTypes.INVENTORY;
        assertTrue(!WaypointIngestionService.schedulePolishAfterCommit(null, inventory),
                "description polish scheduled without a controller");
        assertTrue(!WaypointIngestionService.schedulePolishAfterCommit(null, null),
                "description polish scheduled without a committed record");
    }

    private static WaypointRecord testFarmRecord(BlockPos center, List<FarmCropCount> crops) {
        WaypointRecord record = new WaypointRecord();
        record.id = WaypointRecord.idFor("minecraft:overworld", center);
        record.type = WaypointTypes.FARM;
        record.dimension = "minecraft:overworld";
        record.pos = new int[]{center.getX(), center.getY(), center.getZ()};
        record.description = "provider lifecycle farm";
        record.keywords = List.of("farm", "crops");
        record.origin = WaypointRecord.ORIGIN_BOT_PLACED;
        record.createdGameTime = 1L;
        record.updatedGameTime = 1L;
        record.data = new FarmWaypointData(4, 80, 0, crops);
        return record;
    }

    private static int countOccurrences(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void injectLegacyRecord(EllieGPSStore store, WaypointRecord record)
            throws Exception {
        java.lang.reflect.Field recordsField = EllieGPSStore.class.getDeclaredField("records");
        recordsField.setAccessible(true);
        ((java.util.Map<String, WaypointRecord>) recordsField.get(store))
                .put(record.id, record.copy());
        java.lang.reflect.Method publish = EllieGPSStore.class
                .getDeclaredMethod("publishCurrentSnapshot");
        publish.setAccessible(true);
        publish.invoke(store);
    }

    private static WaypointRecord requireRecord(WaypointRecord record, String label) {
        if (record == null) {
            throw new AssertionError(label + " was null");
        }
        return record;
    }

    private static FarmWaypointData requireFarm(WaypointRecord record) {
        FarmWaypointData farm = record.farmData();
        if (farm == null) {
            throw new AssertionError("farm payload was not typed");
        }
        return farm;
    }

    private static void assertStatus(
            WaypointMutationStatus expected,
            WaypointMutationResult actual,
            String label) {
        assertEquals(expected, actual.status(), label + " status");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception=" + actual, actual);
        }
        throw new AssertionError(message + ": no exception thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RecordingPersistence implements WaypointPersistenceBackend {
        private int writeCount;

        @Override
        public void persist(Path storeFile, String serializedStore) throws IOException {
            writeCount++;
        }
    }

    private static final class HealthyIndex implements WaypointIndexBackend {
        @Override
        public boolean isHealthyFor(String expectedVersionToken) {
            return true;
        }

        @Override
        public WaypointIndexUpdateStatus synchronize(EllieGPSStore store) {
            return WaypointIndexUpdateStatus.INDEX_COMMITTED;
        }

        @Override
        public List<RetrievalHit> query(String query, int limit) {
            return List.of();
        }
    }
}
