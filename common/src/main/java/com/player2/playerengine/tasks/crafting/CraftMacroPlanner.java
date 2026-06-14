package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Seeds the INITIAL {@link CraftMacroPlan} for a craft-macro request: the structural facts the
 * {@link CraftMacroResourceTask} FSM needs up front — the concrete output item, the target count,
 * whether a crafting table is required, and the final table-bound recipe (chest/sign).
 *
 * <p><b>The scattered per-family material accounting has moved out of the planner.</b> The old
 * chest-only cumulative-target arithmetic and the single-wood {@code pickBestWoodFamily} fixation are
 * gone; the executor calls {@code MaterialResolver.resolve(...)} from LIVE inventory at every decision
 * point, so the per-tick remaining work (sub-craft steps + external acquisitions) is computed there,
 * never trusted from a stale up-front snapshot. The seed plan therefore carries EMPTY step/external
 * lists — the resolver fills them in each tick. Both shared callers ({@code GetCommand},
 * {@code ResolveStorageChestTask} via {@code CraftMacroTasks.tryCreateMacroTask}) benefit from the one
 * accounting code path without any signature change.
 *
 * <p><b>Do NOT re-add per-family arithmetic here.</b> The generic-catalogue pre-resolution stays
 * upstream in {@code CraftMacroSupport.resolveSingleOutputItem} (bare {@code planks} -> OAK_PLANKS,
 * bare {@code sign} -> the species {@code CraftMacroSupport.pickSignSpecies} picks deterministically
 * from FUNDABLE held/nearby wood, OAK on ties); the resolver receives a concrete,
 * variant-locked item in those cases and must not re-pool it. An explicit {@code <wood>_sign}
 * request is honored as-is when fundable; the not-fundable substitution happens UPSTREAM, once, at task creation
 * ({@code CraftMacroSupport.adjustSignRequestForFunding} via {@code CraftMacroTasks}) — never here,
 * so replan can never re-roll the species.
 */
public final class CraftMacroPlanner {
   private CraftMacroPlanner() {
   }

   /**
    * Seed the initial plan for {@code requested}. Keeps the dispatch + the early "already have enough
    * -> empty plan" short-circuit, and computes only the STRUCTURAL facts the FSM needs (output item,
    * target count, {@code requiresCraftingTable}, final table recipe). Returns {@link Optional#empty()}
    * for an unsupported / unresolvable output.
    */
   public static Optional<CraftMacroPlan> plan(PlayerEngineController mod, ItemTarget requested) {
      Optional<Item> outputOpt = CraftMacroSupport.resolveSingleOutputItem(mod, requested);
      if (outputOpt.isEmpty()) {
         return Optional.empty();
      }
      Item outputItem = outputOpt.get();
      int targetCount = requested.getTargetCount();
      int have = mod.getItemStorage().getItemCount(outputItem);
      if (have >= targetCount) {
         return Optional.of(
               new CraftMacroPlan(requested, outputItem, targetCount, List.of(), List.of(), false, null, null));
      }

      if (outputItem == Items.CHEST) {
         return Optional.of(seed(requested, outputItem, targetCount, true, anyPlankChestRecipe()));
      }
      if (Arrays.asList(ItemHelper.WOOD_SIGN).contains(outputItem)) {
         ItemHelper.WoodItems wood = findWoodForItem(outputItem);
         if (wood == null) {
            wood = ItemHelper.getWoodItems(com.player2.playerengine.util.WoodType.OAK);
         }
         return Optional.of(seed(requested, outputItem, targetCount, true, signRecipe(wood)));
      }
      if (outputItem == Items.CRAFTING_TABLE) {
         return Optional.of(seed(requested, outputItem, targetCount, false, null));
      }
      if (outputItem == Items.STICK) {
         return Optional.of(seed(requested, outputItem, targetCount, false, null));
      }
      if (Arrays.asList(ItemHelper.PLANKS).contains(outputItem)) {
         if (findWoodForItem(outputItem) == null) {
            return Optional.empty();
         }
         return Optional.of(seed(requested, outputItem, targetCount, false, null));
      }
      return Optional.empty();
   }

   /**
    * Build a seed plan with empty step/external lists. The resolver supplies the remaining work each
    * tick; only the structural flags ({@code requiresCraftingTable}, {@code finalTableRecipe}) and the
    * output identity are fixed here so the FSM can sequence place-table / move / look / craft-in-table.
    */
   private static CraftMacroPlan seed(
         ItemTarget requested, Item outputItem, int targetCount, boolean requiresTable, RecipeTarget finalRecipe) {
      return new CraftMacroPlan(
            requested, outputItem, targetCount, List.of(), List.of(), requiresTable, null, finalRecipe);
   }

   private static ItemHelper.WoodItems findWoodForItem(Item item) {
      for (ItemHelper.WoodItems wood : ItemHelper.getWoodItems()) {
         if (wood.planks == item || wood.sign == item || wood.log == item) {
            return wood;
         }
      }
      return null;
   }

   /** Chest recipe whose 8 plank slots accept ANY plank ({@code #minecraft:planks}), pooling mixed planks. */
   private static RecipeTarget anyPlankChestRecipe() {
      ItemTarget p = new ItemTarget(ItemHelper.PLANKS, 1);
      return new RecipeTarget(
            Items.CHEST,
            1,
            CraftingRecipe.newShapedRecipe(
                  new ItemTarget[]{p, p, p, p, ItemTarget.EMPTY, p, p, p, p}, 1));
   }

   private static RecipeTarget signRecipe(ItemHelper.WoodItems wood) {
      ItemTarget planks = new ItemTarget(wood.planks, 1);
      ItemTarget stick = TaskCatalogue.getItemTarget("stick", 1);
      return new RecipeTarget(
            wood.sign,
            3,
            CraftingRecipe.newShapedRecipe(
                  new ItemTarget[]{planks, planks, planks, planks, planks, planks, null, stick, null}, 3));
   }
}
