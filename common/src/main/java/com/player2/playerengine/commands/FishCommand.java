package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.misc.FishTask;
import java.util.function.Consumer;

public class FishCommand extends Command {
   // U+2014 EM DASH, built from an ASCII-only source expression to avoid a non-ASCII
   // literal in this file (PS 5.1 UTF-8 round-trip mojibake guard).
   private static final String EM_DASH = String.valueOf((char) 0x2014);

   public FishCommand() throws CommandException {
      super("fish", "Starts fishing automatically.  Example: `fish` to start fishing. NEEDS FISHING ROD");
   }

   @Override
   protected void call(PlayerEngineController controller, ArgParser parser) throws CommandException {
      Consumer<String> onCannotStart = reason -> {
         controller.reportAgenticProgress(
            "I can't fish " + EM_DASH + " I don't have a fishing rod. "
            + "Give me one (or ask me to get/craft one) first.", true);
         this.finishWithError("Cannot fish: no fishing rod in inventory. "
            + "The bot did not fish. Give the bot a fishing rod first.");
      };
      controller.runUserTask(new FishTask(onCannotStart), () -> this.finish());
   }
}
