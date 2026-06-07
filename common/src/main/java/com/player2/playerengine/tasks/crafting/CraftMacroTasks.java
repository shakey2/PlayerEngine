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
      return CraftMacroPlanner.plan(mod, target).map(plan -> new CraftMacroResourceTask(plan)).orElse(null);
   }
}
