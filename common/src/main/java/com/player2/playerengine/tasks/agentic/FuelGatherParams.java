package com.player2.playerengine.tasks.agentic;

import net.minecraft.world.item.Item;

/**
 * Validated parameters for a fuel pre-gather (the agentic smelt step's PRE-GATHER, run by the
 * {@code AgenticSmeltTask} wrapper before delegating to {@code SmeltDeferredTask}).
 *
 * <p>Mirrors the clamping convention of {@link MineBlockParams}: numeric fields are clamped at the
 * call site before construction, so this record carries safe values and does not re-validate. The
 * conservative defaults at the {@code SmeltStepFactory} call site are {@code radius=32},
 * {@code timeoutSeconds=60}, {@code maxContainers=1}, {@code climbBudget=1} — a single bounded gather
 * attempt that fails fast rather than deep-crafting.
 *
 * <p><b>NOTE on {@code timeoutSeconds} and {@code climbBudget} (RESERVED / NOT ENFORCED in v1):</b>
 * {@link FuelGatherTask} does not read either field. The single-attempt climb bound is provided by the
 * {@code climbStarted} latch (one {@code CollectPlanksTask}/coal mine per task instance), not by
 * {@code climbBudget}; and no wall-clock timeout is applied to the climb child (the catalogued coal
 * {@code MineAndCollectTask} is uncapped — the coal spiral is severed solely by the held-wood-pickaxe
 * gate, not by a timeout). These two fields are retained for signature/spec parity and as a reserved
 * surface for a future enforced-timeout pass; do not assume they bound behaviour today.
 *
 * @param targetFuelItem the single fuel item to gather (bound by {@code FuelPlanner.deficitFor}; the
 *                       gather never cycles items mid-attempt — it acquires exactly the item the later
 *                       {@code FuelPlanner.plan} re-run will select).
 * @param neededCount    the deficit in ITEM units ({@code DeficitCandidate.deficit}, i.e.
 *                       {@code needed - held}) — how many MORE units to acquire into inventory.
 * @param radius         search radius in blocks for marked/unmarked chests and world acquisition.
 * @param timeoutSeconds RESERVED — NOT enforced in v1 (see class note); kept for spec parity.
 * @param maxContainers  max chest-withdraw attempts per stage (marked / unmarked) before falling through.
 * @param climbBudget    RESERVED — NOT enforced in v1 (the single climb is bounded by the
 *                       {@code climbStarted} latch, not this field); kept for spec parity.
 */
public record FuelGatherParams(
        Item targetFuelItem,
        int neededCount,
        double radius,
        double timeoutSeconds,
        int maxContainers,
        int climbBudget
) {}
