package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.tasks.base.Task;

import java.util.List;
import java.util.Optional;

public class StatusCommand extends Command {
   public StatusCommand() {
      super("status", "Get status of currently executing step or last completed step");
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) {
      Optional<StepExecution> execOpt = mod.getStepExecutorAdapter().getActiveExecution();

      if (execOpt.isEmpty()) {
         mod.log("No tasks currently running.");
         this.finish();
         return;
      }

      StepExecution exec = execOpt.get();

      if (exec.getState() == StepState.RUNNING) {
         long secs = exec.getElapsedMs() / 1000L;
         mod.log("ACTIVE: " + exec.getStepKind() + " [RUNNING] (" + secs + "s)");
         List<Task> tasks = mod.getUserTaskChain().getTasks();
         if (!tasks.isEmpty()) {
            mod.log("  Task: " + tasks.get(0).toString());
         }
      } else {
         mod.log("LAST STEP: " + exec.getStepKind() + " [" + exec.getState() + "]");
         for (String entry : exec.getLog()) {
            mod.log("  " + entry);
         }
      }

      this.finish();
   }
}
