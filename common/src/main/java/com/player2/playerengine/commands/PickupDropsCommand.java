package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.agentic.AgenticDegradationSummary;
import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.steps.GatherLooseItemsParams;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.tasks.agentic.GatherLooseItemsTask;
import java.util.List;
import java.util.Optional;
public class PickupDropsCommand extends Command {
   public PickupDropsCommand() throws CommandException {
      super(
         "pickup_drops",
         "picks up all item drops nearby"
      );
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      var settings = mod.getModSettings();
      GatherLooseItemsParams params = new GatherLooseItemsParams(
            settings.getGatherLooseItemsRadius(),
            settings.getGatherLooseItemsMaxItems(),
            256,
            settings.getGatherLooseItemsSettleSeconds(),
            settings.getGatherLooseItemsTimeoutSeconds(),
            true,
            List.of());
      // Throwaway run-state (NOT registered) so GatherLooseItemsTask can record its PARTIAL signal,
      // letting us surface a truthful partial-pickup note via the shared WS2 wording.
      AgenticRunRegistry.AgenticRunState runState =
            new AgenticRunRegistry.AgenticRunState("pickup", "pickup_drops", "command");
      mod.runUserTaskTracked(
            "pickup-drops",
            "gather_loose_items",
            new GatherLooseItemsTask(params, runState),
            RollbackPolicy.NONE,
            () -> this.onPickupComplete(mod, runState));
   }

   /**
    * Tracked-step completion gate. A genuine FATAL failure is reported to the model via
    * {@link #finishWithError}; a recorded PARTIAL gather is surfaced truthfully to both player and
    * model via {@link #finishWithNote}; lifecycle stops and clean success route to {@link #finish}.
    */
   private void onPickupComplete(PlayerEngineController mod, AgenticRunRegistry.AgenticRunState runState) {
      boolean genuineFailure = false;
      if (mod.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
         Optional<StepExecution> execOpt = adapter.getLastCompletedExecution();
         genuineFailure = execOpt.map(e ->
               e.getState() == StepState.FAILED && e.getLastLogEntry().contains(StopReason.FATAL.name())
         ).orElse(false);
      }
      if (genuineFailure) {
         this.finishWithError("gather failed: no reachable drops");
      } else if (runState.getGatherDegradation() != AgenticRunRegistry.DegradationLevel.CLEAN) {
         this.finishWithNote(AgenticDegradationSummary.forModel(runState));
      } else {
         this.finish();
      }
   }
}

