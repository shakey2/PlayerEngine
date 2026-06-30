package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.tasks.entity.HeroTask;

public class HeroCommand extends Command {
   public HeroCommand() {
      super("hero", "Kill all hostile mobs");
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      mod.runUserTaskTracked(
         "hero", "hero",
         new HeroTask(), RollbackPolicy.NONE,
         () -> this.finish()   // hero has no "target gone" analog; any terminal state is a clean finish.
      );
   }
}
