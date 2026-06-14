package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.util.ItemTarget;
import java.util.Optional;

public final class CraftMacroTasks {
   private CraftMacroTasks() {
   }

   public static ResourceTask tryCreateMacroTask(PlayerEngineController mod, ItemTarget target) {
      if (!CraftMacroSupport.isMacroEnabled(mod) || !CraftMacroSupport.isSupportedTarget(target)) {
         return null;
      }
      // Explicit-sign funding gate (owner rule 4): runs ONCE here at task creation, BEFORE the plan is
      // built and the species pin set (replan calls CraftMacroPlanner.plan directly and never re-enters
      // this). Non-sign and bare-"sign" targets pass through untouched (chest flow unchanged). On a
      // substitution the notice reaches BOTH audiences (DESIGN.md S3): the player via the owner-scoped
      // chat line NOW, the model via the task's creation note merged into the command-completion
      // feedback by GetCommand.onGetComplete.
      CraftMacroSupport.SignRequestAdjustment adj = CraftMacroSupport.adjustSignRequestForFunding(mod, target);
      return CraftMacroPlanner.plan(mod, adj.target())
            .map(plan -> {
               CraftMacroResourceTask task = new CraftMacroResourceTask(plan);
               if (adj.notice() != null && !adj.notice().isBlank()) {
                  task.setCreationNote(adj.notice());
                  mod.reportAgenticProgress(adj.notice(), true);
               }
               return task;
            })
            .orElse(null);
   }
}
