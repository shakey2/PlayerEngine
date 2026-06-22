package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.crafting.resolver.IngredientInspector;
import com.player2.playerengine.tasks.crafting.resolver.IngredientInspectorImpl;
import com.player2.playerengine.tasks.crafting.resolver.MaterialResolver;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccess;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;

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
    * Stateless singletons used by the generic builder. Both are stateless (no mutable fields) so a
    * single instance is safe across any number of concurrent or sequential {@link #plan} calls. We
    * avoid constructing new instances every tick in {@code replan} — the per-tick {@code
    * CraftMacroResourceTask.replan} calls {@code plan} on every decision point, so allocation here
    * would be constant GC pressure.
    *
    * <p><b>Determinism invariant:</b> {@code selectRecipeForResult} is a pure function of (output,
    * RecipeManager, RegistryAccess); given the same server tick's RecipeManager it always returns
    * the same recipe, so re-running {@code plan} on consecutive ticks yields the identical
    * {@code finalTableRecipe}. Mid-run recipe flips cannot occur.
    */
   private static final RecipeAccess RECIPE_ACCESS = new RecipeAccessImpl();
   private static final IngredientInspector INGREDIENT_INSPECTOR = new IngredientInspectorImpl();

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

      // Generic builder: handle ANY output that has a live crafting recipe but is not one of the
      // 5 hardcoded targets above. This is the load-bearing WS2 change that opens the 4th gate —
      // without it, even after the existence + craft gates widen (WS1), every generic item hits
      // Optional.empty() here and produces no plan.
      //
      // Determinism invariant: selectRecipeForResult is a pure function of (output, RecipeManager,
      // RegistryAccess). Given the same server tick's RecipeManager it always returns the same
      // recipe, so re-running plan() on consecutive ticks (via CraftMacroResourceTask.replan) yields
      // the identical finalTableRecipe. Mid-run recipe flips cannot occur.
      MinecraftServer server = mod.getPlayer() != null ? mod.getPlayer().getServer() : null;
      if (server == null) {
         // No server context (pre-join edge case): cannot consult the RecipeManager; return empty
         // so the caller falls back to the TaskCatalogue path rather than crashing.
         return Optional.empty();
      }
      RecipeManager mgr = server.getRecipeManager();
      RegistryAccess registries = server.registryAccess();

      Optional<net.minecraft.world.item.crafting.CraftingRecipe> recipeOpt =
            RECIPE_ACCESS.selectRecipeForResult(mgr, outputItem, registries);
      if (recipeOpt.isEmpty()) {
         // Genuine no-recipe: return empty so the TaskCatalogue fallback fires (smelt/smith/gather).
         return Optional.empty();
      }

      net.minecraft.world.item.crafting.CraftingRecipe mcRecipe = recipeOpt.get();
      int yield = Math.max(1, RECIPE_ACCESS.outputCountOf(mcRecipe, registries));
      boolean requiresCraftingTable = !mcRecipe.canCraftInDimensions(2, 2);

      // Build slot ItemTargets by walking the MC recipe's ingredient list, using the same
      // inspector.acceptedItems(ing) pattern that MaterialResolver uses at lines 533-544. Empty or
      // null ingredients (the air slots of a shaped recipe) become ItemTarget.EMPTY. Slots whose
      // tag is unbound (acceptedItems returns empty) also become ItemTarget.EMPTY; the resolver's
      // per-tick UNOBTAINABLE guard handles those at execution time.
      NonNullList<Ingredient> ingredients = mcRecipe.getIngredients();
      List<ItemTarget> slotTargets = new ArrayList<>(ingredients.size());
      for (Ingredient ing : ingredients) {
         if (ing == null || ing.isEmpty()) {
            slotTargets.add(ItemTarget.EMPTY);
            continue;
         }
         Set<Item> accepted = INGREDIENT_INSPECTOR.acceptedItems(ing);
         if (accepted.isEmpty()) {
            slotTargets.add(ItemTarget.EMPTY);
            continue;
         }
         Item[] matchSet = accepted.toArray(new Item[0]);
         slotTargets.add(new ItemTarget(matchSet, 1));
      }

      // toRecipeTarget sizes the grid (2x2 if canCraftInDimensions(2,2) and <=4 slots, else 3x3),
      // populates the MC-recipe carrier (mcRecipe + mcResultStack + registries) for output-fidelity
      // and container-item return (WS5/WS6), and returns the mod-wrapper RecipeTarget.
      RecipeTarget finalTableRecipe = MaterialResolver.toRecipeTarget(
            outputItem, targetCount, yield, slotTargets, mcRecipe, registries);

      return Optional.of(seed(requested, outputItem, targetCount, requiresCraftingTable, finalTableRecipe));
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
