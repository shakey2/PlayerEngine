package com.player2.playerengine.tasks.crafting.resolver;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
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
}
