package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.ItemList;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.player2api.AgentCommandUtils;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

public class GetCommand extends Command {

   /** Stateless smithing recipe resolver used for the NO_CRAFTING_RECIPE redirect (WS5). */
   private static final SmithingRecipeAccess SMITHING_RECIPE_ACCESS = new SmithingRecipeAccessImpl();

   public GetCommand() throws CommandException {
      super(
         "get",
         "Crafts or gathers any item that has a vanilla or modded crafting recipe. Accepts any registered item name. For MODDED items you MUST use the full registry id in the form namespace:path — e.g. `get iceandfire:podium_oak 1`, NOT a bare name and NOT the translation/lang key you see in inventory (which looks like `block.iceandfire.podium_oak`). Bare names work only for vanilla items (e.g. `get diamond 1`). If you only know an item's display/lang key, get its real registry id from `inspect ITEM <id>` or an advanced tooltip (F3+H). You can craft an item even if you don't have ingredients in inventory already. Examples: `get log 20` gets 20 logs, `get diamond_chestplate 1` gets 1 diamond chestplate, `get iceandfire:podium_oak 1` crafts 1 modded podium. For equipments you have to specify the type of equipments like wooden, stone, iron, golden and diamond. For wooden items, prefer the GENERIC name — `get sign 1`, `get planks 4`, `get log 2`, NOT `get oak_sign 1` — so the best wood species actually available nearby is chosen automatically; only name a species if that exact wood is required.",
         new Arg<>(ItemList.class, "items")
      );
   }

   private void getItems(PlayerEngineController mod, ItemTarget... items) {
      items = AgentCommandUtils.addPresentItemsToTargets(mod, items);
      if (items != null && items.length != 0) {
         Task targetTask = this.resolveTask(mod, items);
         if (targetTask != null) {
            final Task trackedTask = targetTask;
            mod.runUserTaskTracked(
                  buildStepId(items),
                  "get_items",
                  targetTask,
                  RollbackPolicy.NONE,
                  () -> this.onGetComplete(mod, trackedTask));
         } else {
            this.finish();
         }
      } else {
         mod.log("You must specify at least one item!");
         this.finish();
      }
   }

   /**
    * Tracked-step completion gate: a genuine FATAL failure of the get/craft step is reported to the
    * model via {@link #finishWithError}; lifecycle stops (operator @stop, superseded, not-in-game) and
    * clean success route to {@link #finish} (model no-op).
    */
   private void onGetComplete(PlayerEngineController mod, Task trackedTask) {
      boolean genuineFailure = false;
      if (mod.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
         Optional<StepExecution> execOpt = adapter.getLastCompletedExecution();
         genuineFailure = execOpt.map(e ->
               e.getState() == StepState.FAILED && e.getLastLogEntry().contains(StopReason.FATAL.name())
         ).orElse(false);
      }
      // Creation-time degradation note (e.g. the explicit-sign species substitution): the player got
      // the chat line at task creation; HERE is where the model learns of it, merged ahead of any
      // termination reason in every outcome path so the AI can answer truthfully about what was
      // actually crafted (DESIGN.md S3 dual-audience reporting).
      String creationNote = null;
      String terminationReason = null;
      if (trackedTask instanceof CraftMacroResourceTask macro) {
         creationNote = blankToNull(macro.getCreationNote());
         terminationReason = blankToNull(macro.getFailureReason());
      }
      if (genuineFailure) {
         // Player channel: deliver the failure reason as a milestone chat line BEFORE routing
         // the model channel, so neither audience misses the cause (DESIGN.md §3 / AGENTS.md
         // dual-audience requirement). reportAgenticProgress(milestone=true) bypasses the
         // interval throttle so this line is never swallowed by the rate limit.
         String failureMsg = joinNotes(creationNote, getFailureReason(trackedTask));
         mod.reportAgenticProgress(failureMsg, true);
         this.finishWithError(failureMsg);
      } else if (terminationReason != null || creationNote != null) {
         // Non-FATAL termination/degradation of a craft macro (e.g. under-gathered, unobtainable
         // wood, partial completion, a creation-time species substitution): route the recorded
         // reason(s) through the SUCCESS-with-note path so the model's command-completion feedback
         // carries the specific reason ("... finished running, but: <reason>.") instead of a generic
         // success the AI could mistake for an unqualified full completion (DESIGN.md S3 dual-audience
         // reporting). Blank/absent reasons degrade to a plain finish, byte-identical to a clean
         // success.
         // Player channel: deliver the degradation note before the model-facing finish (DESIGN.md §3).
         String degradeMsg = joinNotes(creationNote, terminationReason);
         mod.reportAgenticProgress(degradeMsg, true);
         this.finishWithNote(degradeMsg);
      } else {
         this.finish();
      }
   }

