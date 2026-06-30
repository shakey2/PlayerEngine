package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.ItemList;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.smithing.SmithDeferredParams;
import com.player2.playerengine.tasks.smithing.SmithDeferredTask;
import com.player2.playerengine.tasks.smithing.SmithDeferredTask.Outcome;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Standalone {@code smith <item> [count]} command — the generic smithing-upgrade trigger surface
 * (WS3 of the Generic Smithing Recipe Resolution plan).
 *
 * <p>Resolves a {@code SmithingTransformRecipe} for the requested output item, then runs the
 * shared {@link SmithDeferredTask} as a tracked user step. The task gathers the required
 * template, base, and addition items via the existing smithing-table primitive
 * ({@code UpgradeInSmithingTableTask}), performs the upgrade at a smithing table, and records
 * a single typed {@link Outcome}.
 *
 * <p>Item identifiers are resolved deterministically through the existing {@link ItemList} /
 * {@code DescriptionIdIndex} path — modded lang-keys and bare modded paths are accepted without
 * any AI-retry call.
 *
 * <p><b>Dual-audience reporting (DESIGN.md §3).</b> Every terminal outcome reaches BOTH the
 * player (a concise chat line via {@code reportAgenticProgress(msg, true)}) AND the model (via
 * {@code finishWithInfo}/{@code finishWithNote}/{@code finishWithError}). On every terminal path
 * the player line fires FIRST, then the model-facing finish. The single {@link Outcome} the task
 * records is the shared source for both surfaces; the agentic {@code smith_items} step routes the
 * same outcome classes through the run-state smith slot instead.
 */
public class SmithCommand extends Command {

    /** Stateless smithing recipe resolver (mirrors the {@code COOKING_RECIPE_ACCESS} singleton). */
    private static final SmithingRecipeAccess SMITHING_RECIPE_ACCESS = new SmithingRecipeAccessImpl();

