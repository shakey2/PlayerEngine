package com.player2.playerengine.agentic.elliegps;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Centralised formatter for all AI-visible and player-visible EllieGPS strings (Part C5, WS5).
 *
 * <p><b>Ownership:</b> this file is owned by <b>WS5</b>; these are the final production
 * strings and the cross-branch parity surface — both branches must stay string-identical.
 *
 * <p>All AI-visible and player-visible strings for the five waypoint commands live here so that
 * both branches stay string-identical (byte-for-byte outside the two documented divergences).
 * No loader (Fabric/Forge) imports are permitted in this common-module class.
 */
public final class WaypointReportFormatter {

    private WaypointReportFormatter() {}

    // -------------------------------------------------------------------------
    // Disabled state
    // -------------------------------------------------------------------------

    /** Player-facing message when EllieGPS is disabled via config. */
    public static String ellieGpsDisabledPlayer() {
        return "EllieGPS is disabled (ellieGpsEnabled=false in settings).";
    }

    /**
     * Component variant of {@link #ellieGpsDisabledPlayer()} — use this in player-facing calls
     * so the string is resolved from the translation key rather than a hardcoded literal.
     * Pass straight into {@code reportAgenticProgress(Component)} so the client resolves it.
     */
    public static Component ellieGpsDisabledPlayerComponent() {
        return Component.translatable("message.playerengine.elliegps.disabled");
    }

    /** Model-facing message when EllieGPS is disabled. */
    public static String ellieGpsDisabledModel() {
        return "EllieGPS is disabled; command refused. Check ellieGpsEnabled setting.";
    }

    // -------------------------------------------------------------------------
    // create_waypoint
    // -------------------------------------------------------------------------

    /**
     * Success line for {@code create_waypoint}. Returned to both player and model.
     * <p>Use {@link #waypointRegisteredComponent(String, int, int, boolean)} for the
     * player-facing call so the string resolves from the translation key.
     *
     * @param pos             formatted position string, e.g. {@code "(120,64,-35)"}
     * @param itemTypes       number of distinct item types in the snapshot (or 0 for keyword-only)
     * @param kwCount         number of keywords assigned
     * @param snapshotOmitted true when the slot threshold was exceeded
     */
    public static String waypointRegistered(String pos, int itemTypes, int kwCount, boolean snapshotOmitted) {
        return "waypoint registered at " + pos + ": " + itemTypes + " item types, " + kwCount + " keywords"
                + (snapshotOmitted ? " (snapshot omitted: slots > threshold)" : "");
    }

    /**
     * Component variant of {@link #waypointRegistered(String, int, int, boolean)} for the
     * player-facing path. Returns either {@code message.playerengine.elliegps.waypoint_registered}
     * or {@code message.playerengine.elliegps.waypoint_registered_no_snapshot} depending on
     * {@code snapshotOmitted}.
     */
    public static MutableComponent waypointRegisteredComponent(String pos, int itemTypes, int kwCount,
            boolean snapshotOmitted) {
        if (snapshotOmitted) {
            return Component.translatable(
                    "message.playerengine.elliegps.waypoint_registered_no_snapshot",
                    pos, itemTypes, kwCount);
        }
        return Component.translatable(
                "message.playerengine.elliegps.waypoint_registered", pos, itemTypes, kwCount);
    }

    // -------------------------------------------------------------------------
    // delete_waypoint
    // -------------------------------------------------------------------------

    /** Success message for {@code delete_waypoint}. */
    public static String waypointDeleted(String pos, String dimensionId) {
        return "waypoint at " + pos + " in " + dimensionId + " deleted.";
    }

    /** Component variant of {@link #waypointDeleted(String, String)} for the player-facing path. */
    public static Component waypointDeletedComponent(String pos, String dimensionId) {
        return Component.translatable("message.playerengine.elliegps.waypoint_deleted", pos, dimensionId);
    }

    /** Error: no record found at the given position. */
    public static String noWaypointAt(String pos, String dimensionId) {
        return "no waypoint at " + pos + " in " + dimensionId + ".";
    }

    /** Component variant of {@link #noWaypointAt(String, String)} for the player-facing path. */
    public static Component noWaypointAtComponent(String pos, String dimensionId) {
        return Component.translatable("message.playerengine.elliegps.no_waypoint_at", pos, dimensionId);
    }

    // -------------------------------------------------------------------------
    // audit_waypoint / compare_waypoint — stale
    // -------------------------------------------------------------------------

    /**
     * Both-audience message when a container is missing during audit/compare
     * (Decision 8 stale path).
     */
    public static String waypointStaleMissing(String pos) {
        return "waypoint stale: no container at " + pos
                + "; use delete_waypoint " + pos + " to remove it.";
    }

