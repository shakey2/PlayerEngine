package com.player2.playerengine.agentic.steps;

import java.util.List;

/**
 * Parsed args for the optional best-effort {@code label_chest} step (Part C3, Workstream 3).
 *
 * <p>{@code lines} are optional explicit front-side label lines; when empty and {@code autoLabel}
 * is true the task derives a deterministic template from the storage target. {@code signItemId} is
 * an optional explicit sign item id; when empty the task uses the first {@code SignItem} found in
 * the bot's inventory. The label step always finishes SUCCEEDED, so these are advisory only.
 */
public record LabelChestParams(
        List<String> lines,
        boolean autoLabel,
        String signItemId,
        double timeoutSeconds
) {}
