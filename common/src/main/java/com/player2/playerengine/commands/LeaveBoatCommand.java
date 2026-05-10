package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;

public class LeaveBoatCommand extends Command {
   public LeaveBoatCommand() throws CommandException {
      super("leaveboat", "If riding a boat (or other vehicle), dismount.");
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      if (mod.getPlayer().isPassenger()) {
         mod.getPlayer().stopRiding();
      }
      this.finish();
   }
}

