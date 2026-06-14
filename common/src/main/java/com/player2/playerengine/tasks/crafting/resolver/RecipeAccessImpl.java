package com.player2.playerengine.tasks.crafting.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * MC 1.20.1 body of {@link RecipeAccess}.
 *
 * <p>In 1.20.1 the crafting recipe set is reached without a {@code RecipeHolder} wrapper:
 * {@code RecipeManager.getAllRecipesFor(RecipeType.CRAFTING)} (typed
 * {@code RecipeType<CraftingRecipe>}) returns {@code List<CraftingRecipe>} directly (verified
 * {@code decompiled-sources/1.20.1/.../crafting/RecipeManager.java:116} and {@code RecipeType.java:8}),
 * and the MC {@code CraftingRecipe} interface here is {@code CraftingRecipe extends
 * Recipe<CraftingContainer>} ({@code decompiled-sources/1.20.1/.../crafting/CraftingRecipe.java:5}).
 *
 * <p>This is one of the two documented per-branch bodies (alongside {@code IngredientInspectorImpl});
 * the 1.21.1 sibling must instead unwrap {@code RecipeHolder<CraftingRecipe>} via {@code .value()} and
 * absorbs the {@code Recipe<CraftingInput>} generic bound. Exposing only the common MC
 * {@link CraftingRecipe} interface keeps {@code MaterialResolver} byte-identical across branches.
 *
 * <p>This type is part of the deterministic resolver package: no Player2/AiTask/Joules calls.
 */
public final class RecipeAccessImpl implements RecipeAccess {

   @Override
   public List<CraftingRecipe> getCraftingRecipes(RecipeManager mgr) {
      // 1.20.1: getAllRecipesFor returns the MC CraftingRecipe directly (no RecipeHolder to unwrap).
      return new ArrayList<>(mgr.getAllRecipesFor(RecipeType.CRAFTING));
   }

   @Override
   public Optional<CraftingRecipe> selectRecipeForResult(RecipeManager mgr, Item target, RegistryAccess registries) {
      // Load-bearing variant-lock step: keep only recipes whose result item == the concrete target,
      // then prefer one that fits a 2x2 grid (no crafting table needed). getResultItem(RegistryAccess)
      // is byte-identical across versions (RegistryAccess extends HolderLookup.Provider).
      CraftingRecipe firstMatch = null;
      for (CraftingRecipe recipe : getCraftingRecipes(mgr)) {
         if (recipe.getResultItem(registries).getItem() != target) {
            continue;
         }
         if (recipe.canCraftInDimensions(2, 2)) {
            return Optional.of(recipe);
         }
         if (firstMatch == null) {
            firstMatch = recipe;
         }
      }
      return Optional.ofNullable(firstMatch);
   }

   @Override
   public List<CraftingRecipe> recipesForResult(RecipeManager mgr, Item target, RegistryAccess registries) {
      // Registry-order list of EVERY recipe producing `target` (e.g. stick has a planks recipe AND a
      // bamboo recipe); the resolver's funding-aware selection picks among them.
      List<CraftingRecipe> out = new ArrayList<>();
      for (CraftingRecipe recipe : getCraftingRecipes(mgr)) {
         if (recipe.getResultItem(registries).getItem() == target) {
            out.add(recipe);
         }
      }
      return out;
   }

   @Override
   public int outputCountOf(CraftingRecipe recipe, RegistryAccess registries) {
      // MC's Recipe interface has NO outputCount(); the per-craft yield is the result stack's count.
      return recipe.getResultItem(registries).getCount();
   }
}
