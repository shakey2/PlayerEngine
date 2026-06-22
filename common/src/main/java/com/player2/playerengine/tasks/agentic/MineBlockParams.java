package com.player2.playerengine.tasks.agentic;

/**
 * Validated parameters for a {@code mine_block} agentic step.
 *
 * <p>All numeric fields are pre-clamped by {@code MineBlockStepFactory} before construction,
 * so the record carries safe values and does not re-validate.
 *
 * @param blockId              Registry id (e.g. {@code minecraft:stone}) or tag token
 *                             (e.g. {@code mineable/pickaxe}) identifying the target block.
 *                             Required; factory rejects blank values.
 * @param maxBlocks            Maximum number of blocks to break in one step (clamped 1..256).
 * @param radius               Radius in blocks to search for the target (clamped 4.0..128.0).
 * @param timeoutSeconds       Overall step timeout; partial-success on expiry if any block was
 *                             mined (clamped 10.0..600.0).
 * @param toolAcquireMaxContainers Max chest withdraw attempts across acquisition stages (b)+(c)
 *                             before falling through to the material-chain climb (clamped 1..32).
 * @param toolAcquireClimbBudget   Max material-chain climb attempts (cycle guard) before
 *                             definitive {@code no_tool_acquisition_path} failure (clamped 1..16).
 */
public record MineBlockParams(
        String blockId,
        int maxBlocks,
        double radius,
        double timeoutSeconds,
        int toolAcquireMaxContainers,
        int toolAcquireClimbBudget
) {}
