package com.player2.playerengine.util;

import java.util.Objects;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class RecipeTarget {
   private final CraftingRecipe recipe;
   private final Item item;
   private final int targetCount;

   /**
    * Optional MC-recipe carrier fields (WS5 — output fidelity + container-item remainder return).
    *
    * <p>{@code mcRecipe} is the selected MC {@code CraftingRecipe} (the real MC type, NOT the mod
    * wrapper) populated by {@code MaterialResolver.toRecipeTarget} and by the generic builder in
    * {@code CraftMacroPlanner}. {@code mcResultStack} is the precomputed result {@code ItemStack}
    * obtained from {@code mcRecipe.getResultItem(registries)}, which preserves NBT / DataComponents
    * for modded recipes. {@code registries} is the {@code RegistryAccess} used to compute it.
    *
    * <p>All three fields are {@code null} for the 5 legacy hand-built wrappers (chest, sign, planks,
    * stick, crafting_table) that do not go through {@code toRecipeTarget}. {@code
    * CraftingInventoryOps.performSingleCraft} falls back to the existing {@code new ItemStack(outputItem,
    * yield)} path when this carrier is absent (byte-behavior-identical for legacy targets).
    */
   private final net.minecraft.world.item.crafting.CraftingRecipe mcRecipe;
   private final ItemStack mcResultStack;
   private final RegistryAccess registries;

   /**
    * Primary constructor used by the mod's existing code paths (legacy hand-built wrappers for the 5
    * supported targets). The MC-recipe carrier is absent ({@code null}); {@code
    * CraftingInventoryOps.performSingleCraft} will use the existing {@code new ItemStack} fallback.
    */
   public RecipeTarget(Item item, int targetCount, CraftingRecipe recipe) {
      this.item = item;
      this.targetCount = targetCount;
      this.recipe = recipe;
      this.mcRecipe = null;
      this.mcResultStack = null;
      this.registries = null;
   }

   /**
    * Full constructor used by {@code MaterialResolver.toRecipeTarget} and the generic
    * {@code CraftMacroPlanner} builder (WS2/WS5). Carries the selected MC recipe, its precomputed
    * result stack, and the registry access so {@code CraftingInventoryOps.performSingleCraft} can
    * preserve output data components and return container items.
    *
    * @param mcRecipe      the MC {@code CraftingRecipe} (never the mod wrapper); may be {@code null}
    *                      only when called from legacy paths — prefer the 3-arg constructor in that case.
    * @param mcResultStack precomputed {@code mcRecipe.getResultItem(registries)}; {@code null} iff
    *                      {@code mcRecipe} is {@code null}.
    * @param registries    the {@code RegistryAccess} used to compute {@code mcResultStack}; {@code null}
    *                      iff {@code mcRecipe} is {@code null}.
    */
   public RecipeTarget(
         Item item, int targetCount, CraftingRecipe recipe,
         net.minecraft.world.item.crafting.CraftingRecipe mcRecipe,
         ItemStack mcResultStack,
         RegistryAccess registries) {
      this.item = item;
      this.targetCount = targetCount;
      this.recipe = recipe;
      this.mcRecipe = mcRecipe;
      this.mcResultStack = mcResultStack;
      this.registries = registries;
   }

   public CraftingRecipe getRecipe() {
      return this.recipe;
   }

   public Item getOutputItem() {
      return this.item;
   }

   public int getTargetCount() {
      return this.targetCount;
   }

   /**
    * The selected MC {@code CraftingRecipe} carrier, or {@code null} for legacy hand-built wrappers.
    * When non-null, {@code CraftingInventoryOps.performSingleCraft} uses it to call
    * {@code getRemainingItems} (WS6 seam) so container items are returned to inventory.
    */
   public net.minecraft.world.item.crafting.CraftingRecipe getMcRecipe() {
      return this.mcRecipe;
   }

   /**
    * Precomputed MC result stack ({@code mcRecipe.getResultItem(registries)}), or {@code null} for
    * legacy wrappers. When non-null, {@code CraftingInventoryOps.performSingleCraft} builds the
    * output via {@code mcResultStack.copyWithCount(yield)} to preserve NBT / DataComponents.
    */
   public ItemStack getMcResultStack() {
      return this.mcResultStack;
   }

   /**
    * The {@link RegistryAccess} used to resolve {@link #getMcResultStack()}, or {@code null} for
    * legacy wrappers.
    */
   public RegistryAccess getRegistries() {
      return this.registries;
   }

   /** Returns {@code true} when the MC-recipe carrier is populated (non-legacy path). */
   public boolean hasMcRecipe() {
      return this.mcRecipe != null;
   }

   @Override
   public String toString() {
      return this.targetCount == 1 ? "Recipe{" + this.item + "}" : "Recipe{" + this.item + " x " + this.targetCount + "}";
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         RecipeTarget that = (RecipeTarget)o;
         return this.targetCount == that.targetCount && this.recipe.equals(that.recipe) && Objects.equals(this.item, that.item);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.recipe, this.item);
   }
}
