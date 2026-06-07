package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.util.RecipeTarget;
import net.minecraft.world.item.Item;

/**
 * One step of a craft macro plan.
 *
 * <p>{@code outputMatches} is an OPTIONAL pooled-satisfaction override: when non-null it lists the
 * full set of items that satisfy this step (e.g. every {@code #minecraft:planks} variant for the
 * chest plan), so the macro counts progress across all of them instead of the single concrete
 * {@code recipeTarget().getOutputItem()}. It is null for every normal {@code get}/craft step, which
 * preserves the strict single-item satisfaction those plans rely on.
 */
public record CraftMacroStep(
      CraftMacroStepKind kind, RecipeTarget recipeTarget, int craftsNeeded, String debugLabel, Item[] outputMatches) {
   public CraftMacroStep(CraftMacroStepKind kind, RecipeTarget recipeTarget, int craftsNeeded, String debugLabel) {
      this(kind, recipeTarget, craftsNeeded, debugLabel, null);
   }
}
