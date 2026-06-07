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
         "Get a resource or Craft an item in Minecraft. You can craft item even if you don't have ingredients in inventory already. Examples: `get log 20` gets 20 logs, `get diamond_chestplate 1` gets 1 diamond chestplate. For equipments you have to specify the type of equipments like wooden, stone, iron, golden and diamond.",
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
      if (genuineFailure) {
         this.finishWithError(getFailureReason(trackedTask));
      } else {
         this.finish();
      }
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
