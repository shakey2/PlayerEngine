package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.ItemList;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.cooking.SmeltDeferredParams;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask.Outcome;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Standalone {@code smelt <item> [count]} command — the deferred-smelting trigger surface (WS9).
 *
 * <p>Resolves a furnace/blast/smoke recipe for the requested item, then runs the shared
 * {@link SmeltDeferredTask} as a tracked user step. The task locates a furnace, loads input + fuel,
 * parks-and-waits on the live furnace Block Entity (never a wall-clock timer), parks-and-resumes if
 * the bot wanders off, then collects the output. The furnace BE is the sole source of truth for
 * completion.
 *
 * <p><b>Dual-audience reporting (DESIGN.md §3).</b> Every terminal outcome reaches BOTH the player
 * (a concise chat line via {@code reportAgenticProgress(msg, true)}) AND the model (via
 * {@code finishWithInfo}/{@code finishWithNote}/{@code finishWithError}). On every terminal path the
 * player line fires FIRST, then the model-facing finish (canonical order, mirroring {@code GetCommand}).
 * The single {@link Outcome} the task records is the shared source for both surfaces; the agentic
 * {@code smelt_items} step routes the SAME outcome classes through the run-state smelt slot instead.
 */
public class SmeltCommand extends Command {

    /** Stateless cooking recipe resolver (mirrors the {@code RecipeAccessImpl} singleton pattern). */
    private static final CookingRecipeAccess COOKING_RECIPE_ACCESS = new CookingRecipeAccessImpl();

