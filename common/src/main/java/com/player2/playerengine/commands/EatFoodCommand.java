package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.multiversion.item.ItemVer;
import com.player2.playerengine.tasks.misc.EatFoodTask;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public class EatFoodCommand extends Command {
   public EatFoodCommand() throws CommandException {
      super("eat_food", "Eats food item from your inventory. ONLY CALL IF hunger < 20");
   }

   @Override
   protected void call(PlayerEngineController controller, ArgParser parser) throws CommandException {
      if(controller.getBaritone().getEntityContext().hungerManager().getFoodLevel() >= 20){
         throw new CommandException("Tried to call eatFood, but hunger was too high. You may not eat if hunger is full.");
      }

      if (parser.getArgUnits().length != 1) {
        return;
      }
      String itemNameAsString = parser.getArgUnits()[0].toLowerCase();
      // Resolve via the item registry so modded foods are accepted.
      // The AI passes either "bread" (minecraft namespace implied) or "namespace:path" for modded items.
      String resourceString = itemNameAsString.contains(":") ? itemNameAsString : "minecraft:" + itemNameAsString;
      ResourceLocation location = new ResourceLocation(resourceString);
      Item a = BuiltInRegistries.ITEM.get(location);
      if (!ItemVer.isFood(a)) {
        return;
      }
      controller.runUserTask(new EatFoodTask(a), () -> this.finish());
   }
}
