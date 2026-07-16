package com.player2.playerengine.agentic.elliegps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure regression checks for the pre-copy EllieGPS authoritative snapshot bound. */
public final class EllieGPSBoundedSnapshotSelfTest {
    private EllieGPSBoundedSnapshotSelfTest() {
    }

    public static void runAll() {
        completeSnapshotsAreDefensive();
        oversizedSnapshotsRefuseBeforeCopying();
        unavailableAndInvalidRequestsFailClosed();
    }

    private static void completeSnapshotsAreDefensive() {
        WaypointRecord sourceRecord = record("one", 4);
        Map<String, WaypointRecord> source = new LinkedHashMap<>();
        source.put(sourceRecord.id, sourceRecord);

        EllieGPSStore.BoundedSnapshot snapshot = EllieGPSStore.boundedSnapshot(source, 1);
        require(snapshot.available() && snapshot.complete() && snapshot.records().size() == 1,
                "a snapshot at the hard bound is available and complete");
        WaypointRecord copied = snapshot.records().get(0);
        require(copied != sourceRecord && copied.pos != sourceRecord.pos,
                "bounded snapshots defensively copy records and position arrays");
        sourceRecord.pos[0] = 99;
        require(copied.pos[0] == 4,
                "later authoritative-record mutation cannot alter a published snapshot");
        expectThrows(
                UnsupportedOperationException.class,
                () -> snapshot.records().add(record("two", 8)),
                "the published bounded record list is immutable");
    }

    private static void oversizedSnapshotsRefuseBeforeCopying() {
        Map<String, WaypointRecord> oversized = new LinkedHashMap<>();
        oversized.put("one", record("one", 1));
        oversized.put("poison-copy", null);

        EllieGPSStore.BoundedSnapshot snapshot =
                EllieGPSStore.boundedSnapshot(oversized, 1);
        require(snapshot.available() && !snapshot.complete() && snapshot.records().isEmpty(),
                "an oversized store is rejected as incomplete before any record copy");
    }

    private static void unavailableAndInvalidRequestsFailClosed() {
        EllieGPSStore.BoundedSnapshot unavailable =
                EllieGPSStore.boundedSnapshot(null, 1);
        require(!unavailable.available()
                        && !unavailable.complete()
                        && unavailable.records().isEmpty(),
                "an unavailable authoritative source exposes no records");
        expectThrows(
                IllegalArgumentException.class,
                () -> EllieGPSStore.boundedSnapshot(Map.of(), -1),
                "negative bounds are rejected");
    }

    private static WaypointRecord record(String suffix, int x) {
        WaypointRecord record = new WaypointRecord();
        record.id = "minecraft:overworld|" + suffix;
        record.type = "bounded_snapshot_test";
        record.dimension = "minecraft:overworld";
        record.pos = new int[]{x, 64, 0};
        record.description = "bounded snapshot test";
        record.keywords = List.of();
        record.origin = "explicit_create";
        return record;
    }

    private static void expectThrows(
            Class<? extends RuntimeException> expected,
            Runnable action,
            String message) {
        try {
            action.run();
        } catch (RuntimeException actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw actual;
        }
        throw new IllegalStateException(
                "EllieGPS bounded snapshot self-test failed: " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "EllieGPS bounded snapshot self-test failed: " + message);
        }
    }
}
