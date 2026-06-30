package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.misc.FishTask;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public class FishCommand extends Command {
   public FishCommand() throws CommandException {
      super("fish", "Starts fishing automatically.  Example: `fish` to start fishing. NEEDS FISHING ROD");
   }

   @Override
   protected void call(PlayerEngineController controller, ArgParser parser) throws CommandException {
      Consumer<String> onCannotStart = reason -> {
         // Player channel: send as a translatable Component so the client resolves the locale.
         // Owner resolution mirrors PlayerEngineController#reportAgenticProgress.
         Player owner = controller.getOwner();
         ServerPlayer target;
         if (owner instanceof ServerPlayer sp) {
            target = sp;
         } else {
            target = controller.getClosestPlayer().orElse(null);
         }
         if (target != null) {
            target.displayClientMessage(
               Component.translatable("message.playerengine.fish.no_rod"), false);
         }
         // Model channel (English only — truthfulness).
         this.finishWithError("Cannot fish: no fishing rod in inventory. "
            + "The bot did not fish. Give the bot a fishing rod first.");
      };
      controller.runUserTask(new FishTask(onCannotStart), () -> this.finish());
   }
}
