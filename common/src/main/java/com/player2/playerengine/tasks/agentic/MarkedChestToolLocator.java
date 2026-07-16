package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic keyword&rarr;candidate-coordinate resolution over the EllieGPS index and store for the
 * tool-acquisition pipeline's marked-chest stage (WS3, stage b).
 *
 * <p>This is the same static accessor chain the counting service
 * ({@link com.player2.playerengine.agentic.elliegps.EllieGPSWaypointCountingService}) uses, but it
 * returns the navigate-to <em>coordinates</em> of marked inventory waypoints that actually list the
 * target tool registry id, ordered nearest-first. It does NOT call or extend
 * {@code LocateWaypointsCommand} (which only returns chat text).
 *
 * <p><b>Design invariants (HARD):</b>
 * <ul>
 *   <li>100% deterministic, on-device, snapshot-bearing. No model calls. Never throws to the caller
 *       (returns an empty list on any failure).</li>
 *   <li>Navigate-to coordinate is read via {@link WaypointRecord#canonicalBlockPos()} ONLY — never
 *       {@code record.secondaryPos} / {@link WaypointRecord#secondaryBlockPos()} (that is only a
 *       double chest's second half, not the navigate target).</li>
 *   <li>Same filter as the counting service: inventory-type, non-stale, snapshot-bearing, matching
 *       dimension, within radius, and the snapshot must list the tool registry id.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 */
final class MarkedChestToolLocator {

    private MarkedChestToolLocator() {}

    /**
     * Resolves candidate marked-chest coordinates that contain {@code tool}, nearest-first.
     *
     * @param controller   controller used for one-shot player/model degradation feedback
     * @param tool         the target tool item
     * @param origin       the bot position the search is anchored at
     * @param radiusBlocks the search radius
     * @param dimensionId  the bot's current dimension id ({@code level.dimension().location().toString()});
     *                     {@code null}/blank yields an empty result (conservative — never cross dimensions)
     * @return navigate-to {@link BlockPos} list ordered nearest-first; never {@code null}, possibly empty
     */
    static List<BlockPos> candidateCoordinates(
            PlayerEngineController controller,
            Item tool,
            Vec3 origin,
            double radiusBlocks,
            String dimensionId) {
        return MarkedChestItemLocator.candidateCoordinates(
                controller, tool, origin, radiusBlocks, dimensionId);
    }
}
