package com.player2.playerengine.agentic;

import com.player2.playerengine.agentic.elliegps.WaypointOriginEvidence;
import java.util.Optional;

/** Per-run agentic execution memory shared across steps in one plan. */
public final class AgenticExecutionMemory {

    // -------------------------------------------------------------------------
    // Storage target (set by ResolveStorageChestTask)
    // -------------------------------------------------------------------------

    private AgenticStorageTarget storageTarget;

    public Optional<AgenticStorageTarget> storageTarget() {
        return Optional.ofNullable(storageTarget);
    }

    public void setStorageTarget(AgenticStorageTarget target) {
        this.storageTarget = target;
    }

    public void clearStorageTarget() {
        this.storageTarget = null;
    }

    // -------------------------------------------------------------------------
    // Waypoint origin evidence (set by WS3 / ResolveStorageChestTask)
    //
    // Captured at target-confirm time, BEFORE any deposit opens the container,
    // so the Tier 2 loot-table marker is never destroyed before evaluation.
    // WS6 reads this in WaypointAutoRegistrar.afterDeposit to decide whether
    // to attempt auto-registration. Stored separately from AgenticStorageTarget
    // so that record's shape is untouched (plan invariant).
    // -------------------------------------------------------------------------

    private WaypointOriginEvidence waypointOriginEvidence;

    /**
     * Returns the captured waypoint origin evidence for the current run, or
     * {@link Optional#empty()} when no evidence has been captured yet.
     */
    public Optional<WaypointOriginEvidence> waypointOriginEvidence() {
        return Optional.ofNullable(waypointOriginEvidence);
    }

    /**
     * Stores the waypoint origin evidence captured at resolve time.
     * Must be called on the server thread before any deposit contact with the container.
     */
    public void setWaypointOriginEvidence(WaypointOriginEvidence evidence) {
        this.waypointOriginEvidence = evidence;
    }

    /**
     * Clears the waypoint origin evidence (called when starting a new run or on cleanup).
     */
    public void clearWaypointOriginEvidence() {
        this.waypointOriginEvidence = null;
    }
}
