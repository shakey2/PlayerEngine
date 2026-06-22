package com.player2.playerengine.tasks.crafting.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * MC 1.21.1 body of {@link RecipeAccess}.
 *
 * <p>In 1.21.1 the crafting recipe set is wrapped in {@code RecipeHolder}: {@code
 * RecipeManager.getAllRecipesFor(RecipeType.CRAFTING)} (typed {@code RecipeType<CraftingRecipe>})
 * returns {@code List<RecipeHolder<CraftingRecipe>>} (verified
 * {@code decompiled-sources/1.21.1/.../crafting/RecipeManager.java:107}). Each holder must be
 * unwrapped via {@link RecipeHolder#value()} to obtain the MC {@link CraftingRecipe} interface.
 *
 * <p>The MC {@code CraftingRecipe} interface in 1.21.1 is {@code CraftingRecipe extends
 * Recipe<CraftingInput>} ({@code decompiled-sources/1.21.1/.../crafting/CraftingRecipe.java:3}).
 * This differs from the 1.20.1 {@code Recipe<CraftingContainer>} bound; both divergences are
 * absorbed here so that {@code MaterialResolver} — which references only the common
 * {@link CraftingRecipe} interface — stays byte-identical across branches.
 *
 * <p>This is one of the two documented per-branch bodies (alongside {@code IngredientInspectorImpl});
 * the 1.20.1 sibling receives {@code List<CraftingRecipe>} directly (no {@code RecipeHolder}) and
 * absorbs the {@code Recipe<CraftingContainer>} generic bound.
 *
 * <p>This type is part of the deterministic resolver package: no Player2/AiTask/Joules calls.
 */
public final class RecipeAccessImpl implements RecipeAccess {

   @Override
   public List<CraftingRecipe> getCraftingRecipes(RecipeManager mgr) {
      // 1.21.1: getAllRecipesFor returns List<RecipeHolder<CraftingRecipe>>; unwrap each via .value().
      // Pre-filter special recipes (CustomRecipe subclasses: firework, shulker dye, banner, map clone,
      // repair, tipped arrow, etc.) and incomplete recipes (empty/unbound ingredient lists). Both
      // isSpecial() and isIncomplete() are stable across branches; calling them here keeps the resolver
      // from mis-classifying BARRIER-returning or unresolvable ingredient slots downstream.
      List<RecipeHolder<CraftingRecipe>> holders = mgr.getAllRecipesFor(RecipeType.CRAFTING);
      List<CraftingRecipe> result = new ArrayList<>(holders.size());
      for (RecipeHolder<CraftingRecipe> holder : holders) {
         CraftingRecipe recipe = holder.value();
         if (recipe.isSpecial() || recipe.isIncomplete()) {
            continue;
         }
         result.add(recipe);
      }
      return result;
   }

   @Override
   public Optional<CraftingRecipe> selectRecipeForResult(RecipeManager mgr, Item target, RegistryAccess registries) {
      // Load-bearing variant-lock step: keep only recipes whose result item == the concrete target,
      // then prefer one that fits a 2x2 grid (no crafting table needed). getResultItem(RegistryAccess)
      // is byte-identical across versions (RegistryAccess extends HolderLookup.Provider in 1.21.1,
      // and server.registryAccess() returns RegistryAccess.Frozen which satisfies HolderLookup.Provider).
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

   /**
    * WS6 implementation — 1.21.1 branch.
    *
    * <p>Constructs a {@link CraftingInput} from the given slot contents via
    * {@link CraftingInput#of(int, int, List)}, which internally crops the grid to the minimal
    * bounding box of non-empty items. The returned {@link NonNullList} size therefore equals the
    * cropped item count, NOT {@code width * height}. Callers MUST iterate the list and reinsert
    * each non-{@link ItemStack#EMPTY} stack without assuming slot-position correspondence to the
    * original {@code slotContents} layout (see WS5 note in {@link RecipeAccess#getRemainingItems}).
    */
   @Override
   public NonNullList<ItemStack> getRemainingItems(
         CraftingRecipe recipe, List<ItemStack> slotContents,
         int width, int height, RegistryAccess registries) {
      CraftingInput input = CraftingInput.of(width, height, slotContents);
      return recipe.getRemainingItems(input);
   }
}
