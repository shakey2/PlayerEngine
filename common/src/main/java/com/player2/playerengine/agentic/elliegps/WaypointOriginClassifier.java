package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Map;

/**
 * Tiered placement-origin classifier for EllieGPS (Part C5, Decision 4).
 *
 * <p>Evaluates Tiers 2 and 3 of the placement-origin filter against the actual world state.
 * Tier 1 (bot-placed certainty) is handled by the caller, which owns {@code placedByBot}.
 *
 * <h3>Tier order (non-negotiable; Decision 4)</h3>
 * <ol>
 *   <li><b>Tier 2 — un-opened worldgen marker (certain, negative).</b> If the loot-table
 *       field on the container block entity is non-null, the container has never been opened
 *       and is worldgen loot. Return {@link WaypointOriginEvidence.Verdict#REJECT_WORLDGEN}.
 *       Checked on BOTH block entities for double chests.</li>
 *   <li><b>Tier 3 — structure-piece check (heuristic, negative).</b>
 *       {@code structureManager.hasAnyStructureAt(pos)} as a cheap pre-filter; if true, iterate
 *       {@code getAllStructuresAt(pos)} and check piece containment via
 *       {@code getStructureWithPieceAt(pos, structure) != INVALID_START}. Any hit ->
 *       {@link WaypointOriginEvidence.Verdict#REJECT_STRUCTURE}. Checked on BOTH the canonical
 *       and secondary positions — deliberately MORE conservative than the Decision 4 minimum
 *       (canonical only): a double chest straddling a structure-piece boundary is rejected if
 *       either half sits inside the piece bounds. Intentional; keep on the 1.21.1 port.</li>
 *   <li><b>Conservative default:</b> if any evaluation step cannot complete (block entity
 *       missing, chunk unloaded, exception), return
 *       {@link WaypointOriginEvidence.Verdict#UNKNOWN}. Callers treat UNKNOWN as
 *       do-not-register for the auto-hook path.</li>
 * </ol>
 *
 * <h3>Explicit-create override policy (Decision 4)</h3>
 * Explicit {@code create_waypoint} overrides Tier 3 but NEVER Tier 2. The classifier itself
 * does not enforce this — callers (commands) apply the override at their decision point.
 *
 * <h3>Scan-order invariant</h3>
 * This classifier MUST be invoked before any container scan ({@code ContainerScanService.scan},
 * {@code ScanContainerTask}, or any direct {@code getItem()} call). The Tier 2 check reads the
 * loot-table field via {@link WorldgenLootMarker}; a subsequent {@code getItem()} call would
 * call {@code unpackLootTable()}, destroying that marker. At the auto-hook call site in
 * {@link ResolveStorageChestTask}, evidence is captured after target-confirm but before the
 * deposit step that would open the container.
 */
public final class WaypointOriginClassifier {

    private WaypointOriginClassifier() {}