    public SmithCommand() throws CommandException {
        super(
                "smith",
                "smith <item> [count]. Upgrade an item at a smithing table. The companion resolves"
                        + " the required template, base, and addition from the recipe registry, gathers"
                        + " all three ingredients, walks to a smithing table, and performs the upgrade —"
                        + " no hardcoded netherite heuristic. Works for any registered SmithingTransformRecipe,"
                        + " including modded upgrades. Count defaults to 1."
                        + " Examples: `smith netherite_pickaxe`, `smith netherite_chestplate 1`.",
                new Arg<>(ItemList.class, "item"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        PlayerEngineSettings settings = mod.getModSettings();
        if (!settings.isEnableSmithing()) {
            // Player sees the localized form; model keeps a fixed English token (cardinal rule).
            mod.reportAgenticProgress(Component.translatable("message.playerengine.smith.disabled"), true);
            this.finishWithError("Smithing is disabled in the configuration.");
            return;
        }

        ItemList items;
        try {
            items = parser.get(ItemList.class);
        } catch (CommandException e) {
            // Input-gate rejection (item does not exist / invalid name): player line BEFORE rethrow
            // so both audiences see it. The model gets the standard "smith FAILED" InfoMessage via
            // the executor's error route (DESIGN.md §3 / AGENTS.md dual-audience requirement).
            mod.reportAgenticProgress(e.getMessage(), true);
            throw e;
        }

        if (items.items == null || items.items.length != 1) {
            // Player sees the localized form; model keeps a fixed English token (cardinal rule).
            mod.reportAgenticProgress(Component.translatable("message.playerengine.smith.invalid_args_count"), true);
            this.finishWithError("Specify exactly one item to upgrade, e.g. `smith netherite_pickaxe`.");
            return;
        }
        ItemTarget target = items.items[0];
        Item output = target.getMatches().length == 1 ? target.getMatches()[0] : null;
        if (output == null) {
            // Player sees the localized form; model keeps a fixed English token (cardinal rule).
            mod.reportAgenticProgress(Component.translatable("message.playerengine.smith.invalid_args_concrete"), true);
            this.finishWithError("Specify a single concrete item to upgrade, e.g. `smith netherite_pickaxe`.");
            return;
        }

        int requested = target.getTargetCount();
        int maxBatch = settings.getSmithMaxBatch();
        int count = Math.max(1, Math.min(maxBatch, requested));

        // Pre-flight recipe resolve so a non-smithable output is rejected truthfully BEFORE the
        // bot moves (mirrors SmeltCommand's early rejection gate). The task re-resolves at RESOLVE
        // phase; this is only a fast-fail gate so we don't navigate to a smithing table for an
        // item that has no transform recipe.
        ServerLevel world = mod.getWorld();
        if (world != null) {
            Optional<SmithingRecipeAccess.SmithingResolution> resolved =
                    SMITHING_RECIPE_ACCESS.resolve(world.getRecipeManager(), output, world.registryAccess());
            if (resolved.isEmpty()) {
                String name = displayName(output);
                // Player sees the localized form; model keeps a fixed English token (cardinal rule).
                mod.reportAgenticProgress(Component.translatable("message.playerengine.smith.no_recipe_preflight", name), true);
                this.finishWithError("Can't upgrade " + name
                        + ": nothing in the smithing registry produces it.");
                return;
            }
        }

        SmithDeferredParams params = new SmithDeferredParams.Builder(output, count)
                .gatherBudget(400)
                .pollCap(12000)
                .build();
        final SmithDeferredTask task = new SmithDeferredTask(params);
        mod.runUserTaskTracked(
                buildStepId(output, count),
                "smith_items",
                task,
                RollbackPolicy.NONE,
                () -> onSmithComplete(mod, task));
    }

    /**
     * Tracked-step completion gate: maps the task's single {@link Outcome} to the dual-audience
     * report. The player line fires FIRST (milestone, bypasses the throttle), then the
     * model-facing finish: clean success -> {@code finishWithInfo}; partial/tampered ->
     * {@code finishWithNote}; hard failures (no recipe, no table, missing inputs, setup) ->
     * {@code finishWithError}.
     */
    private void onSmithComplete(PlayerEngineController mod, SmithDeferredTask task) {
        Outcome o = task.outcome();
        if (o == null) {
            // The step ended without recording an outcome (interrupted / superseded). Plain
            // finish — a lifecycle stop is not a smith failure.
            this.finish();
            return;
        }
        Component playerLine = playerLine(o);
        mod.reportAgenticProgress(playerLine, true);
        switch (o.kind()) {
            case CLEAN_SUCCESS ->
                    this.finishWithInfo("upgraded " + o.collected() + " x " + o.outputName());
            case PARTIAL ->
                    this.finishWithNote("partial: upgraded " + o.collected() + " of " + o.expected()
                            + " x " + o.outputName() + "; some inputs could not be gathered");
            case TAMPERED ->
                    this.finishWithNote("tampered: expected " + o.expected() + " upgrades, completed "
                            + o.collected() + " x " + o.outputName()
                            + " — inventory changed unexpectedly mid-run");
            case NO_RECIPE ->
                    this.finishWithError("no recipe: nothing in the smithing registry produces "
                            + o.outputName());
            case NO_TABLE ->
                    this.finishWithError("no smithing table: no reachable or placeable smithing table"
                            + " was found");
            case MISSING_TEMPLATE ->
                    this.finishWithError("missing template: " + o.reasonLabel()
                            + " — could not gather the required template item");
            case MISSING_BASE ->
                    this.finishWithError("missing base: " + o.reasonLabel()
                            + " — could not gather the required base item to upgrade");
            case MISSING_ADDITION ->
                    this.finishWithError("missing addition: " + o.reasonLabel()
                            + " — could not gather the required upgrade material");
            case SETUP_FAILED ->
                    this.finishWithError("could not start the upgrade (" + o.reasonLabel() + ")");
        }
    }

    /** Concise, human-readable player chat line for each terminal outcome. */
    private static Component playerLine(Outcome o) {
        String out = displayName(o.outputItem());
        return switch (o.kind()) {
            case CLEAN_SUCCESS -> Component.translatable(
                    "message.playerengine.smith.result.success", o.collected(), out);
            case PARTIAL -> Component.translatable(
                    "message.playerengine.smith.result.partial", o.collected(), o.expected(), out);
            case TAMPERED -> Component.translatable(
                    "message.playerengine.smith.result.tampered", o.collected(), o.expected(), out);
            case NO_RECIPE -> Component.translatable(
                    "message.playerengine.smith.result.no_recipe", out);
            case NO_TABLE -> Component.translatable(
                    "message.playerengine.smith.result.no_table", out);
            case MISSING_TEMPLATE -> Component.translatable(
                    "message.playerengine.smith.result.missing_template", out, o.reasonLabel());
            case MISSING_BASE -> Component.translatable(
                    "message.playerengine.smith.result.missing_base", out, o.reasonLabel());
            case MISSING_ADDITION -> Component.translatable(
                    "message.playerengine.smith.result.missing_addition", out, o.reasonLabel());
            case SETUP_FAILED -> Component.translatable(
                    "message.playerengine.smith.result.setup_failed", out, o.reasonLabel());
        };
    }

    private static String displayName(Item item) {
        if (item == null) {
            return "that item";
        }
        return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }

    private static String buildStepId(Item output, int count) {
        return "smith:" + BuiltInRegistries.ITEM.getKey(output).getPath() + "x" + count;
    }
}