    public SmeltCommand() throws CommandException {
        super(
                "smelt",
                "smelt <item> [count]. Smelt, blast, or smoke an item using a nearby furnace, blast"
                        + " furnace, or smoker. The companion walks to the furnace, loads the input plus"
                        + " enough fuel (charcoal/coal/planks preferred, sized from the recipe), waits while"
                        + " it cooks, then collects the result — you do not have to babysit it. The output is"
                        + " resolved from the recipe registry, so any smeltable raw material works (e.g. raw"
                        + " iron -> iron ingot, raw food -> cooked food). Count defaults to 1."
                        + " Examples: `smelt iron 16`, `smelt raw_iron 32`, `smelt potato 8`.",
                new Arg<>(ItemList.class, "item"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        PlayerEngineSettings settings = mod.getModSettings();
        if (!settings.isEnableDeferredSmelt()) {
            String msg = "Deferred smelting is disabled in the configuration.";
            mod.reportAgenticProgress(msg, true);
            this.finishWithError(msg);
            return;
        }

        ItemList items;
        try {
            items = parser.get(ItemList.class);
        } catch (CommandException e) {
            // Input-gate rejection (item does not exist / invalid name): player line BEFORE rethrow so
            // both audiences see it. The model gets the standard "smelt FAILED" InfoMessage via the
            // executor's error route (DESIGN.md §3 / AGENTS.md dual-audience requirement).
            mod.reportAgenticProgress(e.getMessage(), true);
            throw e;
        }

        if (items.items == null || items.items.length != 1) {
            String msg = "Specify exactly one item to smelt, e.g. `smelt iron 16`.";
            mod.reportAgenticProgress(msg, true);
            this.finishWithError(msg);
            return;
        }
        ItemTarget target = items.items[0];
        Item input = target.getMatches().length == 1 ? target.getMatches()[0] : null;
        if (input == null) {
            String msg = "Specify a single concrete item to smelt, e.g. `smelt raw_iron 16`.";
            mod.reportAgenticProgress(msg, true);
            this.finishWithError(msg);
            return;
        }

        int requested = target.getTargetCount();
        int maxBatch = settings.getDeferredSmeltMaxBatch();
        int count = Math.max(1, Math.min(maxBatch, requested));

        // Pre-flight recipe resolve so a non-smeltable input is rejected truthfully BEFORE the bot
        // moves (mirrors GetCommand's early rejection). The task re-resolves at LOAD; this is only a
        // fast-fail gate so we don't navigate to a furnace for an item that can never cook.
        ServerLevel world = mod.getWorld();
        if (world != null) {
            Optional<CookingRecipeAccess.CookResolution> resolved =
                    COOKING_RECIPE_ACCESS.resolveAny(world.getRecipeManager(), input, world.registryAccess());
            if (resolved.isEmpty()) {
                String name = displayName(input);
                String msg = "Can't smelt " + name + ": it has no furnace, blast furnace, or smoker recipe.";
                mod.reportAgenticProgress(msg, true);
                this.finishWithError(msg);
                return;
            }
        }

        SmeltDeferredParams params = new SmeltDeferredParams.Builder(input, count)
                .stallPolls(settings.getDeferredSmeltStallPolls())
                .timeoutSeconds(settings.getDeferredSmeltTimeoutSeconds())
                .build();
        final SmeltDeferredTask task = new SmeltDeferredTask(params);
        mod.runUserTaskTracked(
                buildStepId(input, count),
                "smelt_items",
                task,
                RollbackPolicy.NONE,
                () -> onSmeltComplete(mod, task));
    }

    /**
     * Tracked-step completion gate: maps the task's single {@link Outcome} to the dual-audience
     * report. The player line fires FIRST (milestone, bypasses the throttle), then the model-facing
     * finish: clean success -> {@code finishWithInfo}; partial/tampered/soft-stall -> {@code finishWithNote};
     * hard failures (no fuel, furnace gone, timeout, no recipe, setup) -> {@code finishWithError}.
     */
    private void onSmeltComplete(PlayerEngineController mod, SmeltDeferredTask task) {
        Outcome o = task.outcome();
        if (o == null) {
            // The step ended without recording an outcome (interrupted / superseded). Plain finish —
            // a lifecycle stop is not a smelt failure.
            this.finish();
            return;
        }
        String playerLine = playerLine(o);
        mod.reportAgenticProgress(playerLine, true);
        switch (o.kind()) {
            case CLEAN_SUCCESS ->
                    this.finishWithInfo("smelted " + o.collected() + " " + o.outputName()
                            + posSuffix(o));
            case PARTIAL_OUT_OF_FUEL ->
                    this.finishWithNote("partial: smelted " + o.collected() + " of " + o.expected()
                            + " " + o.outputName() + "; out of fuel");
            case TAMPERED ->
                    this.finishWithNote("tampered: expected " + o.expected() + ", collected "
                            + o.collected() + " " + o.outputName());
            case STALLED_RETRYING ->
                    // v1 has NO automatic resume scheduler — be honest that the operator must
                    // re-issue the smelt near the furnace to collect what finished. Do NOT promise
                    // the bot will finish on its own (DESIGN.md §3 — never invent certainty).
                    this.finishWithNote("stalled: the furnace chunk stopped ticking; collected "
                            + o.collected() + " of " + o.expected() + " " + o.outputName()
                            + " — run `smelt` again near the furnace to finish the rest");
            case FURNACE_IN_USE ->
                    this.finishWithError("furnace in use: the furnace already had items in its input"
                            + " or fuel slot" + posSuffix(o) + " — nothing loaded");
            case NO_FUEL ->
                    this.finishWithError("no fuel: needed about " + o.fuelNeeded()
                            + " fuel to smelt " + o.expected() + " " + o.outputName());
            case STALLED_TIMEOUT ->
                    this.finishWithError("stalled timeout: the furnace chunk stopped ticking and the "
                            + "job timed out (collected " + o.collected() + " of " + o.expected() + ")");
            case FURNACE_GONE ->
                    this.finishWithError("furnace removed" + posSuffix(o)
                            + " — collected " + o.collected() + " of " + o.expected());
            case NO_RECIPE ->
                    this.finishWithError("no recipe: that item has no furnace/blast/smoke recipe");
            case SETUP_FAILED ->
                    this.finishWithError("could not start the smelt (" + o.reasonLabel() + ")");
        }
    }

    /** Concise, human-readable player chat line for each terminal outcome. */
    private static String playerLine(Outcome o) {
        String out = displayName(o.outputItem());
        return switch (o.kind()) {
            case CLEAN_SUCCESS -> "Smelted " + o.collected() + " x " + out + ".";
            case PARTIAL_OUT_OF_FUEL -> "Smelted " + o.collected() + " of " + o.expected() + " x "
                    + out + " — ran out of fuel.";
            case NO_FUEL -> "Couldn't smelt — no fuel available (need about " + o.fuelNeeded() + ").";
            case TAMPERED -> "The furnace contents changed while I was away — collected what I could ("
                    + o.collected() + ").";
            case FURNACE_GONE -> "The furnace I was using is gone — couldn't finish.";
            case STALLED_RETRYING -> "The furnace chunk stopped ticking, so it can't finish on its own. I"
                    + " smelted " + o.collected() + " of " + o.expected() + " — ask me to smelt again near"
                    + " the furnace to finish the rest.";
            case STALLED_TIMEOUT -> "Gave up smelting — the furnace chunk wasn't ticking and the job timed out.";
            case FURNACE_IN_USE -> "That furnace already has something in it — I didn't load anything to"
                    + " avoid mixing batches.";
            case NO_RECIPE -> "I can't smelt that — it has no furnace, blast furnace, or smoker recipe.";
            case SETUP_FAILED -> "Couldn't start the smelt (" + o.reasonLabel() + ").";
        };
    }

    private static String posSuffix(Outcome o) {
        return o.furnacePos() != null
                ? " at " + o.furnacePos().getX() + " " + o.furnacePos().getY() + " " + o.furnacePos().getZ()
                : "";
    }

    private static String displayName(Item item) {
        if (item == null) {
            return "that item";
        }
        return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }

    private static String buildStepId(Item input, int count) {
        return "smelt:" + BuiltInRegistries.ITEM.getKey(input).getPath() + "x" + count;
    }
}