    /**
     * Evaluates the placement origin for the container at {@code canonicalPos}, checking the
     * secondary position {@code secondaryPos} for Tier 2 as well (double chests each have an
     * independent block entity with their own loot-table field).
     *
     * @param level          the server level; must not be null
     * @param canonicalPos   the canonical (primary) position of the container
     * @param secondaryPos   the secondary position for double-chest containers, or {@code null}
     *                       for single-block containers
     * @return a {@link WaypointOriginEvidence} containing the verdict and reason token
     */
    public static WaypointOriginEvidence.Verdict classify(
            ServerLevel level,
            BlockPos canonicalPos,
            BlockPos secondaryPos) {

        try {
            // ----------------------------------------------------------------
            // Tier 2: un-opened worldgen loot-table marker (certain negative)
            // ----------------------------------------------------------------
            // Check canonical position block entity first
            if (WorldgenLootMarker.hasUnopenedLootTable(level, canonicalPos)) {
                PlayerEngine.LOGGER.debug(
                    "EllieGPS: Tier 2 REJECT_WORLDGEN at canonical pos {}",
                    canonicalPos);
                return WaypointOriginEvidence.Verdict.REJECT_WORLDGEN;
            }
            // For double chests, also check the secondary half
            if (secondaryPos != null
                    && WorldgenLootMarker.hasUnopenedLootTable(level, secondaryPos)) {
                PlayerEngine.LOGGER.debug(
                    "EllieGPS: Tier 2 REJECT_WORLDGEN at secondary pos {}",
                    secondaryPos);
                return WaypointOriginEvidence.Verdict.REJECT_WORLDGEN;
            }

            // ----------------------------------------------------------------
            // Tier 3: structure-piece bounding-box check (heuristic negative)
            // ----------------------------------------------------------------
            // There is NO "all structures" tag constant in 1.20.1 StructureTags — the plan
            // explicitly notes this. Use the verified per-structure algorithm:
            //   1. hasAnyStructureAt(pos) as a cheap pre-filter (chunk-reference lookup)
            //   2. getAllStructuresAt(pos) for the map of Structure -> LongSet references
            //   3. getStructureWithPieceAt(pos, structure) for piece-bounds confirmation
            // Any piece hit -> REJECT_STRUCTURE.
            StructureManager sm = level.structureManager();
            if (sm.hasAnyStructureAt(canonicalPos)) {
                Map<Structure, ?> structures = sm.getAllStructuresAt(canonicalPos);
                for (Structure structure : structures.keySet()) {
                    StructureStart start = sm.getStructureWithPieceAt(canonicalPos, structure);
                    if (start != StructureStart.INVALID_START) {
                        PlayerEngine.LOGGER.debug(
                            "EllieGPS: Tier 3 REJECT_STRUCTURE at {} (structure: {})",
                            canonicalPos, structure);
                        return WaypointOriginEvidence.Verdict.REJECT_STRUCTURE;
                    }
                }
            }
            // Also check secondary pos if present (a chest placed at the border of a structure
            // piece could have its secondary half inside the piece bounds)
            if (secondaryPos != null && sm.hasAnyStructureAt(secondaryPos)) {
                Map<Structure, ?> structures = sm.getAllStructuresAt(secondaryPos);
                for (Structure structure : structures.keySet()) {
                    StructureStart start = sm.getStructureWithPieceAt(secondaryPos, structure);
                    if (start != StructureStart.INVALID_START) {
                        PlayerEngine.LOGGER.debug(
                            "EllieGPS: Tier 3 REJECT_STRUCTURE at secondary pos {} (structure: {})",
                            secondaryPos, structure);
                        return WaypointOriginEvidence.Verdict.REJECT_STRUCTURE;
                    }
                }
            }

            // Passed both tiers -> eligible for registration
            PlayerEngine.LOGGER.debug(
                "EllieGPS: classifier REGISTER for pos {}", canonicalPos);
            return WaypointOriginEvidence.Verdict.REGISTER;

        } catch (Exception e) {
            // Conservative default: any evaluation failure -> UNKNOWN (do not register).
            // This covers unloaded chunks, missing block entities, and unexpected MC API changes.
            PlayerEngine.LOGGER.debug(
                "EllieGPS: classifier exception at {} -> UNKNOWN: {}",
                canonicalPos, e.getMessage());
            return WaypointOriginEvidence.Verdict.UNKNOWN;
        }
    }

    /**
     * Builds a {@link WaypointOriginEvidence} from this classifier's result for auto-hook
     * capture in {@link com.player2.playerengine.agentic.AgenticExecutionMemory}.
     *
     * <p>Determines the reason token from the verdict:
     * <ul>
     *   <li>{@link WaypointOriginEvidence.Verdict#REJECT_WORLDGEN} -> {@code "worldgen_loot"}</li>
     *   <li>{@link WaypointOriginEvidence.Verdict#REJECT_STRUCTURE} -> {@code "structure_piece"}</li>
     *   <li>{@link WaypointOriginEvidence.Verdict#UNKNOWN} -> {@code "origin_unverified"}</li>
     *   <li>{@link WaypointOriginEvidence.Verdict#REGISTER} -> {@code ""}</li>
     * </ul>
     *
     * @param level           the server level
     * @param canonicalPos    the canonical position of the container
     * @param secondaryPos    the secondary position (or {@code null})
     * @param captureGameTime the game time at capture (from {@code level.getGameTime()})
     * @return an evidence record for the classifier result
     */
    public static WaypointOriginEvidence buildEvidence(
            ServerLevel level,
            BlockPos canonicalPos,
            BlockPos secondaryPos,
            long captureGameTime) {

        WaypointOriginEvidence.Verdict verdict = classify(level, canonicalPos, secondaryPos);
        String reasonToken = switch (verdict) {
            case REJECT_WORLDGEN  -> "worldgen_loot";
            case REJECT_STRUCTURE -> "structure_piece";
            case UNKNOWN          -> "origin_unverified";
            case REGISTER         -> "";
        };
        return new WaypointOriginEvidence(false, verdict, reasonToken, captureGameTime);
    }
}
