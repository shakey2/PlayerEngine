package com.player2.playerengine.commands.random;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.BlockScanner;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.util.helpers.FuzzySearchHelper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class ScanCommand extends Command {
   public ScanCommand() throws CommandException {
      super("scan", "Locates nearest block", new Arg<>(String.class, "block", "DIRT", 0));
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      String blockStr = parser.get(String.class);
      Field[] declaredFields = Blocks.class.getDeclaredFields();
      Block block = null;
      List<String> allBlockNames = new ArrayList<>();

      for (Field field : declaredFields) {
         field.setAccessible(true);

         try {
            String fieldName = field.getName();
            allBlockNames.add(fieldName.toLowerCase());
            if (fieldName.equalsIgnoreCase(blockStr)) {
               block = (Block)field.get(Blocks.class);
            }
         } catch (IllegalAccessException var12) {
            throw new RuntimeException(var12);
         }

         field.setAccessible(false);
      }

      // Dual-audience rule (DESIGN.md §3): the result text goes to the log AND to the model
      // via finishWithNote, so "Returns the position of the nearest matching block" is true
      // for the model too — a bare finish() left it with only a generic "finished running".
      if (block == null) {
         String closest = FuzzySearchHelper.getClosestMatchMinecraftItems(blockStr, allBlockNames);
         String msg = "Block named: \"" + blockStr + "\" not a valid block. Perhaps the user meant \"" + closest + "\"?" + (blockStr.contains("log") ? " Can try 'log' as well": "");
         mod.log(msg);

         this.finishWithNote(msg);
      } else {
         BlockScanner blockScanner = mod.getBlockScanner();
         Optional<BlockPos> p = blockScanner.getNearestBlock(block, mod.getPlayer().position());
         String msg;
         if (p.isPresent()) {
            BlockPos pos = p.get();
            msg = "Closest " + blockStr + ": (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
         } else {
            msg = "No blocks of type " + blockStr + " found nearby.";
         }
         mod.log(msg);

         this.finishWithNote(msg);
      }
   }
}
