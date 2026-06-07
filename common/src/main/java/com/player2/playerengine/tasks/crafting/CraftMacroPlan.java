package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.List;
import net.minecraft.world.item.Item;

public record CraftMacroPlan(
      ItemTarget requestedOutput,
      Item outputItem,
      int targetCount,
      List<CraftMacroStep> steps,
      List<ItemTarget> externalMaterials,
      boolean requiresCraftingTable,
      ItemHelper.WoodItems woodFamily,
      RecipeTarget finalTableRecipe
) {
   public boolean isEmpty() {
      return this.steps.isEmpty() && this.externalMaterials.isEmpty() && this.finalTableRecipe == null;
   }
}
