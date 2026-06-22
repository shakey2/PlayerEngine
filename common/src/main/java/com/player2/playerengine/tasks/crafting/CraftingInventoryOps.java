package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccess;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Shared survival-honest consume/insert crafting used by Part C0 craft macros.
 *
 * <p>WS5: when the {@link RecipeTarget} carries an MC recipe (populated by
 * {@code MaterialResolver.toRecipeTarget} for the generic resolver path), {@link #performSingleCraft}
 * uses {@code mcResultStack.copyWithCount(yield)} to preserve output data components (NBT / DataComponents
 * for modded items), and calls {@link RecipeAccess#getRemainingItems} after ingredient removal to
 * reinsert container items (e.g. empty buckets from milk_bucket/water_bucket ingredients).
 *
 * <p>The 5 legacy hand-built wrappers (chest, sign, planks, stick, crafting_table) pass {@code null}
 * for the MC carrier; those paths fall through to the existing {@code new ItemStack(outputItem, yield)}
 * behavior, which is byte-behavior-identical to the pre-WS5 code.
 */
public final class CraftingInventoryOps {

   /**
    * Stateless singleton for the WS6 {@link RecipeAccess#getRemainingItems} seam. {@link RecipeAccessImpl}
    * is stateless; constructing a shared instance here avoids per-craft allocation.
    */
   private static final RecipeAccess RECIPE_ACCESS = new RecipeAccessImpl();

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

      // WS5: if the MC-recipe carrier is present, snapshot the ingredient stacks BEFORE removal so
      // getRemainingItems (WS6) can identify which container items to return (e.g. empty bucket for a
      // milk_bucket slot). We record one representative ItemStack per non-empty slot. The slot ordering
      // matches the mod-wrapper grid (width x height), which is what TransientCraftingContainer expects.
      List<ItemStack> slotContents = null;
      if (oneCraft.hasMcRecipe()) {
         int slotCount = oneCraft.getRecipe().getSlotCount();
         slotContents = new ArrayList<>(slotCount);
         for (ItemTarget ingredient : oneCraft.getRecipe().getSlots()) {
            if (ingredient != null && !ingredient.isEmpty() && ingredient.getMatches().length > 0) {
               // Use count=1 since getRemainingItems only inspects the item type (hasCraftingRemainingItem),
               // not the stack count.
               slotContents.add(new ItemStack(ingredient.getMatches()[0], 1));
            } else {
               slotContents.add(ItemStack.EMPTY);
            }
         }
      }

      for (ItemTarget ingredient : oneCraft.getRecipe().getSlots()) {
         if (ingredient != null && !ingredient.isEmpty()) {
            inventory.remove(stack -> ingredient.matches(stack.getItem()), ingredient.getTargetCount(), inventory);
         }
      }

      // WS5 output fidelity: prefer copyWithCount (preserves NBT / DataComponents) when the MC-result
      // carrier is present and non-empty; fall back to the legacy new ItemStack path for null-carrier
      // (the 5 legacy hand-built wrappers: chest, sign, planks, stick, crafting_table).
      int yield = oneCraft.getRecipe().outputCount();
      ItemStack result;
      if (oneCraft.hasMcRecipe() && oneCraft.getMcResultStack() != null && !oneCraft.getMcResultStack().isEmpty()) {
         result = oneCraft.getMcResultStack().copyWithCount(yield);
      } else {
         result = new ItemStack(oneCraft.getOutputItem(), yield);
      }
      inventory.insertStack(result);

      // WS5/WS6: reinsert container-item remainders (e.g. empty bucket from milk_bucket ingredient).
      // Only runs on the carrier path (generic resolver); legacy wrappers skip this to preserve
      // byte-behavior-identical results for the 5 supported targets.
      if (oneCraft.hasMcRecipe() && slotContents != null) {
         int width = oneCraft.getRecipe().getWidth();
         int height = oneCraft.getRecipe().getHeight();
         NonNullList<ItemStack> remainders = RECIPE_ACCESS.getRemainingItems(
               oneCraft.getMcRecipe(), slotContents, width, height, oneCraft.getRegistries());
         for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty()) {
               inventory.insertStack(remainder);
            }
         }
      }

      mod.getItemStorage().registerSlotAction();
      mod.getEntity().swing(InteractionHand.MAIN_HAND);
      return true;
   }

   public static int performCrafts(PlayerEngineController mod, RecipeTarget target, int craftsNeeded) {
      int completed = 0;
      // WS5: copy the MC-recipe carrier through so performSingleCraft has access to the MC recipe and
      // registries for output fidelity and remainder return. Legacy null-carrier wrappers pass through
      // null as well, which keeps the 3-arg fallback behavior in performSingleCraft.
      RecipeTarget single = new RecipeTarget(
            target.getOutputItem(), target.getRecipe().outputCount(), target.getRecipe(),
            target.getMcRecipe(), target.getMcResultStack(), target.getRegistries());
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