   private static String blankToNull(String s) {
      return (s == null || s.isBlank()) ? null : s;
   }

   /** Join the creation note and the outcome reason ("; "-separated); either side may be null. */
   private static String joinNotes(String first, String second) {
      if (first == null) {
         return second;
      }
      if (second == null) {
         return first;
      }
      return first + "; " + second;
   }

   /** Prefer the macro task's recorded reason; otherwise a clear generic the model can act on. */
   private static String getFailureReason(Task trackedTask) {
      if (trackedTask instanceof CraftMacroResourceTask macro) {
         String reason = macro.getFailureReason();
         if (reason != null && !reason.isBlank()) {
            return reason;
         }
      }
      return "could not obtain the requested item(s)";
   }

   /**
    * Resolves the best {@link Task} for the requested items. For a single item, the macro resolver
    * is tried first (macro-first ordering is invariant). The {@link CraftMacroTasks.MacroOutcome}
    * enum distinguishes three outcomes so each is handled truthfully (DESIGN.md §3 / AGENTS.md):
    *
    * <ul>
    *   <li>{@code MACRO_CREATED} — use the task directly.</li>
    *   <li>{@code GATE_DISABLED} — macro is off (user config); fall silently to TaskCatalogue.</li>
    *   <li>{@code NO_CRAFTING_RECIPE} — no crafting recipe exists; fall to TaskCatalogue. If the
    *       catalogue also has nothing, this item has no path at all; emit a truthful deferred-type
    *       rejection to both player and model instead of a silent finish.</li>
    * </ul>
    *
    * <p>Returns {@code null} when no task is resolvable; the caller's {@code getItems} then takes its
    * {@code targetTask == null} branch and calls {@link #finish}. When this method has already emitted a
    * truthful rejection via {@link #finishWithError}, it ALSO returns {@code null}, so that trailing
    * {@code finish()} still runs — the FAILED outcome is preserved ONLY because
    * {@link Command#finish}/{@link Command#finishWithError} are idempotent (the {@code ended} guard makes
    * "whichever fires first wins", so the earlier {@code finishWithError} survives). Do not weaken that
    * idempotency guard without making {@code getItems} skip {@code finish()} on an already-ended command,
    * or the trailing {@code finish()} would overwrite the truthful FAILED with a clean success (a
    * silent-finish / DESIGN.md §3 regression).
    */
   private Task resolveTask(PlayerEngineController mod, ItemTarget... items) {
      if (items.length == 1) {
         CraftMacroTasks.MacroProbe probe = CraftMacroTasks.macroOutcome(mod, items[0]);
         if (probe.outcome() == CraftMacroTasks.MacroOutcome.MACRO_CREATED) {
            return probe.task();
         }
         // GATE_DISABLED or NO_CRAFTING_RECIPE: try the catalogue fallback.
         Task catalogueTask = TaskCatalogue.getItemTask(items[0]);
         if (catalogueTask != null) {
            return catalogueTask;
         }
         // Catalogue also has nothing. Only emit a rejection when we know a recipe was expected
         // but doesn't exist in the crafting system (NO_CRAFTING_RECIPE). GATE_DISABLED with no
         // catalogue task is ambiguous (the user turned the macro off and the item isn't in the
         // catalogue) — emit a generic failure rather than a misleading "furnace" message.
         if (probe.outcome() == CraftMacroTasks.MacroOutcome.NO_CRAFTING_RECIPE) {
            String itemName = items[0].getMatches().length == 1
                  ? ItemHelper.stripItemName(items[0].getMatches()[0])
                  : "that item";
            // WS5: Before emitting the generic "no path" rejection, probe the smithing registry.
            // If a SmithingTransformRecipe produces the requested output, redirect the model to
            // use 'smith' instead of implying there is no path — a truthful, actionable redirect
            // (DESIGN.md §3). The probe is deterministic (registry-backed, no model calls).
            if (items[0].getMatches().length == 1) {
               Item outputItem = items[0].getMatches()[0];
               ServerLevel world = mod.getWorld();
               if (world != null) {
                  Optional<SmithingRecipeAccess.SmithingResolution> smithResolution =
                        SMITHING_RECIPE_ACCESS.resolve(
                              world.getRecipeManager(), outputItem, world.registryAccess());
                  if (smithResolution.isPresent()) {
                     // Smithing-able output: redirect both audiences to 'smith'.
                     String smithRedirect = itemName + " is a smithing-table upgrade — use"
                           + " `smith " + itemName.replace(' ', '_') + "` instead of `get`.";
                     mod.reportAgenticProgress(smithRedirect, true);
                     this.finishWithError(smithRedirect);
                     return null;
                  }
               }
            }
            // Truthful, non-asserting wording: this branch fires for ANY registered item with no
            // crafting-table recipe AND no catalogue task — not only furnace/smithing items (e.g.
            // spawn eggs, command blocks, obtain-only items). State only the known facts (no crafting
            // recipe + no gather/smelt/smith path) without inventing a furnace requirement we have not
            // verified (DESIGN.md §3 / "never invent certainty").
            String rejection = "Cannot craft " + itemName
                  + ": there is no crafting-table recipe for it and no smithing-table"
                  + " transform recipe for it. If it can be smelted or mined, use"
                  + " `smelt` or `mine` directly.";
            // Player channel (milestone = true so the rate limiter never swallows it).
            mod.reportAgenticProgress(rejection, true);
            // Model channel: route through finishWithError so the executor marks the command
            // FAILED and the model receives the explicit rejection in its command-completion
            // InfoMessage rather than a silent success.
            this.finishWithError(rejection);
         } else {
            // GATE_DISABLED with no catalogue task: plain finish (no item, no rejection needed —
            // the model knows macros are disabled if it checks the world snapshot).
            this.finish();
         }
         return null;
      }
      return TaskCatalogue.getSquashedItemTask(items);
   }

   private static String buildStepId(ItemTarget... items) {
      List<String> parts = new ArrayList<>();
      for (ItemTarget target : items) {
         if (target.isCatalogueItem()) {
            parts.add(target.getCatalogueName() + "x" + target.getTargetCount());
         } else if (target.getMatches().length == 1) {
            parts.add(ItemHelper.stripItemName(target.getMatches()[0]) + "x" + target.getTargetCount());
         } else {
            parts.add("multi");
         }
      }
      return "get:" + String.join(",", parts);
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      ItemList items;
      try {
         items = parser.get(ItemList.class);
      } catch (CommandException e) {
         // Input-gate rejection (item does not exist / invalid name): emit a player-facing chat
         // line BEFORE rethrowing so both audiences see the rejection. The model already gets the
         // standard "Command feedback: get FAILED. The error was <msg>." InfoMessage via the
         // executor's error route; this adds the matching human-readable line (DESIGN.md §3 /
         // AGENTS.md dual-audience requirement).
         mod.reportAgenticProgress(e.getMessage(), true);
         throw e;
      }
      this.getItems(mod, items.items);
   }
}
