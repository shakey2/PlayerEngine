package com.player2.playerengine.agentic.elliegps;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Central bounded formatter for model and localized player EllieGPS feedback. */
public final class WaypointReportFormatter {

    public static final int MAX_MODEL_LENGTH = 512;
    public static final int MAX_REASON_LENGTH = 160;
    public static final int MAX_IDENTIFIER_LENGTH = 96;

    private WaypointReportFormatter() {}

    public static String ellieGpsDisabledPlayer() {
        return "EllieGPS is disabled (ellieGpsEnabled=false in settings).";
    }

    public static Component ellieGpsDisabledPlayerComponent() {
        return Component.translatable("message.playerengine.elliegps.disabled");
    }

    public static String ellieGpsDisabledModel() {
        return "EllieGPS is disabled; command refused. Check ellieGpsEnabled setting.";
    }

    public static String waypointRegistered(String pos, int itemTypes, int kwCount,
                                             boolean snapshotOmitted) {
        return boundModel("waypoint registered at " + boundIdentifier(pos) + ": " + itemTypes
                + " item types, " + kwCount + " keywords"
                + (snapshotOmitted ? " (snapshot omitted: slots > threshold)" : ""));
    }

    public static MutableComponent waypointRegisteredComponent(String pos, int itemTypes, int kwCount,
                                                                 boolean snapshotOmitted) {
        return Component.translatable(snapshotOmitted
                        ? "message.playerengine.elliegps.waypoint_registered_no_snapshot"
                        : "message.playerengine.elliegps.waypoint_registered",
                pos, itemTypes, kwCount);
    }

    public static String waypointUnchanged(String pos) {
        return boundModel("waypoint at " + boundIdentifier(pos)
                + " was already up to date; no write was needed.");
    }

