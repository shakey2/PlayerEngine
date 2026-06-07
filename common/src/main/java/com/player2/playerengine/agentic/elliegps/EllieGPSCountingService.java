package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.util.ItemTarget;
import net.minecraft.world.phys.Vec3;

/**
 * Forward-compatible counting seam for EllieGPS inventory waypoints (Phase C, Part C5).
 *
 * <p><b>Status this round: DOCUMENTED SEAM ONLY.</b> Ships with a no-op implementation
 * ({@link EllieGPSCountingServiceStub}) that always returns {@code 0}. The material-availability
 * counter ({@code common/.../util/helpers/MaterialAvailability.java}) references this type at its
 * source-routing {@code waypointStub} term, marked {@code // TODO(C5): wire EllieGPS source here},
 * so the aggregate compiles and runs today with the EllieGPS term equal to {@code 0}. There is
 * intentionally <b>no</b> {@code PlayerEngineController.getEllieGPSCountingService()} accessor and
 * <b>no</b> {@code AgenticExecutionContext} accessor this round; that dependency injection is C5's
 * job. C5 swaps the stub impl in at startup (not mid-run).
 *
 * <p><b>C5 contract (best-effort, snapshot-only)</b> — see
 * {@code masterplan/phase-c-plan.md} (&sect;"Waypoint model" / "Inventory snapshot rules") and
 * {@code masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md} WS2:
 * <ul>
 *   <li>The count is drawn <b>only</b> from snapshot-bearing waypoints (the optional inline item
 *       snapshot on the C5 inventory-waypoint model). <b>Keyword-only waypoints (no snapshot)
 *       contribute 0 even post-C5</b>, by design.</li>
 *   <li>C5 retrieves waypoints by <b>categorized MinHash keyword similarity</b>, not exact item-id
 *       and not coordinate radius. The C5 impl maps the {@code target}'s items to its category
 *       keywords; {@code target} is an {@link ItemTarget} (not a bare item-id string) so the
 *       aggregate can pass its type-aware target with no client signature change when C5 lands.</li>
 *   <li>{@code origin} and {@code radiusBlocks} are an <b>upper bound</b> on which waypoints are
 *       considered, <b>not</b> the retrieval key. The retrieval key remains keyword similarity.</li>
 *   <li>This term feeds <b>only</b> the source-routing axis ("mine the local source vs wander for a
 *       new one?"); it must <b>never</b> contribute to the sufficiency axis ("am I done?").</li>
 *   <li>The implementation is deterministic, on-device, and non-blocking: it must
 *       <b>never block, never throw, and never make a network/model call</b>; on missing/partial
 *       data it returns a conservative count (degrade visibly, never invent certainty).</li>
 * </ul>
 *
 * <p><b>Intended C5 location:</b> a concrete service in this package
 * ({@code com.player2.playerengine.agentic.elliegps}) backed by the MinHash waypoint index
 * (persisted separately from command/capability RAG). C5 adds the controller/context accessor and
 * swaps the impl at startup. This is a counting service, not an agentic step kind:
 * {@code AgenticSchemas.FORBIDDEN_FUTURE_STEP_KINDS} keeps {@code register_waypoint}/{@code elliegps}
 * non-executable.
 */
public interface EllieGPSCountingService {
    /**
     * Best-effort count of matching items across known nearby inventory waypoints.
     *
     * <p>Per the C5 contract above: counts only snapshot-bearing waypoints whose categorized MinHash
     * keywords match {@code target}; keyword-only waypoints contribute 0 even post-C5.
     * {@code radiusBlocks} bounds which waypoints are considered (not the retrieval key).
     * The stub returns {@code 0}. Must never block and never throw.
     *
     * @param target       the type-aware item target the caller is short on (matched by exact item
     *                     identity via {@link ItemTarget#getMatches()} / {@link ItemTarget#matches};
     *                     C5 maps these items to its category keywords)
     * @param origin       world position the search is anchored at
     * @param radiusBlocks upper bound (in blocks) on which waypoints are considered; a bound, not the
     *                     retrieval key
     * @return a best-effort, non-negative count of matching items in snapshot-bearing waypoints; 0
     *         when unknown or when no snapshot-bearing waypoint matches
     */
    int estimateNearbyWaypointItems(ItemTarget target, Vec3 origin, double radiusBlocks);
}