    /**
     * Component variant of {@link #waypointStaleMissing(String)} for the player-facing path.
     * {@code pos} is passed as both {@code %1$s} and {@code %2$s} to match the String variant.
     * Pass straight into {@code reportAgenticProgress(Component)} so the client resolves it.
     */
    public static MutableComponent waypointStaleMissingComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.waypoint_stale_missing", pos, pos);
    }

    // -------------------------------------------------------------------------
    // compare_waypoint
    // -------------------------------------------------------------------------

    /**
     * Returned when the record has no snapshot (keyword-only) and a compare is attempted.
     */
    public static String noSnapshotStored() {
        return "no snapshot stored; run audit_waypoint to record one.";
    }

    /**
     * Component variant of {@link #noSnapshotStored()} for the player-facing path.
     * Pass straight into {@code reportAgenticProgress(Component)} so the client resolves it.
     */
    public static MutableComponent noSnapshotStoredComponent() {
        return Component.translatable("message.playerengine.elliegps.no_snapshot_stored");
    }

    /**
     * Trailing hint appended when compare found drift (steers the model toward the
     * compare->audit loop per Decision 1).
     */
    public static String auditHint(String pos) {
        return "run audit_waypoint " + pos + " to update the stored snapshot.";
    }

    // -------------------------------------------------------------------------
    // locate_waypoints
    // -------------------------------------------------------------------------

    /**
     * Formats a single locate hit line in the Decision 1 shape:
     * {@code (120,64,-35) double_chest: <description> [stale]}.
     *
     * @param pos         formatted position, e.g. {@code "(120,64,-35)"}
     * @param kind        container kind token, e.g. {@code "double_chest"}
     * @param description waypoint description
     * @param stale       true if the record is stale
     */
    public static String locateHitLine(String pos, String kind, String description, boolean stale) {
        return pos + " " + kind + ": " + description + (stale ? " [stale]" : "");
    }

    /** Returned when no waypoints match a locate query. */
    public static String noWaypointsMatch() {
        return "no waypoints match the query in this dimension.";
    }

    /** Component variant of {@link #noWaypointsMatch()} for the player-facing path. */
    public static Component noWaypointsMatchComponent() {
        return Component.translatable("message.playerengine.elliegps.no_waypoints_match");
    }

    // -------------------------------------------------------------------------
    // Auto-hook / degradation
    // -------------------------------------------------------------------------

    /**
     * Player milestone line when auto-registration succeeds.
     */
    public static String autoRegisteredMilestone(String pos) {
        return "registered storage waypoint at " + pos + ".";
    }

    /**
     * Player skip line when the origin filter or evidence gate rejects the auto-hook.
     */
    public static String autoSkippedLine(String reason) {
        return "waypoint not auto-registered: " + reason + ".";
    }

    // -------------------------------------------------------------------------
    // Origin-filter refusals
    // -------------------------------------------------------------------------

    /** Both-audience refusal when Tier 2 (worldgen loot-table) fires. */
    public static String refusedWorldgenLoot(String pos) {
        return "refused: " + pos + " is an un-opened worldgen loot chest; waypoint not registered.";
    }

    /** Component variant of {@link #refusedWorldgenLoot(String)} for the player-facing path. */
    public static Component refusedWorldgenLootComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.refused_worldgen_loot", pos);
    }

    /**
     * Both-audience refusal when the classifier returned UNKNOWN on an explicit create
     * (Decision 4 conservative default: an unevaluated Tier 2 is never overridden).
     */
    public static String refusedOriginUnverified(String pos) {
        return "refused: could not verify the placement origin of " + pos
                + " (worldgen check unavailable); waypoint not registered.";
    }

    /** Component variant of {@link #refusedOriginUnverified(String)} for the player-facing path. */
    public static Component refusedOriginUnverifiedComponent(String pos) {
        return Component.translatable("message.playerengine.elliegps.refused_origin_unverified", pos);
    }

    /**
     * Note appended to the create success feedback when the Tier 3 structure-piece verdict was
     * overridden by explicit create intent (Decision 4 explicit-create policy; the override is
     * noted so both audiences know the chest sits inside a structure's bounds).
     */
    public static String structureOverrideNote() {
        return "note: this chest is inside a structure's bounds; registered by explicit request.";
    }

    /** Component variant of {@link #structureOverrideNote()} for the player-facing path. */
    public static Component structureOverrideNoteComponent() {
        return Component.translatable("message.playerengine.elliegps.structure_override_note");
    }

    // -------------------------------------------------------------------------
    // Corrupt-store quarantine (Decision 14)
    // -------------------------------------------------------------------------

    /**
     * One-time operator note surfaced by the first EllieGPS command after a corrupt
     * {@code waypoints.json} was quarantined (paired with
     * {@code EllieGPSStore.consumeQuarantineNote()}).
     */
    public static String quarantineNote() {
        return "EllieGPS: previous waypoints.json was corrupt and quarantined; store started empty.";
    }

    /** Component variant of {@link #quarantineNote()} for the player-facing path. */
    public static Component quarantineNoteComponent() {
        return Component.translatable("message.playerengine.elliegps.quarantine_note");
    }

    // -------------------------------------------------------------------------
    // Description polish
    // -------------------------------------------------------------------------

    /**
     * Appended to the model's create/audit feedback when async description polish was
     * scheduled (Decision 7: the model is told the description may be refined).
     */
    public static String descriptionPolishScheduledNote() {
        return "description may be refined asynchronously.";
    }
}
