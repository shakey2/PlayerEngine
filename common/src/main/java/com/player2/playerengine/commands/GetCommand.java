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
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GetCommand extends Command {
   public GetCommand() throws CommandException {
      super(
         "get",
         "Get a resource or Craft an item in Minecraft. You can craft item even if you don't have ingredients in inventory already. Examples: `get log 20` gets 20 logs, `get diamond_chestplate 1` gets 1 diamond chestplate. For equipments you have to specify the type of equipments like wooden, stone, iron, golden and diamond. For wooden items, prefer the GENERIC name — `get sign 1`, `get planks 4`, `get log 2`, NOT `get oak_sign 1` — so the best wood species actually available nearby is chosen automatically; only name a species if that exact wood is required.",
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
         this.finishWithError(joinNotes(creationNote, getFailureReason(trackedTask)));
      } else if (terminationReason != null || creationNote != null) {
         // Non-FATAL termination/degradation of a craft macro (e.g. under-gathered, unobtainable
         // wood, partial completion, a creation-time species substitution): route the recorded
         // reason(s) through the SUCCESS-with-note path so the model's command-completion feedback
         // carries the specific reason ("... finished running, but: <reason>.") instead of a generic
         // success the AI could mistake for an unqualified full completion (DESIGN.md S3 dual-audience
         // reporting). Blank/absent reasons degrade to a plain finish, byte-identical to a clean
         // success.
         this.finishWithNote(joinNotes(creationNote, terminationReason));
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

   private Task resolveTask(PlayerEngineController mod, ItemTarget... items) {
      if (items.length == 1) {
         Task macro = CraftMacroTasks.tryCreateMacroTask(mod, items[0]);
         if (macro != null) {
            return macro;
         }
         return TaskCatalogue.getItemTask(items[0]);
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
      ItemList items = parser.get(ItemList.class);
      this.getItems(mod, items.items);
   }
}
