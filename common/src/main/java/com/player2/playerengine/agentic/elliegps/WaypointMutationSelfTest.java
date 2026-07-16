package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.retrieval.RetrievalHit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Deterministic executable checks for checked mutations, rollback, snapshots, and search. */
public final class WaypointMutationSelfTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private WaypointMutationSelfTest() {}

    public static void runAll() throws Exception {
        upsertCoversCheckedEdgesAndRollback();
        replaceCoversCheckedEdgesAndRollback();
        deleteCoversCheckedEdgesAndRollback();
        markStaleCoversCheckedEdgesAndRollback();
        storeReturnsDeepDefensiveSnapshots();
        allMutationStatusesHaveBoundedFeedbackSurfaces();
        searchFiltersBeforeFinalLimit();
        emptyQueryUsesStructuredStableOrder();
        degradedSearchUsesFullStoreFallback();
        nearestUsesThreeDimensionsAndStableIdTieBreak();
        searchFailsClosedAboveAuthoritativeCap();
        searchOrderContractsRejectInvalidOrigins();
        searchUnavailableAndCountErrorsAreExplicit();
    }

    private static void upsertCoversCheckedEdgesAndRollback() {
        Fixture fixture = fixture("upsert");
        WaypointRecord original = inventory(OVERWORLD, 1, 64, 1, "original");
        WaypointMutationResult inserted = fixture.store.upsert(original);
        assertStatus(WaypointMutationStatus.COMMITTED, inserted, "new upsert");
        assertEquals(1, fixture.persistence.writeCount, "new upsert write count");

        WaypointRecord sameExceptScanTime = original.copy();
        sameExceptScanTime.updatedGameTime = 99L;
        fixture.index.healthy = false;
        fixture.index.syncResult = WaypointIndexUpdateStatus.INDEX_COMMITTED;
        int syncsBeforeRepair = fixture.index.syncCalls;
        WaypointMutationResult repairedNoChange = fixture.store.upsert(sameExceptScanTime);
        assertStatus(WaypointMutationStatus.NO_CHANGE, repairedNoChange, "upsert no-change repair");
        assertEquals(syncsBeforeRepair + 1, fixture.index.syncCalls,
                "upsert no-change did not repair an unhealthy index");
        assertEquals(1, fixture.persistence.writeCount, "upsert no-change rewrote JSON");

        fixture.index.healthy = false;
        fixture.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        WaypointMutationResult degradedNoChange = fixture.store.upsert(sameExceptScanTime);
        assertStatus(WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED, degradedNoChange,
                "upsert no-change degraded index");

        int writesBeforeConflict = fixture.persistence.writeCount;
        WaypointMutationResult conflict = fixture.store.upsert(
                farm(OVERWORLD, 1, 64, 1, "type conflict"));
        assertStatus(WaypointMutationStatus.REJECTED_TYPE_CONFLICT, conflict,
                "upsert type conflict");
        assertEquals(writesBeforeConflict, fixture.persistence.writeCount,
                "upsert type conflict wrote JSON");

        WaypointRecord changed = original.copy();
        changed.description = "committed despite index failure";
        fixture.index.healthy = false;
        fixture.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        WaypointMutationResult degradedCommit = fixture.store.upsert(changed);
        assertStatus(WaypointMutationStatus.COMMITTED_INDEX_DEGRADED, degradedCommit,
                "upsert committed/index degraded");
        assertEquals(changed.description, fixture.store.byPosition(OVERWORLD, new BlockPos(1, 64, 1)).description,
                "degraded upsert was not authoritative");

        WaypointRecord rejectedByPersistence = changed.copy();
        rejectedByPersistence.description = "must roll back";
        fixture.persistence.failNext = true;
        int syncsBeforeFailure = fixture.index.syncCalls;
        WaypointMutationResult failed = fixture.store.upsert(rejectedByPersistence);
        assertStatus(WaypointMutationStatus.FAILED_JSON_COMMIT, failed, "upsert JSON failure");
        assertEquals(changed.description, fixture.store.byPosition(OVERWORLD, new BlockPos(1, 64, 1)).description,
                "failed upsert leaked into authoritative memory");
        assertEquals(syncsBeforeFailure, fixture.index.syncCalls,
                "failed upsert attempted to synchronize the index");
    }

    private static void replaceCoversCheckedEdgesAndRollback() {
        Fixture missing = fixture("replace-missing");
        WaypointRecord absentCandidate = inventory(OVERWORLD, 9, 64, 9, "absent");
        String missingId = WaypointRecord.idFor(OVERWORLD, new BlockPos(8, 64, 8));
        assertMutationResult(
                WaypointMutationStatus.NOT_FOUND,
                null,
                null,
                missing.store.replace(missingId, absentCandidate),
                "replace missing/healthy");
        missing.index.healthy = false;
        missing.index.syncResult = WaypointIndexUpdateStatus.INDEX_COMMITTED;
        int syncsBeforeRepair = missing.index.syncCalls;
        assertMutationResult(
                WaypointMutationStatus.NOT_FOUND,
                null,
                null,
                missing.store.replace(missingId, absentCandidate),
                "replace missing/repaired");
        assertEquals(syncsBeforeRepair + 1, missing.index.syncCalls,
                "replace not-found did not repair the index");
        missing.index.healthy = false;
        missing.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertMutationResult(
                WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                null,
                null,
                missing.store.replace(missingId, absentCandidate),
                "replace missing/degraded");

        Fixture missingWithTarget = fixture("replace-missing-with-target");
        WaypointRecord existingTarget = inventory(OVERWORLD, 9, 64, 9, "existing target");
        missingWithTarget.store.upsert(existingTarget);
        assertMutationResult(
                WaypointMutationStatus.NOT_FOUND,
                null,
                existingTarget,
                missingWithTarget.store.replace(missingId, existingTarget.copy()),
                "replace missing old/occupied target");

        Fixture sameId = fixture("replace-same-id");
        WaypointRecord sameIdOriginal = inventory(OVERWORLD, 1, 64, 2, "same id");
        sameId.store.upsert(sameIdOriginal);
        WaypointRecord onlyNewScanTime = sameIdOriginal.copy();
        onlyNewScanTime.updatedGameTime++;
        assertMutationResult(
                WaypointMutationStatus.NO_CHANGE,
                sameIdOriginal,
                sameIdOriginal,
                sameId.store.replace(sameIdOriginal.id, onlyNewScanTime),
                "replace same-id no-change");
        sameId.index.healthy = false;
        sameId.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertMutationResult(
                WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED,
                sameIdOriginal,
                sameIdOriginal,
                sameId.store.replace(sameIdOriginal.id, onlyNewScanTime),
                "replace same-id no-change/index degraded");
        sameId.index.syncResult = WaypointIndexUpdateStatus.INDEX_COMMITTED;
        WaypointRecord sameIdChanged = sameIdOriginal.copy();
        sameIdChanged.description = "same id changed";
        assertMutationResult(
                WaypointMutationStatus.COMMITTED,
                sameIdOriginal,
                sameIdChanged,
                sameId.store.replace(sameIdOriginal.id, sameIdChanged),
                "replace same-id changed");
        WaypointRecord sameIdTypeMorph = farm(OVERWORLD, 1, 64, 2, "same id type morph");
        assertMutationResult(
                WaypointMutationStatus.REJECTED_TYPE_CONFLICT,
                sameIdChanged,
                sameIdChanged,
                sameId.store.replace(sameIdOriginal.id, sameIdTypeMorph),
                "replace same-id type morph");
        WaypointRecord sameIdDegraded = sameIdChanged.copy();
        sameIdDegraded.description = "same id changed with degraded index";
        sameId.index.healthy = false;
        sameId.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertMutationResult(
                WaypointMutationStatus.COMMITTED_INDEX_DEGRADED,
                sameIdChanged,
                sameIdDegraded,
                sameId.store.replace(sameIdOriginal.id, sameIdDegraded),
                "replace same-id changed/index degraded");
        WaypointRecord sameIdJsonFailure = sameIdDegraded.copy();
        sameIdJsonFailure.description = "same id failed JSON change";
        sameId.persistence.failNext = true;
        assertMutationResult(
                WaypointMutationStatus.FAILED_JSON_COMMIT,
                sameIdDegraded,
                sameIdDegraded,
                sameId.store.replace(sameIdOriginal.id, sameIdJsonFailure),
                "replace same-id JSON failure");

        Fixture moved = fixture("replace-move");
        WaypointRecord old = inventory(OVERWORLD, 2, 64, 2, "old");
        moved.store.upsert(old);
        WaypointRecord newPosition = inventory(OVERWORLD, 3, 64, 2, "moved");
        assertMutationResult(
                WaypointMutationStatus.COMMITTED,
                old,
                newPosition,
                moved.store.replace(old.id, newPosition),
                "replace move");
        assertNull(moved.store.byPosition(OVERWORLD, new BlockPos(2, 64, 2)),
                "replace move retained the old position");
        assertNotNull(moved.store.byPosition(OVERWORLD, new BlockPos(3, 64, 2)),
                "replace move omitted the new position");

        Fixture movedDegraded = fixture("replace-move-degraded");
        WaypointRecord degradedOld = inventory(OVERWORLD, 4, 64, 2, "old");
        movedDegraded.store.upsert(degradedOld);
        movedDegraded.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        movedDegraded.index.healthy = false;
        WaypointRecord degradedNew = inventory(OVERWORLD, 5, 64, 2, "new");
        assertMutationResult(
                WaypointMutationStatus.COMMITTED_INDEX_DEGRADED,
                degradedOld,
                degradedNew,
                movedDegraded.store.replace(degradedOld.id, degradedNew),
                "replace move/index degraded");

        Fixture conflicts = fixture("replace-conflicts");
        WaypointRecord conflictOld = inventory(OVERWORLD, 10, 64, 0, "old");
        WaypointRecord inventoryTarget = inventory(OVERWORLD, 11, 64, 0, "target");
        conflicts.store.upsert(conflictOld);
        conflicts.store.upsert(inventoryTarget);
        WaypointRecord candidateAtInventoryTarget = inventoryTarget.copy();
        candidateAtInventoryTarget.description = "candidate";
        assertMutationResult(
                WaypointMutationStatus.REJECTED_TARGET_CONFLICT,
                conflictOld,
                inventoryTarget,
                conflicts.store.replace(conflictOld.id, candidateAtInventoryTarget),
                "replace occupied same-type target");

        Fixture otherTypeTarget = fixture("replace-other-target");
        WaypointRecord otherOld = inventory(OVERWORLD, 12, 64, 0, "old");
        WaypointRecord farmTarget = farm(OVERWORLD, 13, 64, 0, "farm target");
        otherTypeTarget.store.upsert(otherOld);
        otherTypeTarget.store.upsert(farmTarget);
        WaypointRecord inventoryAtFarmTarget = inventory(
                OVERWORLD, 13, 64, 0, "inventory candidate");
        assertMutationResult(
                WaypointMutationStatus.REJECTED_TYPE_CONFLICT,
                otherOld,
                farmTarget,
                otherTypeTarget.store.replace(otherOld.id, inventoryAtFarmTarget),
                "replace occupied other-type target");
        WaypointRecord typeMorph = farm(OVERWORLD, 14, 64, 0, "type morph");
        assertMutationResult(
                WaypointMutationStatus.REJECTED_TYPE_CONFLICT,
                otherOld,
                null,
                otherTypeTarget.store.replace(otherOld.id, typeMorph),
                "replace type morph");

        Fixture rollback = fixture("replace-rollback");
        WaypointRecord rollbackOld = inventory(OVERWORLD, 20, 64, 0, "old survives");
        rollback.store.upsert(rollbackOld);
        rollback.persistence.failNext = true;
        int syncsBeforeFailure = rollback.index.syncCalls;
        WaypointRecord rollbackCandidate = inventory(
                OVERWORLD, 21, 64, 0, "must not appear");
        assertMutationResult(
                WaypointMutationStatus.FAILED_JSON_COMMIT,
                rollbackOld,
                null,
                rollback.store.replace(rollbackOld.id, rollbackCandidate),
                "replace JSON failure");
        assertNotNull(rollback.store.byPosition(OVERWORLD, new BlockPos(20, 64, 0)),
                "failed replace removed the old record");
        assertNull(rollback.store.byPosition(OVERWORLD, new BlockPos(21, 64, 0)),
                "failed replace published the candidate");
        assertEquals(syncsBeforeFailure, rollback.index.syncCalls,
                "failed replace attempted to synchronize the index");
    }

    private static void deleteCoversCheckedEdgesAndRollback() {
        Fixture missing = fixture("delete-missing");
        assertStatus(WaypointMutationStatus.NOT_FOUND, missing.store.delete("missing"),
                "delete missing/healthy");
        missing.index.healthy = false;
        missing.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertStatus(WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                missing.store.delete("missing"), "delete missing/degraded");

        Fixture committed = fixture("delete-committed");
        WaypointRecord record = inventory(OVERWORLD, 30, 64, 0, "delete me");
        committed.store.upsert(record);
        WaypointMutationResult deleted = committed.store.delete(record.id);
        assertStatus(WaypointMutationStatus.COMMITTED, deleted, "delete committed");
        assertNotNull(deleted.previous(), "delete result omitted previous record");
        assertNull(deleted.current(), "delete result retained a current record");

        Fixture degraded = fixture("delete-degraded");
        WaypointRecord degradedRecord = inventory(OVERWORLD, 31, 64, 0, "delete me");
        degraded.store.upsert(degradedRecord);
        degraded.index.healthy = false;
        degraded.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertStatus(WaypointMutationStatus.COMMITTED_INDEX_DEGRADED,
                degraded.store.delete(degradedRecord.id), "delete committed/index degraded");
        assertNull(degraded.store.byPosition(OVERWORLD, new BlockPos(31, 64, 0)),
                "degraded delete was not authoritative");

        Fixture rollback = fixture("delete-rollback");
        WaypointRecord survives = inventory(OVERWORLD, 32, 64, 0, "survives");
        rollback.store.upsert(survives);
        rollback.persistence.failNext = true;
        int syncsBeforeFailure = rollback.index.syncCalls;
        assertStatus(WaypointMutationStatus.FAILED_JSON_COMMIT,
                rollback.store.delete(survives.id), "delete JSON failure");
        assertNotNull(rollback.store.byPosition(OVERWORLD, new BlockPos(32, 64, 0)),
                "failed delete removed the authoritative record");
        assertEquals(syncsBeforeFailure, rollback.index.syncCalls,
                "failed delete attempted to synchronize the index");
    }

    private static void markStaleCoversCheckedEdgesAndRollback() {
        Fixture missing = fixture("stale-missing");
        assertStatus(WaypointMutationStatus.NOT_FOUND, missing.store.markStale("missing"),
                "mark-stale missing/healthy");
        missing.index.healthy = false;
        missing.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertStatus(WaypointMutationStatus.NOT_FOUND_INDEX_DEGRADED,
                missing.store.markStale("missing"), "mark-stale missing/degraded");

        Fixture committed = fixture("stale-committed");
        WaypointRecord record = inventory(OVERWORLD, 40, 64, 0, "active");
        committed.store.upsert(record);
        WaypointMutationResult stale = committed.store.markStale(record.id);
        assertStatus(WaypointMutationStatus.COMMITTED, stale, "mark-stale committed");
        assertTrue(stale.current().stale, "mark-stale result is not stale");
        assertStatus(WaypointMutationStatus.NO_CHANGE,
                committed.store.markStale(record.id), "mark-stale already stale");
        committed.index.healthy = false;
        committed.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertStatus(WaypointMutationStatus.NO_CHANGE_INDEX_DEGRADED,
                committed.store.markStale(record.id), "mark-stale already stale/index degraded");

        Fixture degraded = fixture("stale-degraded");
        WaypointRecord degradedRecord = inventory(OVERWORLD, 41, 64, 0, "active");
        degraded.store.upsert(degradedRecord);
        degraded.index.healthy = false;
        degraded.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;
        assertStatus(WaypointMutationStatus.COMMITTED_INDEX_DEGRADED,
                degraded.store.markStale(degradedRecord.id), "mark-stale committed/index degraded");
        assertTrue(degraded.store.byPosition(OVERWORLD, new BlockPos(41, 64, 0)).stale,
                "degraded mark-stale was not authoritative");

        Fixture rollback = fixture("stale-rollback");
        WaypointRecord active = inventory(OVERWORLD, 42, 64, 0, "active");
        rollback.store.upsert(active);
        rollback.persistence.failNext = true;
        int syncsBeforeFailure = rollback.index.syncCalls;
        assertStatus(WaypointMutationStatus.FAILED_JSON_COMMIT,
                rollback.store.markStale(active.id), "mark-stale JSON failure");
        assertTrue(!rollback.store.byPosition(OVERWORLD, new BlockPos(42, 64, 0)).stale,
                "failed mark-stale leaked into authoritative memory");
        assertEquals(syncsBeforeFailure, rollback.index.syncCalls,
                "failed mark-stale attempted to synchronize the index");
    }

    private static void storeReturnsDeepDefensiveSnapshots() {
        Fixture fixture = fixture("defensive-copies");
        WaypointRecord source = inventory(OVERWORLD, 50, 64, 0, "authoritative");
        source.data = new InventoryWaypointData(new int[]{51, 64, 0}, "double_chest", null);
        WaypointMutationResult inserted = fixture.store.upsert(source);

        source.description = "mutated source";
        source.pos[0] = 500;
        source.inventoryData().secondaryPos[0] = 501;
        inserted.current().description = "mutated result";

        WaypointRecord byPosition = fixture.store.byPosition(OVERWORLD, new BlockPos(50, 64, 0));
        WaypointRecord fromAll = fixture.store.all().get(0);
        WaypointRecord fromPublished = fixture.store.publishedRecords().get(0);
        byPosition.description = "mutated by-position copy";
        fromAll.pos[1] = -100;
        fromPublished.inventoryData().secondaryPos[2] = 999;

        WaypointRecord authoritative = fixture.store.byPosition(OVERWORLD, new BlockPos(50, 64, 0));
        assertEquals("authoritative", authoritative.description,
                "caller mutation escaped into authoritative description");
        assertEquals(50, authoritative.pos[0], "caller mutation escaped into authoritative position");
        assertEquals(64, authoritative.pos[1], "snapshot mutation escaped into authoritative position");
        assertEquals(51, authoritative.inventoryData().secondaryPos[0],
                "source payload mutation escaped into authoritative data");
        assertEquals(0, authoritative.inventoryData().secondaryPos[2],
                "published payload mutation escaped into authoritative data");
    }

    private static void allMutationStatusesHaveBoundedFeedbackSurfaces() {
        WaypointRecord previous = inventory(OVERWORLD, 55, 64, 0, "previous");
        WaypointRecord current = inventory(OVERWORLD, 56, 64, 0, "current");
        EnumSet<WaypointMutationStatus> covered = EnumSet.noneOf(WaypointMutationStatus.class);

        for (WaypointMutationStatus status : WaypointMutationStatus.values()) {
            WaypointMutationResult result = new WaypointMutationResult(
                    status, previous.copy(), current.copy());
            FeedbackSurface feedback = feedbackFor(result);
            covered.add(result.status());
            assertTrue(feedback.modelMessage() != null && !feedback.modelMessage().isBlank(),
                    "mutation status lacks model feedback: " + status);
            assertTrue(feedback.modelMessage().length() <= WaypointReportFormatter.MAX_MODEL_LENGTH,
                    "mutation model feedback is unbounded: " + status);
            assertTrue(!feedback.playerMessages().isEmpty(),
                    "mutation status lacks player feedback: " + status);
            for (Component playerMessage : feedback.playerMessages()) {
                assertNotNull(playerMessage, "mutation status has null player feedback: " + status);
            }
        }

        assertEquals(EnumSet.allOf(WaypointMutationStatus.class), covered,
                "mutation status-to-feedback coverage is incomplete");
    }

    private static FeedbackSurface feedbackFor(WaypointMutationResult result) {
        String pos = "(56,64,0)";
        return switch (result.status()) {
            case COMMITTED -> new FeedbackSurface(
                    WaypointReportFormatter.waypointRegistered(pos, 0, 2, true),
                    List.of(WaypointReportFormatter.waypointRegisteredComponent(pos, 0, 2, true)));
            case COMMITTED_INDEX_DEGRADED -> new FeedbackSurface(
                    WaypointReportFormatter.boundModel(
                            WaypointReportFormatter.waypointRegistered(pos, 0, 2, true) + " "
                                    + WaypointReportFormatter.indexDegradedModel(true)),
                    List.of(
                            WaypointReportFormatter.waypointRegisteredComponent(pos, 0, 2, true),
                            WaypointReportFormatter.indexDegradedComponent(true)));
            case NO_CHANGE -> new FeedbackSurface(
                    WaypointReportFormatter.waypointUnchanged(pos),
                    List.of(WaypointReportFormatter.waypointUnchangedComponent(pos)));
            case NO_CHANGE_INDEX_DEGRADED -> new FeedbackSurface(
                    WaypointReportFormatter.boundModel(
                            WaypointReportFormatter.waypointUnchanged(pos) + " "
                                    + WaypointReportFormatter.indexDegradedModel(false)),
                    List.of(
                            WaypointReportFormatter.waypointUnchangedComponent(pos),
                            WaypointReportFormatter.indexDegradedComponent(false)));
            case NOT_FOUND -> new FeedbackSurface(
                    WaypointReportFormatter.mutationNotFoundModel(),
                    List.of(WaypointReportFormatter.mutationNotFoundComponent()));
            case NOT_FOUND_INDEX_DEGRADED -> new FeedbackSurface(
                    WaypointReportFormatter.boundModel(
                            WaypointReportFormatter.mutationNotFoundModel() + " "
                                    + WaypointReportFormatter.indexDegradedModel(false)),
                    List.of(
                            WaypointReportFormatter.mutationNotFoundComponent(),
                            WaypointReportFormatter.indexDegradedComponent(false)));
            case REJECTED_TYPE_CONFLICT, REJECTED_TARGET_CONFLICT -> new FeedbackSurface(
                    WaypointReportFormatter.waypointConflictModel(result.status()),
                    List.of(WaypointReportFormatter.waypointConflictComponent()));
            case FAILED_JSON_COMMIT -> new FeedbackSurface(
                    WaypointReportFormatter.jsonCommitFailedModel(),
                    List.of(WaypointReportFormatter.jsonCommitFailedComponent()));
            case FAILED_STORE_UNAVAILABLE -> new FeedbackSurface(
                    WaypointReportFormatter.boundModel(
                            "elliegps_store_unavailable: no active EllieGPS store"),
                    List.of(Component.translatable("message.playerengine.elliegps.store_unavailable")));
        };
    }

    private static void searchFiltersBeforeFinalLimit() {
        Fixture fixture = fixture("search-filter-before-limit");
        WaypointRecord wrongType = farm(OVERWORLD, 60, 64, 0, "wheat farm");
        WaypointRecord wrongDimension = inventory(NETHER, 61, 64, 0, "wheat storage");
        WaypointRecord stale = inventory(OVERWORLD, 62, 64, 0, "wheat stale");
        stale.stale = true;
        WaypointRecord eligible = inventory(OVERWORLD, 63, 64, 0, "wheat eligible");
        fixture.store.upsert(wrongType);
        fixture.store.upsert(wrongDimension);
        fixture.store.upsert(stale);
        fixture.store.upsert(eligible);
        fixture.index.queryHits = List.of(
                hit(wrongType.id, 4.0),
                hit(wrongDimension.id, 3.0),
                hit(stale.id, 2.0),
                hit(eligible.id, 1.0));

        WaypointSearchResult result = WaypointSearchService.find(
                fixture.store,
                "wheat",
                OVERWORLD,
                Set.of(WaypointTypes.INVENTORY),
                false,
                WaypointSearchOrder.RELEVANCE,
                null,
                1);
        assertEquals(WaypointSearchStatus.INDEXED, result.status(),
                "healthy structured relevance status");
        assertIds(List.of(eligible.id), result.records(),
                "structured filters were applied after the caller limit");
        assertEquals(4, fixture.index.lastQueryLimit,
                "index was queried with caller top-k instead of the authoritative corpus bound");
    }

    private static void emptyQueryUsesStructuredStableOrder() {
        Fixture fixture = fixture("search-empty");
        WaypointRecord laterId = inventory(OVERWORLD, 2, 64, 0, "later");
        WaypointRecord earlierId = inventory(OVERWORLD, 1, 64, 0, "earlier");
        fixture.store.upsert(laterId);
        fixture.store.upsert(inventory(NETHER, 0, 64, 0, "filtered"));
        fixture.store.upsert(earlierId);

        WaypointSearchResult result = WaypointSearchService.find(
                fixture.store,
                "   ",
                OVERWORLD,
                Set.of(WaypointTypes.INVENTORY),
                false,
                WaypointSearchOrder.RELEVANCE,
                null,
                10);
        assertEquals(WaypointSearchStatus.INDEXED, result.status(), "empty query status");
        assertIds(List.of(earlierId.id, laterId.id), result.records(),
                "empty query did not use stable structured order");
        assertEquals(0, fixture.index.queryCalls, "empty query unnecessarily queried the index");
    }

    private static void degradedSearchUsesFullStoreFallback() {
        Fixture fixture = fixture("search-fallback");
        WaypointRecord emerald = inventory(OVERWORLD, 70, 64, 0, "emerald supply cache");
        emerald.keywords = List.of("emerald", "storage");
        fixture.store.upsert(emerald);
        fixture.index.healthy = false;
        fixture.index.syncResult = WaypointIndexUpdateStatus.INDEX_FAILED;

        WaypointSearchResult result = WaypointSearchService.find(
                fixture.store,
                "emerald",
                OVERWORLD,
                Set.of(WaypointTypes.INVENTORY),
                false,
                WaypointSearchOrder.RELEVANCE,
                null,
                5);
        assertEquals(WaypointSearchStatus.FULL_STORE_FALLBACK, result.status(),
                "failed index repair did not report full-store fallback");
        assertIds(List.of(emerald.id), result.records(),
                "full-store fallback omitted the matching authoritative record");
        assertTrue(fixture.index.syncCalls >= 2,
                "degraded search did not attempt an index repair");
    }

    private static void nearestUsesThreeDimensionsAndStableIdTieBreak() {
        Fixture fixture = fixture("search-nearest");
        WaypointRecord nearest = inventory(OVERWORLD, 1, 64, 0, "nearest");
        WaypointRecord equalDistanceLexicallyFirst = inventory(OVERWORLD, 0, 64, 3, "tie horizontal");
        WaypointRecord equalDistanceLexicallySecond = inventory(OVERWORLD, 0, 67, 0, "tie vertical");
        fixture.store.upsert(equalDistanceLexicallySecond);
        fixture.store.upsert(equalDistanceLexicallyFirst);
        fixture.store.upsert(nearest);

        WaypointSearchResult result = WaypointSearchService.find(
                fixture.store,
                "",
                OVERWORLD,
                Set.of(WaypointTypes.INVENTORY),
                false,
                WaypointSearchOrder.NEAREST,
                new BlockPos(0, 64, 0),
                10);
        assertEquals(WaypointSearchStatus.AUTHORITATIVE_SPATIAL, result.status(),
                "nearest search status");
        assertIds(
                List.of(nearest.id, equalDistanceLexicallyFirst.id, equalDistanceLexicallySecond.id),
                result.records(),
                "nearest search ignored y distance or stable id tie-break");
        assertEquals(0, fixture.index.queryCalls, "nearest search depended on the derived index");
    }

    private static void searchFailsClosedAboveAuthoritativeCap() throws Exception {
        Fixture fixture = fixture("search-cap");
        WaypointRecord record = inventory(OVERWORLD, 80, 64, 0, "cap fixture");
        List<WaypointRecord> oversized = Collections.nCopies(
                WaypointSearchService.MAX_AUTHORITATIVE_RECORDS + 1, record);
        Field publishedSnapshot = EllieGPSStore.class.getDeclaredField("publishedSnapshot");
        publishedSnapshot.setAccessible(true);
        publishedSnapshot.set(fixture.store, oversized);

        WaypointSearchResult result = WaypointSearchService.find(
                fixture.store,
                "anything",
                null,
                Set.of(),
                true,
                WaypointSearchOrder.RELEVANCE,
                null,
                1);
        assertEquals(WaypointSearchStatus.FAILED_SCAN_LIMIT, result.status(),
                "authoritative search did not fail closed above 4096 records");
        assertTrue(result.records().isEmpty(), "scan-limit failure returned a partial answer");
        assertEquals(0, fixture.index.queryCalls, "scan-limit failure queried the index");
    }

    private static void searchOrderContractsRejectInvalidOrigins() {
        Fixture fixture = fixture("search-contracts");
        assertThrows(IllegalArgumentException.class, () -> WaypointSearchService.find(
                        fixture.store, "", null, Set.of(), true,
                        WaypointSearchOrder.NEAREST, null, 1),
                "nearest search accepted a missing origin");
        assertThrows(IllegalArgumentException.class, () -> WaypointSearchService.find(
                        fixture.store, "", null, Set.of(), true,
                        WaypointSearchOrder.RELEVANCE, BlockPos.ZERO, 1),
                "relevance search accepted an origin");
    }

    private static void searchUnavailableAndCountErrorsAreExplicit() {
        WaypointSearchResult unavailable = WaypointSearchService.find(
                "farm",
                OVERWORLD,
                Set.of(WaypointTypes.FARM),
                false,
                WaypointSearchOrder.RELEVANCE,
                null,
                1);
        assertEquals(WaypointSearchStatus.FAILED_STORE_UNAVAILABLE, unavailable.status(),
                "missing active store was represented as a successful empty search");

        WaypointCountResult invalidContext = new EllieGPSWaypointCountingService()
                .estimateNearbyWaypointItemsChecked(null, null, 16.0, OVERWORLD);
        assertEquals(WaypointSearchStatus.FAILED_SEARCH_ERROR, invalidContext.status(),
                "invalid required count context was represented as an indexed zero");
        assertEquals(0, invalidContext.count(), "failed checked count returned a fabricated count");
        assertTrue(WaypointReportFormatter.searchFailedModel().length()
                        <= WaypointReportFormatter.MAX_MODEL_LENGTH,
                "search-error model feedback exceeds the total cap");
    }

    private static Fixture fixture(String name) {
        RecordingPersistence persistence = new RecordingPersistence();
        FakeIndex index = new FakeIndex();
        EllieGPSStore store = new EllieGPSStore(
                Path.of("build", "farming-self-test", name), persistence, index);
        return new Fixture(store, persistence, index);
    }

    private static WaypointRecord inventory(
            String dimension,
            int x,
            int y,
            int z,
            String description) {
        WaypointRecord record = baseRecord(dimension, x, y, z, description);
        record.type = WaypointTypes.INVENTORY;
        record.keywords = List.of("storage", description);
        record.data = new InventoryWaypointData(null, "chest", null);
        return record;
    }

    private static WaypointRecord farm(
            String dimension,
            int x,
            int y,
            int z,
            String description) {
        WaypointRecord record = baseRecord(dimension, x, y, z, description);
        record.type = WaypointTypes.FARM;
        record.keywords = List.of("farm", "wheat");
        record.data = new FarmWaypointData(
                4,
                80,
                List.of(new FarmCropCount("minecraft:wheat", "minecraft:wheat_seeds", 1)));
        return record;
    }

    private static WaypointRecord baseRecord(
            String dimension,
            int x,
            int y,
            int z,
            String description) {
        BlockPos pos = new BlockPos(x, y, z);
        WaypointRecord record = new WaypointRecord();
        record.id = WaypointRecord.idFor(dimension, pos);
        record.dimension = dimension;
        record.pos = new int[]{x, y, z};
        record.description = description;
        record.origin = WaypointRecord.ORIGIN_EXPLICIT_CREATE;
        record.stale = false;
        record.createdGameTime = 1L;
        record.updatedGameTime = 1L;
        return record;
    }

    private static RetrievalHit hit(String id, double score) {
        return new RetrievalHit(id, score, 1, 1);
    }

    private static void assertStatus(
            WaypointMutationStatus expected,
            WaypointMutationResult actual,
            String label) {
        assertEquals(expected, actual.status(), label + " status");
    }

    private static void assertMutationResult(
            WaypointMutationStatus expectedStatus,
            WaypointRecord expectedPrevious,
            WaypointRecord expectedCurrent,
            WaypointMutationResult actual,
            String label) {
        assertStatus(expectedStatus, actual, label);
        assertRecordEquals(expectedPrevious, actual.previous(), label + " previous");
        assertRecordEquals(expectedCurrent, actual.current(), label + " current");
    }

    private static void assertRecordEquals(
            WaypointRecord expected,
            WaypointRecord actual,
            String message) {
        if (expected == null || actual == null) {
            if (expected != actual) {
                throw new AssertionError(message + ": expected="
                        + (expected == null ? "null" : expected.toJson()) + ", actual="
                        + (actual == null ? "null" : actual.toJson()));
            }
            return;
        }
        assertEquals(expected.toJson(), actual.toJson(), message);
    }

    private static void assertIds(List<String> expected, List<WaypointRecord> actual, String message) {
        List<String> ids = new ArrayList<>();
        for (WaypointRecord record : actual) {
            ids.add(record.id);
        }
        assertEquals(expected, ids, message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": actual=" + value);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
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

    private record Fixture(
            EllieGPSStore store,
            RecordingPersistence persistence,
            FakeIndex index) {}

    private record FeedbackSurface(String modelMessage, List<Component> playerMessages) {}

    private static final class RecordingPersistence implements WaypointPersistenceBackend {
        private int writeCount;
        private boolean failNext;

        @Override
        public void persist(Path storeFile, String serializedStore) throws IOException {
            if (failNext) {
                failNext = false;
                throw new IOException("injected JSON persistence failure");
            }
            writeCount++;
        }
    }

    private static final class FakeIndex implements WaypointIndexBackend {
        private boolean healthy = true;
        private WaypointIndexUpdateStatus syncResult = WaypointIndexUpdateStatus.INDEX_COMMITTED;
        private int syncCalls;
        private int queryCalls;
        private int lastQueryLimit = -1;
        private List<RetrievalHit> queryHits = List.of();

        @Override
        public boolean isHealthyFor(String expectedVersionToken) {
            return healthy;
        }

        @Override
        public WaypointIndexUpdateStatus synchronize(EllieGPSStore store) {
            syncCalls++;
            healthy = syncResult == WaypointIndexUpdateStatus.INDEX_COMMITTED;
            return syncResult;
        }

        @Override
        public List<RetrievalHit> query(String query, int limit) {
            queryCalls++;
            lastQueryLimit = limit;
            return new ArrayList<>(queryHits.subList(0, Math.min(limit, queryHits.size())));
        }
    }
}
