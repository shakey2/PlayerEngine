package com.player2.playerengine.commands.random;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.BlockScanner;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.util.helpers.FuzzySearchHelper;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

public class ScanCommand extends Command {
   public ScanCommand() throws CommandException {
      super("scan", "Locates nearest block", new Arg<>(String.class, "block", "DIRT", 0));
   }

   @Override
   protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
      String blockStr = parser.get(String.class);
      // Resolve via the game's own block registry rather than reflecting on Blocks.class. Under
      // Architectury/Loom the Fabric jar is remapped to the intermediary namespace at build time,
      // so at runtime Blocks.class field names are the obfuscated intermediary ids (e.g. field_9975),
      // never the Mojang names — reflection both failed to match "diamond_ore" AND leaked
      // "field_9975" into the suggestion. BuiltInRegistries.BLOCK goes through the live registry, so
      // it works identically on Fabric, Forge, and NeoForge with human-readable ids.
      String normalised = blockStr.contains(":") ? blockStr : "minecraft:" + blockStr;
      ResourceLocation parsed = ResourceLocation.tryParse(normalised);
      Block block = parsed != null
            ? BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null)
            : null;

      // Dual-audience rule (DESIGN.md §3): the result text goes to the log AND to the model
      // via finishWithNote, so "Returns the position of the nearest matching block" is true
      // for the model too — a bare finish() left it with only a generic "finished running".
      if (block == null) {
         List<String> allBlockNames = BuiltInRegistries.BLOCK.keySet().stream()
               .map(ResourceLocation::toString)
               .collect(Collectors.toList());
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
