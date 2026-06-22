package com.player2.playerengine.trackers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ItemList;
import com.player2.playerengine.multiversion.recipemanager.RecipeManagerWrapper;
import com.player2.playerengine.multiversion.recipemanager.WrappedRecipeEntry;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.RecipeTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

public class CraftingRecipeTracker extends Tracker {
   private final HashMap<Item, List<CraftingRecipe>> itemRecipeMap = new HashMap<>();
   private final HashMap<CraftingRecipe, ItemStack> recipeResultMap = new HashMap<>();
   private boolean shouldRebuild = true;

   public CraftingRecipeTracker(TrackerManager manager) {
      super(manager);
   }

   public List<CraftingRecipe> getRecipeForItem(Item item) {
      this.ensureUpdated();
      if (!this.hasRecipeForItem(item)) {
         this.mod.logWarning("trying to access recipe for unknown item: " + item);
         return null;
      } else {
         return this.itemRecipeMap.get(item);
      }
   }

   public CraftingRecipe getFirstRecipeForItem(Item item) {
      this.ensureUpdated();
      if (!this.hasRecipeForItem(item)) {
         this.mod.logWarning("trying to access recipe for unknown item: " + item);
         return null;
      } else {
         return this.itemRecipeMap.get(item).get(0);
      }
   }

   public List<RecipeTarget> getRecipeTarget(Item item, int targetCount) {
      this.ensureUpdated();
      List<RecipeTarget> targets = new ArrayList<>();

      for (CraftingRecipe recipe : this.getRecipeForItem(item)) {
         targets.add(new RecipeTarget(item, targetCount, recipe));
      }

      return targets;
   }

   public RecipeTarget getFirstRecipeTarget(Item item, int targetCount) {
      this.ensureUpdated();
      return new RecipeTarget(item, targetCount, this.getFirstRecipeForItem(item));
   }

   public boolean hasRecipeForItem(Item item) {
      this.ensureUpdated();
      return this.itemRecipeMap.containsKey(item);
   }

   public ItemStack getRecipeResult(CraftingRecipe recipe) {
      this.ensureUpdated();
      if (!this.hasRecipe(recipe)) {
         this.mod.logWarning("Trying to get result for unknown recipe: " + recipe);
         return null;
      } else {
         ItemStack result = this.recipeResultMap.get(recipe);
         return new ItemStack(result.getItem(), result.getCount());
      }
   }

   public boolean hasRecipe(CraftingRecipe recipe) {
      this.ensureUpdated();
      return this.recipeResultMap.containsKey(recipe);
   }

   @Override
   protected void updateState() {
      if (this.shouldRebuild) {
         if (PlayerEngineController.inGame()) {
            RecipeManagerWrapper recipeManager = RecipeManagerWrapper.of(this.mod.getWorld().getRecipeManager());

            // Pass a real RegistryAccess to getResultItem: vanilla ShapedRecipe ignores it, but a
            // modded recipe that dereferences the param would NPE on the old null-pass. The world's
            // registryAccess() is available here (ServerLevel in both branches).
            net.minecraft.core.RegistryAccess registries = this.mod.getWorld().registryAccess();
            for (WrappedRecipeEntry recipe : recipeManager.values()) {
               Recipe<?> recipe1 = recipe.value();
               // MUST test the Minecraft CraftingRecipe interface, not the project-internal POJO
               // (com.player2.playerengine.util.CraftingRecipe) imported above — that name collision
               // made this instanceof ALWAYS FALSE, so itemRecipeMap (and thus the "did you mean"
               // corpus via updateCraftingCorpus) was never populated. Fully qualify to disambiguate.
               if (recipe1 instanceof net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe) {
                  if (!(craftingRecipe instanceof CustomRecipe)) {
                     try {
                        ItemStack resultStack = craftingRecipe.getResultItem(registries);
                        ItemStack result = new ItemStack(resultStack.getItem(), resultStack.getCount());
                        Item[][] altoclefRecipeItems = getShapedCraftingRecipe(craftingRecipe.getIngredients());
                        CraftingRecipe altoclefRecipe = CraftingRecipe.newShapedRecipe(altoclefRecipeItems, result.getCount());
                        if (this.itemRecipeMap.containsKey(result.getItem())) {
                           this.itemRecipeMap.get(result.getItem()).add(altoclefRecipe);
                        } else {
                           List<CraftingRecipe> recipes = new ArrayList<>();
                           recipes.add(altoclefRecipe);
                           this.itemRecipeMap.put(result.getItem(), recipes);
                        }

                        this.recipeResultMap.put(altoclefRecipe, result);
                     } catch (Exception perRecipe) {
                        // A single malformed/modded recipe must not abort the whole corpus rebuild —
                        // skip it (the corpus stays best-effort) rather than leave the map empty.
                        this.mod.logWarning("Skipping crafting recipe during corpus rebuild: " + perRecipe);
                     }
                  }
               }
            }

            this.itemRecipeMap.replaceAll((k, v) -> Collections.unmodifiableList((List<? extends CraftingRecipe>)v));
            // Notify ItemList of the curated recipe-output corpus so "did you mean" suggestions
            // in parseRemainder are scoped to craftable items rather than all registry entries.
            // Reuses this world-join seam; no second RecipeManager scan needed (WS1).
            ItemList.updateCraftingCorpus(this.itemRecipeMap.keySet());
            this.shouldRebuild = false;
         }
      }
   }

   private static Item[][] getShapedCraftingRecipe(List<Ingredient> ingredients) {
      Item[][] result = new Item[9][];
      int x = 0;

      for (Ingredient ingredient : ingredients) {
         ItemStack[] stacks = ingredient.getItems();
         Item[] items = new Item[stacks.length];

         for (int i = 0; i < stacks.length; i++) {
            ItemStack stack = stacks[i];
            if (stack.getCount() > 1) {
               throw new IllegalStateException("recipe needs more then one item on a slot... well... shit (ingredients: " + ingredient + ")");
            }

            items[i] = stack.getItem();
         }

         if (stacks.length != 0) {
            Item[] var10000 = new Item[]{items[0]};
            result[x] = new Item[1];
         } else {
            result[x] = null;
         }

         x++;
      }

      return result;
   }

   @Override
   protected void reset() {
      this.shouldRebuild = true;
      this.itemRecipeMap.clear();
      this.recipeResultMap.clear();
   }

   @Override
   protected boolean isDirty() {
      return this.shouldRebuild;
   }
}
