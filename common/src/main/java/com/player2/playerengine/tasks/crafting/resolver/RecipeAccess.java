package com.player2.playerengine.tasks.crafting.resolver;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Cross-version access to the Minecraft crafting recipe set, narrowed to the common
 * {@link CraftingRecipe} interface so the deterministic resolver code stays byte-identical across
 * the 1.20.1 and 1.21.1 PlayerEngine branches.
 *
 * <p>The concrete implementation absorbs BOTH version divergences so they never reach
 * {@code MaterialResolver}:
 * <ol>
 *   <li>the {@code RecipeHolder<CraftingRecipe>} unwrap (1.21.1 {@code .value()}; 1.20.1 has no
 *       {@code RecipeHolder}), and</li>
 *   <li>the generic-bound difference {@code CraftingRecipe extends Recipe<CraftingContainer>}
 *       (1.20.1) vs {@code CraftingRecipe extends Recipe<CraftingInput>} (1.21.1).</li>
 * </ol>
 * Only the common MC {@link CraftingRecipe} interface (which exists with the same FQN in both
 * versions) is exposed; {@code Recipe<CraftingContainer>}/{@code Recipe<CraftingInput>} and
 * {@code RecipeHolder} are never surfaced.
 *
 * <p><b>Note:</b> {@code CraftingRecipe} here is the Minecraft interface
 * {@code net.minecraft.world.item.crafting.CraftingRecipe}, NOT the mod's
 * {@code com.player2.playerengine.util.CraftingRecipe} wrapper. The MC {@code Recipe} interface has
 * no {@code outputCount()} method in either version, which is why {@link #outputCountOf} exists here.
 *
 * <p>This type is part of the deterministic resolver package: it performs no Player2/AiTask/Joules
 * calls and only reads the Minecraft recipe system.
 */
public interface RecipeAccess {

   /**
    * All crafting recipes known to the given {@link RecipeManager}, with any version-specific
    * {@code RecipeHolder} wrapper already unwrapped to the MC {@link CraftingRecipe} interface.
    */
   List<CraftingRecipe> getCraftingRecipes(RecipeManager mgr);

   /**
    * Select the result-specific crafting recipe whose result item equals {@code target}, preferring
    * a 2x2-craftable recipe when available; {@link Optional#empty()} if no recipe produces
    * {@code target}.
    *
    * <p>This is the load-bearing variant-lock step: choosing the recipe whose result equals the
    * concrete target restricts the legal inputs to exactly that recipe's ingredients.
    */
   Optional<CraftingRecipe> selectRecipeForResult(RecipeManager mgr, Item target, RegistryAccess registries);

   /**
    * ALL crafting recipes whose result item equals {@code target}, in registry order; empty if no
    * recipe produces {@code target} (so emptiness is interchangeable with
    * {@link #selectRecipeForResult} emptiness for raw-material detection).
    *
    * <p>Exists for FUNDING-AWARE recipe selection in {@code MaterialResolver.selectFundedRecipe}: an
    * item with several recipes (vanilla {@code stick} has a planks recipe and a bamboo recipe) must be
    * chosen by what held inputs can actually fund, not by whichever recipe happens to fit a 2x2 grid
    * first — {@link #selectRecipeForResult} alone cannot express that.
    */
   List<CraftingRecipe> recipesForResult(RecipeManager mgr, Item target, RegistryAccess registries);

   /**
    * Per-craft output yield of {@code recipe}, i.e.
    * {@code recipe.getResultItem(registries).getCount()}. {@code getResultItem(RegistryAccess)} is
    * byte-identical across both versions ({@code RegistryAccess extends HolderLookup.Provider}), so
    * this is a convenience wrapper rather than a true version divergence.
    */
   int outputCountOf(CraftingRecipe recipe, RegistryAccess registries);

   /**
    * Returns {@code true} iff at least one {@link net.minecraft.world.item.crafting.RecipeType#CRAFTING}
    * recipe in {@code mgr} produces {@code target} as its output. This is the craft-gate predicate for
    * {@code CraftMacroSupport.isSupportedTarget}: items whose only path is smelt/smith/stonecut return
    * {@code false} here and fall back to the {@code TaskCatalogue} path.
    *
    * <p>Default implementation: delegates to {@link #recipesForResult}; no {@code RecipeAccessImpl}
    * change required.
    */
   default boolean hasRecipe(RecipeManager mgr, Item target, RegistryAccess registries) {
      return !recipesForResult(mgr, target, registries).isEmpty();
   }

   /**
    * Container-item remainder return after a simulated single craft of {@code recipe} with the given
    * slot contents. Called from {@code CraftingInventoryOps.performSingleCraft} to reinsert container
    * items (e.g. empty buckets from milk_bucket/water_bucket ingredients) into inventory after each
    * craft, so they are never silently destroyed.
    *
    * <p>The returned list may be SHORTER than {@code width * height} (the 1.21.1 implementation crops
    * the grid to the minimal bounding box via {@code CraftingInput.of}); callers must iterate and
    * reinsert each non-{@link ItemStack#EMPTY} stack without assuming slot-position correspondence.
    *
    * <p>Implementation is per-branch: 1.21.1 uses {@code CraftingInput.of(width, height, slotContents)}
    * then {@code recipe.getRemainingItems(input)}; 1.20.1 uses {@code TransientCraftingContainer} with a
    * minimal {@code AbstractContainerMenu} stub. Common callers never see either version-specific type.
    *
    * <p>Both per-branch implementations are complete and always return a list — callers do NOT need to
    * guard against {@code UnsupportedOperationException}.
    */
   NonNullList<ItemStack> getRemainingItems(
         CraftingRecipe recipe, List<ItemStack> slotContents,
         int width, int height, RegistryAccess registries);

   /**
    * SHAPE-ONLY classification of a "storage pack/unpack" recipe (e.g. iron_block -> 9 iron_ingot,
    * 1 iron_ingot -> 9 iron_nugget). If {@code candidate} is a reversible, single-distinct-ingredient
    * decompression recipe producing {@code want}, returns the single consumed ingredient {@link Item}
    * {@code I}; otherwise {@link Optional#empty()}.
    *
    * <p><b>This method is availability-FREE: it carries NO inventory/reservation term.</b> It only
    * inspects recipe geometry. Callers MUST apply their own availability check on the returned
    * ingredient {@code I} (e.g. {@code MaterialResolver} uses {@code freeCount(...,I)==0}, while
    * {@code CraftMacroTasks} uses {@code getItemStorage().getItemCount(I)==0}). Centralising the shape
    * test here guarantees the two call sites can never silently diverge on what an "unpack" is.
    *
    * <p>Classification rule (all must hold):
    * <ol>
    *   <li><b>(a) Single distinct concrete ingredient</b> — across every non-empty slot of
    *       {@code candidate}, each slot's accepted-item set has size {@code 1} and ALL non-empty slots
    *       resolve to the SAME single item {@code I}. A tag / multi-variant slot (accepted set
    *       size &gt; 1) or a multi-type recipe (union size &gt; 1) disqualifies.</li>
    *   <li><b>(b) Yield pre-filter</b> — {@code outputCountOf(candidate) >= 2}. This is purely a
    *       cheap gate to skip the {@link #recipesForResult} scan on obvious 1:1 recipes; it is NOT the
    *       discriminator (there is intentionally no {@code yield>=4} / 4:1 threshold).</li>
    *   <li><b>(c) Bidirectional reversibility</b> — at least one recipe producing {@code I} has its own
    *       distinct non-empty ingredient set exactly equal to {@code { want }} (so {@code want} and
    *       {@code I} pack/unpack into each other).</li>
    * </ol>
    *
    * <p>Uses only the already-declared interface methods ({@link #recipesForResult},
    * {@link #outputCountOf}) plus the common MC {@link Ingredient#getItems()} ({@code ItemStack[]} in
    * both 1.20.1 and 1.21.1), so no {@code RecipeAccessImpl} / {@code IngredientInspector} change is
    * needed and this default is byte-identical across branches.
    */
   default Optional<Item> storageUnpackIngredient(
         RecipeManager mgr, Item want, RegistryAccess registries, CraftingRecipe candidate) {
      // (a) Single distinct concrete ingredient across all non-empty slots.
      Optional<Item> ingredientOpt = singleDistinctIngredient(candidate);
      if (ingredientOpt.isEmpty()) {
         return Optional.empty();
      }
      Item ingredient = ingredientOpt.get();

      // (b) Cheap yield pre-filter ONLY (skip the recipesForResult scan on obvious 1:1 recipes).
      int yield = Math.max(1, outputCountOf(candidate, registries));
      if (yield < 2) {
         return Optional.empty();
      }

      // (c) Bidirectional reversibility: some recipe for the ingredient consumes exactly { want }.
      for (CraftingRecipe reverse : recipesForResult(mgr, ingredient, registries)) {
         Optional<Item> reverseIngredient = singleDistinctIngredient(reverse);
         if (reverseIngredient.isPresent() && reverseIngredient.get() == want) {
            return Optional.of(ingredient);
         }
      }
      return Optional.empty();
   }

   /**
    * SHAPE-ONLY: the single distinct concrete ingredient consumed by {@code recipe}, or
    * {@link Optional#empty()} if {@code recipe} is multi-ingredient, multi-variant (tag slot), or has no
    * non-empty slot. This exposes the same per-slot accepted-item geometry used by
    * {@link #storageUnpackIngredient}, WITHOUT the yield pre-filter or the reversibility test, so callers
    * that need to reason about a single-ingredient recipe whose yield is below the unpack pre-filter
    * (e.g. {@code 9 iron_nugget -> 1 iron_ingot}) share the exact same shape source and cannot drift.
    *
    * <p>Availability-FREE, like {@link #storageUnpackIngredient}; callers apply their own inventory term.
    */
   default Optional<Item> singleConsumedIngredient(CraftingRecipe recipe) {
      return singleDistinctIngredient(recipe);
   }

   /**
    * Helper for {@link #storageUnpackIngredient}: if EVERY non-empty ingredient slot of {@code recipe}
    * accepts exactly one item AND all non-empty slots accept the SAME item, returns that item; else
    * {@link Optional#empty()} (no non-empty slot, a slot accepting more than one item, or two slots
    * accepting different items). Accepted items per slot are read via the common MC
    * {@link Ingredient#getItems()} so no version-specific inspector is required.
    */
   private static Optional<Item> singleDistinctIngredient(CraftingRecipe recipe) {
      Item single = null;
      boolean sawNonEmpty = false;
      for (Ingredient ing : recipe.getIngredients()) {
         if (ing == null || ing.isEmpty()) {
            continue;
         }
         Set<Item> accepted = new HashSet<>();
         for (ItemStack stack : ing.getItems()) {
            accepted.add(stack.getItem());
         }
         if (accepted.size() != 1) {
            return Optional.empty();
         }
         Item slotItem = accepted.iterator().next();
         if (!sawNonEmpty) {
            single = slotItem;
            sawNonEmpty = true;
         } else if (single != slotItem) {
            return Optional.empty();
         }
      }
      return sawNonEmpty ? Optional.of(single) : Optional.empty();
   }
}