    public static Component waypointUnchangedComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.waypoint_unchanged", pos);
    }

    public static String waypointDeleted(String pos, String dimensionId) {
        return boundModel("waypoint at " + boundIdentifier(pos) + " in "
                + boundIdentifier(dimensionId) + " deleted.");
    }

    public static Component waypointDeletedComponent(String pos, String dimensionId) {
        return Component.translatable("message.playerengine.elliegps.waypoint_deleted", pos, dimensionId);
    }

    public static String noWaypointAt(String pos, String dimensionId) {
        return boundModel("no waypoint at " + boundIdentifier(pos) + " in "
                + boundIdentifier(dimensionId) + ".");
    }

    public static Component noWaypointAtComponent(String pos, String dimensionId) {
        return Component.translatable("message.playerengine.elliegps.no_waypoint_at", pos, dimensionId);
    }

    public static String waypointStaleMissing(String pos) {
        String shown = boundIdentifier(pos);
        return boundModel("waypoint stale: no container at " + shown
                + "; use delete_waypoint " + shown + " to remove it.");
    }

    public static MutableComponent waypointStaleMissingComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.waypoint_stale_missing", pos, pos);
    }

    public static String farmWaypointStaleMissing(String pos) {
        String shown = boundIdentifier(pos);
        return boundModel("farm waypoint stale: no valid farm center at " + shown
                + "; use delete_waypoint " + shown + " to remove it.");
    }

    public static Component farmWaypointStaleMissingComponent(String pos) {
        return Component.translatable(
                "message.playerengine.elliegps.farm_waypoint_stale_missing", pos, pos);
    }

    public static String noSnapshotStored() {
        return "no snapshot stored; run audit_waypoint to record one.";
    }

    public static MutableComponent noSnapshotStoredComponent() {
        return Component.translatable("message.playerengine.elliegps.no_snapshot_stored");
    }

    public static String auditHint(String pos) {
        return boundModel("run audit_waypoint " + boundIdentifier(pos)
                + " to update the stored snapshot.");
    }

    public static String locateHitLine(String pos, String kind, String description, boolean stale) {
        return boundModel(boundIdentifier(pos) + " " + boundIdentifier(kind) + ": "
                + bound(description, 320) + (stale ? " [stale]" : ""));
    }

    public static String noWaypointsMatch() {
        return "no waypoints match the query in this dimension.";
    }

    public static Component noWaypointsMatchComponent() {
        return Component.translatable("message.playerengine.elliegps.no_waypoints_match");
    }

    public static Component locateFoundComponent(int count, String query) {
        return Component.translatable("message.playerengine.elliegps.locate_found", count, query);
    }

    public static String searchFallbackModel() {
        return "EllieGPS search index was unavailable; results came from a bounded authoritative-store fallback.";
    }

    public static Component searchFallbackComponent() {
        return Component.translatable("message.playerengine.elliegps.search_fallback");
    }

    public static String searchScanLimitModel() {
        return "EllieGPS search refused an incomplete result because the authoritative store exceeds the 4096-record scan limit.";
    }

    public static Component searchScanLimitComponent() {
        return Component.translatable("message.playerengine.elliegps.search_scan_limit");
    }

    public static String searchStoreUnavailableModel() {
        return "EllieGPS search could not run because no authoritative waypoint store is active.";
    }

    public static Component searchStoreUnavailableComponent() {
        return Component.translatable("message.playerengine.elliegps.search_store_unavailable");
    }

    public static String searchFailedModel() {
        return "EllieGPS search failed before it could produce an authoritative complete result.";
    }

    public static Component searchFailedComponent() {
        return Component.translatable("message.playerengine.elliegps.search_failed");
    }

    public static Component autoRegisteredMilestone(String pos) {
        return Component.translatable("message.playerengine.elliegps.auto_registered", pos);
    }

    public static Component autoSkippedLine(String reason) {
        return Component.translatable("message.playerengine.elliegps.auto_skipped", boundReason(reason));
    }

    public static String indexDegradedModel(boolean committedChange) {
        return committedChange
                ? "waypoint data was saved, but EllieGPS search indexing is temporarily degraded."
                : "waypoint data was unchanged, but EllieGPS could not validate or repair its search index.";
    }

    public static Component indexDegradedComponent(boolean committedChange) {
        return Component.translatable(committedChange
                ? "message.playerengine.elliegps.index_degraded_saved"
                : "message.playerengine.elliegps.index_degraded_unchanged");
    }

    public static String jsonCommitFailedModel() {
        return "EllieGPS could not save the waypoint; the previous authoritative record was kept.";
    }

    public static Component jsonCommitFailedComponent() {
        return Component.translatable("message.playerengine.elliegps.json_commit_failed");
    }

    public static String waypointConflictModel(WaypointMutationStatus status) {
        return "EllieGPS mutation was refused (" + status.name().toLowerCase(Locale.ROOT)
                + "); authoritative waypoint data was left unchanged.";
    }

    public static Component waypointConflictComponent() {
        return Component.translatable("message.playerengine.elliegps.mutation_conflict");
    }

    public static String mutationNotFoundModel() {
        return "EllieGPS did not change anything because the captured waypoint no longer exists.";
    }

    public static Component mutationNotFoundComponent() {
        return Component.translatable("message.playerengine.elliegps.mutation_not_found");
    }

    public static Component auditFailedComponent(String detail) {
        return Component.translatable("message.playerengine.elliegps.audit_failed", boundReason(detail));
    }

    public static Component compareFailedComponent(String detail) {
        return Component.translatable("message.playerengine.elliegps.compare_failed", boundReason(detail));
    }

    public static Component inventoryCompareNoDriftComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.inventory_compare_no_drift", pos);
    }

    public static Component inventoryCompareDriftComponent(String pos, String summary) {
        return Component.translatable("message.playerengine.elliegps.inventory_compare_drift",
                pos, bound(summary, 320));
    }

    public static String refusedWorldgenLoot(String pos) {
        return boundModel("refused: " + boundIdentifier(pos)
                + " is an un-opened worldgen loot chest; waypoint not registered.");
    }

    public static Component refusedWorldgenLootComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.refused_worldgen_loot", pos);
    }

    public static String refusedOriginUnverified(String pos) {
        return boundModel("refused: could not verify the placement origin of " + boundIdentifier(pos)
                + " (worldgen check unavailable); waypoint not registered.");
    }

    public static Component refusedOriginUnverifiedComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.refused_origin_unverified", pos);
    }

    public static String structureOverrideNote() {
        return "note: this chest is inside a structure's bounds; registered by explicit request.";
    }

    public static Component structureOverrideNoteComponent() {
        return Component.translatable("message.playerengine.elliegps.structure_override_note");
    }

    public static String quarantineNote() {
        return "EllieGPS: previous waypoints.json was corrupt and quarantined; store started empty.";
    }

    public static Component quarantineNoteComponent() {
        return Component.translatable("message.playerengine.elliegps.quarantine_note");
    }

    public static String farmAuditUpdated(
            String pos,
            int farmlandCount,
            int openSlots,
            int cropTypes) {
        return boundModel("farm waypoint refreshed at " + boundIdentifier(pos) + ": "
                + farmlandCount + " farmland blocks, " + openSlots + " open slots, "
                + cropTypes + " crop types.");
    }

    public static Component farmAuditUpdatedComponent(
            String pos,
            int farmlandCount,
            int openSlots,
            int cropTypes) {
        return Component.translatable("message.playerengine.elliegps.farm_audit_updated",
                pos, farmlandCount, openSlots, cropTypes);
    }

    public static String farmObservationFailed(FarmObservationResult result) {
        return boundModel("farm observation unavailable ("
                + result.status().name().toLowerCase(Locale.ROOT) + "): "
                + boundReason(result.controlledReason()));
    }

    public static Component farmObservationFailedComponent(FarmObservationResult result) {
        return Component.translatable("message.playerengine.elliegps.farm_observation_failed",
                result.status().name().toLowerCase(Locale.ROOT), boundReason(result.controlledReason()));
    }

    public static String farmCompareNoDrift(String pos) {
        return boundModel("no drift at " + boundIdentifier(pos)
                + "; stored farm contents match the live observation.");
    }

    public static Component farmCompareNoDriftComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.farm_compare_no_drift", pos);
    }

    public static String farmCompareDrift(String pos, String summary) {
        return boundModel("farm drift at " + boundIdentifier(pos) + ": " + bound(summary, 320)
                + "; run audit_waypoint " + boundIdentifier(pos) + " to update it.");
    }

    public static Component farmCompareDriftComponent(String pos, String summary) {
        return Component.translatable("message.playerengine.elliegps.farm_compare_drift",
                pos, bound(summary, 320));
    }

    public static String descriptionPolishScheduledNote() {
        return "description may be refined asynchronously.";
    }

    public static Component descriptionPolishIndexDegradedComponent() {
        return Component.translatable("message.playerengine.elliegps.polish_index_degraded");
    }

    public static Component descriptionPolishCommitFailedComponent() {
        return Component.translatable("message.playerengine.elliegps.polish_save_failed");
    }

    public static String boundModel(String value) {
        return bound(value, MAX_MODEL_LENGTH);
    }

    public static String boundReason(String value) {
        return bound(value, MAX_REASON_LENGTH);
    }

    public static String boundIdentifier(String value) {
        return bound(value, MAX_IDENTIFIER_LENGTH);
    }

    private static String bound(String value, int max) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
