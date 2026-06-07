package com.player2.playerengine.agentic.steps;

import java.util.List;

/**
 * Parsed args for the C3 {@code deposit_items} step.
 *
 * <p>The selection is driven entirely by these params (the planner/fallback supplies explicit ids
 * or relies on the deposit-all default). Gathered-item ids from C1 are not threaded through
 * execution memory today, so {@code DepositItemsTask} never depends on them.
 *
 * @param itemIds       explicit ids to deposit (lowercase, e.g. {@code "minecraft:cobblestone"});
 *                      when non-empty these take precedence over {@code depositAll}.
 * @param depositAll    when true and {@code itemIds} is empty, deposit all non-essential stacks.
 * @param keepTools     when depositing all, exclude tools/armor/weapons.
 * @param timeoutSeconds absolute deadline for the deposit step.
 */
public record DepositItemsParams(
        List<String> itemIds,
        boolean depositAll,
        boolean keepTools,
        double timeoutSeconds
) {}
