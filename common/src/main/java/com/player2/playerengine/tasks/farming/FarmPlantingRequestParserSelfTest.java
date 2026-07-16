package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;

import java.util.Map;

/** Deterministic parser/policy contract checks, invoked by FarmingSelfTestSuite. */
public final class FarmPlantingRequestParserSelfTest {
    private FarmPlantingRequestParserSelfTest() {
    }

    public static void runAll() {
        orderedDuplicatesMergeIntoFirstOccurrence();
        bareVanillaIdsNormalizeToMinecraftNamespace();
        rejectsMalformedAndOverCapacityRequests();
        coordinatesAndPolicyAreAllOrNone();
    }

    private static void orderedDuplicatesMergeIntoFirstOccurrence() {
        FarmPlantingRequestParser.Parsed parsed = FarmPlantingRequestParser.parse(
                "minecraft:carrot=10,minecraft:wheat_seeds=5,minecraft:carrot=3",
                null,
                "preserve");
        require(parsed.valid(), "ordered request should parse");
        require(parsed.requests().size() == 2, "duplicate request should merge");
        require("minecraft:carrot".equals(parsed.requests().get(0).plantingItemId()),
                "first request order must survive merging");
        require(parsed.requests().get(0).count() == 13, "duplicate carrot count should merge");
        require(parsed.requests().get(1).count() == 5, "wheat count should remain second");
    }

    private static void rejectsMalformedAndOverCapacityRequests() {
        require(!FarmPlantingRequestParser.parse("bad id=1", null, null).valid(),
                "malformed item id must fail");
        require(!FarmPlantingRequestParser.parse("minecraft:=1", null, null).valid(),
                "empty item path must fail");
        require(!FarmPlantingRequestParser.parse("minecraft:carrot=0", null, null).valid(),
                "zero count must fail");
        require(!FarmPlantingRequestParser.parse(
                "minecraft:carrot=50,minecraft:wheat_seeds=31", null, null).valid(),
                "more than one farm must fail");
        require(!FarmPlantingRequestParser.parse("minecraft:carrot=1=2", null, null).valid(),
                "multiple equals signs must fail");
    }

    private static void bareVanillaIdsNormalizeToMinecraftNamespace() {
        FarmPlantingRequestParser.Parsed parsed = FarmPlantingRequestParser.parse(
                "wheat_seeds=20", new BlockPos(11, 71, -2), "preserve");
        require(parsed.valid(), "bare vanilla planting id should parse");
        require(parsed.requests().size() == 1, "bare request should retain one crop group");
        require("minecraft:wheat_seeds".equals(parsed.requests().get(0).plantingItemId()),
                "bare vanilla id must normalize to the minecraft namespace");
        require(new BlockPos(11, 71, -2).equals(parsed.exactCenter()),
                "exact farm coordinates must survive direct request parsing");
    }

    private static void coordinatesAndPolicyAreAllOrNone() {
        require(!FarmPlantingRequestParser.parseArgs(Map.of(
                "requests", "minecraft:carrot=1", "x", "1")).valid(),
                "partial coordinates must fail");
        require(!FarmPlantingRequestParser.parse(
                "minecraft:carrot=1", null, "mixed").valid(),
                "policy mutation without exact farm must fail");
        require(!FarmPlantingRequestParser.parse(
                "minecraft:carrot=1,minecraft:wheat_seeds=1",
                new BlockPos(1, 2, 3),
                "single:minecraft:carrot").valid(),
                "single policy must reject another requested item");
        FarmPlantingRequestParser.Parsed exact = FarmPlantingRequestParser.parseArgs(Map.of(
                "requests", "minecraft:carrot=3",
                "x", "1", "y", "2", "z", "3",
                "farm_policy", "single:minecraft:carrot"));
        require(exact.valid() && new BlockPos(1, 2, 3).equals(exact.exactCenter()),
                "exact matching policy should parse");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
