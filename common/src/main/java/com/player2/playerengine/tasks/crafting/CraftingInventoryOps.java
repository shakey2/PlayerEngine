package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Shared survival-honest consume/insert crafting used by Part C0 craft macros.
 */
public final class CraftingInventoryOps {
   private CraftingInventoryOps() {
   }

   public static boolean hasMaterials(PlayerEngineController mod, RecipeTarget target) {
      return StorageHelper.hasRecipeMaterialsOrTarget(
            mod, new RecipeTarget(target.getOutputItem(), target.getRecipe().outputCount(), target.getRecipe()));
   }

   public static boolean performSingleCraft(PlayerEngineController mod, RecipeTarget oneCraft) {
      if (!hasMaterials(mod, oneCraft)) {
         return false;
      }

      LivingEntityInventory inventory = ((IInventoryProvider)mod.getEntity()).getLivingInventory();
      for (ItemTarget ingredient : oneCraft.getRecipe().getSlots()) {
         if (ingredient != null && !ingredient.isEmpty()) {
            inventory.remove(stack -> ingredient.matches(stack.getItem()), ingredient.getTargetCount(), inventory);
         }
      }

      ItemStack result = new ItemStack(oneCraft.getOutputItem(), oneCraft.getRecipe().outputCount());
      inventory.insertStack(result);
      mod.getItemStorage().registerSlotAction();
      mod.getEntity().swing(InteractionHand.MAIN_HAND);
      return true;
   }

   public static int performCrafts(PlayerEngineController mod, RecipeTarget target, int craftsNeeded) {
      int completed = 0;
      RecipeTarget single = new RecipeTarget(target.getOutputItem(), target.getRecipe().outputCount(), target.getRecipe());
      for (int i = 0; i < craftsNeeded; i++) {
         if (!performSingleCraft(mod, single)) {
            break;
         }
         completed++;
      }
      return completed;
   }

   public static int countItem(PlayerEngineController mod, Item item) {
      return mod.getItemStorage().getItemCount(item);
   }
}
