package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccess;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;

public final class CraftMacroTasks {
   private CraftMacroTasks() {
   }

   /**
    * Stateless {@link RecipeAccess} singleton shared by this class. Instantiated once; safe because
    * {@link RecipeAccessImpl} holds no mutable state.
    */
   private static final RecipeAccess RECIPE_ACCESS = new RecipeAccessImpl();

   /**
    * Outcome classification for a macro-task creation attempt. Used by {@link #macroOutcome} so
    * callers can distinguish WHY a macro task could not be created and route accordingly, avoiding the
    * silent {@code null}-return ambiguity of the previous {@link #tryCreateMacroTask} single path.
    *
    * <ul>
    *   <li>{@link #MACRO_CREATED} — macro is enabled AND a crafting recipe exists; the task is
    *       available via {@link MacroProbe#task()}.</li>
    *   <li>{@link #GATE_DISABLED} — {@code isFastCraftMacrosEnabled} is {@code false}; fall through to
    *       the {@code TaskCatalogue} path without emitting any error (it is a user config knob).</li>
    *   <li>{@link #NO_CRAFTING_RECIPE} — macro is enabled but no {@code RecipeType.CRAFTING} recipe
    *       produces the requested item; fall through to the {@code TaskCatalogue} path AND, if the
    *       catalogue also has nothing, emit a truthful rejection to both player and model.</li>
    * </ul>
    */
   public enum MacroOutcome {
      MACRO_CREATED,
      GATE_DISABLED,
      NO_CRAFTING_RECIPE
   }

   /**
    * Probe result: the outcome classification plus the created task (non-null only when outcome is
    * {@link MacroOutcome#MACRO_CREATED}).
    */
   public record MacroProbe(MacroOutcome outcome, ResourceTask task) {
      /** Convenience: {@code null} unless {@link MacroOutcome#MACRO_CREATED}. */
      public ResourceTask taskOrNull() {
         return outcome == MacroOutcome.MACRO_CREATED ? task : null;
      }
   }

   /**
    * Classify WHY a macro task could or could not be created for {@code target}, and return the task
    * when creation succeeds.
    *
    * <p>Semantics:
    * <ol>
    *   <li>If {@code isFastCraftMacrosEnabled} is {@code false}: returns {@link MacroOutcome#GATE_DISABLED}
    *       (fall to catalogue; not an error).</li>
    *   <li>If the macro is enabled but no {@code RecipeType.CRAFTING} recipe produces the resolved output
    *       item: returns {@link MacroOutcome#NO_CRAFTING_RECIPE} (fall to catalogue; emit a truthful
    *       "deferred recipe type" rejection to both audiences if catalogue also has nothing).</li>
    *   <li>If the macro is enabled and a plan is produced: returns {@link MacroOutcome#MACRO_CREATED}
    *       with the task.</li>
    * </ol>
    *
    * <p>This replaces the previous opaque {@code null} return from {@link #tryCreateMacroTask} so
    * {@code GetCommand.resolveTask} can distinguish gate-off from no-recipe and respond truthfully
    * (DESIGN.md §3 / AGENTS.md dual-audience requirement).
    */
   public static MacroProbe macroOutcome(PlayerEngineController mod, ItemTarget target) {
      return macroOutcome(mod, target, null);
   }

   /**
    * WS4 overload: as {@link #macroOutcome(PlayerEngineController, ItemTarget)} but threading the
    * chain-scoped reservation {@code ledger} into the constructed {@link CraftMacroResourceTask} so the
    * resolver subtracts later agentic steps' pre-seeded reservations as an additive floor. {@code ledger}
    * is {@code null} for standalone crafts (e.g. {@code GetCommand}), making this byte-identical to the
    * pre-ledger behavior. This is the real chokepoint: every craft-macro path funnels through here.
    */
   public static MacroProbe macroOutcome(PlayerEngineController mod, ItemTarget target,
         @Nullable MaterialReservationService ledger) {
      if (!CraftMacroSupport.isMacroEnabled(mod)) {
         return new MacroProbe(MacroOutcome.GATE_DISABLED, null);
      }

      // Craft gate: resolve a single output item and ask the live RecipeManager whether any
      // RecipeType.CRAFTING recipe produces it. Items whose only path is smelt/smith/stonecut return
      // false and fall through to the TaskCatalogue path (no regression).
      MinecraftServer server = mod.getPlayer() != null ? mod.getPlayer().getServer() : null;
      if (server != null) {
         java.util.Optional<Item> outputItemOpt = CraftMacroSupport.resolveSingleOutputItem(mod, target);
         if (outputItemOpt.isPresent()) {
            RecipeManager recipeManager = server.getRecipeManager();
            RegistryAccess registries = server.registryAccess();
            Item out = outputItemOpt.get();
            // No-recipe OR every recipe is an UNFUNDED storage-unpack (e.g. iron_ingot whose only craft
            // path is decompressing iron_block, with no iron_block on hand) -> route to TaskCatalogue
            // (smelt/mine) instead of building a bogus "collect iron_ingot as raw" macro plan.
            if (!RECIPE_ACCESS.hasRecipe(recipeManager, out, registries)
                  || allRecipesAreUnfundedUnpacks(mod, recipeManager, registries, out)) {
               return new MacroProbe(MacroOutcome.NO_CRAFTING_RECIPE, null);
            }
         } else {
            // Cannot resolve to a single output item (e.g. unrecognised catalogue name with no
            // species-pick match). isSupportedTarget would re-run resolveSingleOutputItem on the same
            // target and also return false (a redundant RecipeManager scan), so its result is already
            // known here: NO_CRAFTING_RECIPE. Return directly — if we can't identify the output item we
            // can't craft it.
            return new MacroProbe(MacroOutcome.NO_CRAFTING_RECIPE, null);
         }
      } else {
         // No server available (single-player pre-join edge case); isSupportedTarget also needs
         // the server, so it will return false here. This collapses to NO_CRAFTING_RECIPE, which
         // is correct — we cannot determine craftability without a server.
         if (!CraftMacroSupport.isSupportedTarget(mod, target)) {
            return new MacroProbe(MacroOutcome.NO_CRAFTING_RECIPE, null);
         }
      }

      // Explicit-sign funding gate (owner rule 4): runs ONCE here at task creation, BEFORE the plan is
      // built and the species pin set (replan calls CraftMacroPlanner.plan directly and never re-enters
      // this). Non-sign and bare-"sign" targets pass through untouched (chest flow unchanged). On a
      // substitution the notice reaches BOTH audiences (DESIGN.md S3): the player via the owner-scoped
      // chat line NOW, the model via the task's creation note merged into the command-completion
      // feedback by GetCommand.onGetComplete.
      CraftMacroSupport.SignRequestAdjustment adj = CraftMacroSupport.adjustSignRequestForFunding(mod, target);
      ResourceTask task = CraftMacroPlanner.plan(mod, adj.target())
            .map(plan -> {
               CraftMacroResourceTask craftTask = new CraftMacroResourceTask(plan, ledger);
               if (adj.notice() != null && !adj.notice().isBlank()) {
                  craftTask.setCreationNote(adj.notice());
                  mod.reportAgenticProgress(adj.notice(), true);
               }
               return (ResourceTask) craftTask;
            })
            .orElse(null);

      if (task == null) {
         // CraftMacroPlanner.plan returned empty — no recipe (should be caught above once WS2 lands,
         // but handle defensively here; once planner returns empty ONLY for no-recipe, this path is
         // equivalent to NO_CRAFTING_RECIPE).
         return new MacroProbe(MacroOutcome.NO_CRAFTING_RECIPE, null);
      }
      return new MacroProbe(MacroOutcome.MACRO_CREATED, task);
   }

   /**
    * Thin wrapper over {@link #macroOutcome} that preserves the existing null-return contract for
    * callers that do not need the outcome classification (e.g. {@code ResolveStorageChestTask}).
    *
    * @return the created {@link ResourceTask}, or {@code null} when the macro is gated or no recipe
    *         exists.
    */
   public static ResourceTask tryCreateMacroTask(PlayerEngineController mod, ItemTarget target) {
      return tryCreateMacroTask(mod, target, null);
   }

   /**
    * WS4 overload: as {@link #tryCreateMacroTask(PlayerEngineController, ItemTarget)} but forwarding the
    * chain-scoped reservation {@code ledger} to {@link #macroOutcome}. In-run callers
    * ({@code ResolveStorageChestTask}, {@code LabelChestTask}) pass the run ledger so the craft respects
    * sibling steps' reservations; a {@code null} ledger is the standalone (no-op) path.
    */
   public static ResourceTask tryCreateMacroTask(PlayerEngineController mod, ItemTarget target,
         @Nullable MaterialReservationService ledger) {
      return macroOutcome(mod, target, ledger).taskOrNull();
   }

   /**
    * Gate-time availability test for the storage-unpack routing fix: returns {@code true} iff
    * {@code target} HAS recipes but EVERY recipe producing it is an UNFUNDED storage-unpack — i.e. a
    * reversible single-ingredient decompression (iron_block -> 9 iron_ingot) whose consumed ingredient
    * is not currently in the bot's inventory.
    *
    * <p>The SHAPE test is the shared, availability-free {@link RecipeAccess#storageUnpackIngredient};
    * here the availability term is deliberately {@code getItemStorage().getItemCount(I) > 0} (RAW live
    * inventory, NO reservation ledger) because at task-creation time no reservations exist yet. This
    * does NOT reuse {@code MaterialResolver}'s {@code freeCount}-baked check and does NOT thread a
    * ledger; both sites share only the one shape source so they cannot drift on what an "unpack" is.
    *
    * <p>Returns {@code false} when: the recipe list is empty (genuine raw material — handled by the
    * {@code hasRecipe} branch, no spurious fire here), ANY recipe is a real (fundable, non-circular)
    * craft, or ANY unpack recipe IS funded (its ingredient is on hand, so decompression can run now).
    *
    * <p><b>Nugget-chain guard (acceptance check 5):</b> a recipe that {@code storageUnpackIngredient}
    * does NOT classify can still be an unfunded dead-end rather than a real craft path — notably the
    * reverse {@code 9 iron_nugget -> 1 iron_ingot} recipe, which escapes the unpack shape only via the
    * {@code yield>=2} pre-filter. Such a single-ingredient recipe is also non-routable when its consumed
    * ingredient is (1) not on hand AND (2) itself has no genuine procurement at the gate — i.e. every
    * recipe producing it is an unfunded unpack back into {@code target} (circular). Treating that as
    * non-blocking (continue, not {@code return false}) prevents the planner from building a
    * nugget->ingot plan that recurses to {@code iron_nugget} and emits a bogus 216-count external. The
    * recursion is bounded to ONE level deep (we only inspect the ingredient's own recipes, not further).
    */
   private static boolean allRecipesAreUnfundedUnpacks(
         PlayerEngineController mod, RecipeManager mgr, RegistryAccess registries, Item target) {
      List<CraftingRecipe> recs = RECIPE_ACCESS.recipesForResult(mgr, target, registries);
      if (recs.isEmpty()) {
         return false;
      }
      for (CraftingRecipe candidate : recs) {
         Optional<Item> ing = RECIPE_ACCESS.storageUnpackIngredient(mgr, target, registries, candidate);
         if (ing.isPresent()) {
            if (mod.getItemStorage().getItemCount(ing.get()) > 0) {
               // This unpack IS funded (ingredient on hand) -> craftable now via decompression.
               return false;
            }
            continue;
         }
         // Not classified as a storage unpack. It is STILL non-routable if it is a single-ingredient
         // recipe whose ingredient is unavailable AND has no genuine procurement (the unfunded
         // nugget->ingot reverse pair). Otherwise a real craft path exists -> not all unpacks.
         if (!isUnfundedCircularSingleIngredient(mod, mgr, registries, target, candidate)) {
            return false;
         }
      }
      return true;
   }

   /**
    * One-level-deep gate-time check for the nugget-chain regression: {@code true} iff {@code candidate}
    * has a single distinct consumed ingredient {@code J} that is (a) not currently on hand and (b) has
    * no genuine procurement path of its own at the gate — every recipe producing {@code J} is itself an
    * unfunded storage-unpack back into {@code target} (i.e. {@code J} is only obtainable by unpacking
    * {@code target}, which is circular). Such a {@code candidate} is an unfunded dead-end, not a real
    * craft path, so the caller treats it as non-blocking. Availability uses raw live inventory
    * ({@code getItemCount}); no reservation ledger exists at task-creation time.
    */
   private static boolean isUnfundedCircularSingleIngredient(
         PlayerEngineController mod, RecipeManager mgr, RegistryAccess registries,
         Item target, CraftingRecipe candidate) {
      Optional<Item> ingOpt = RECIPE_ACCESS.singleConsumedIngredient(candidate);
      if (ingOpt.isEmpty()) {
         return false; // multi-ingredient / multi-variant -> a genuine craft path, not a dead-end.
      }
      Item ingredient = ingOpt.get();
      if (mod.getItemStorage().getItemCount(ingredient) > 0) {
         return false; // ingredient on hand -> craftable now.
      }
      List<CraftingRecipe> ingRecipes = RECIPE_ACCESS.recipesForResult(mgr, ingredient, registries);
      if (ingRecipes.isEmpty()) {
         return false; // ingredient is genuinely raw/gatherable -> a real procurement path exists.
      }
      for (CraftingRecipe ingRecipe : ingRecipes) {
         Optional<Item> back = RECIPE_ACCESS.singleConsumedIngredient(ingRecipe);
         // A recipe for J that does NOT consume exactly the original target is a genuine (non-circular)
         // way to obtain J -> the candidate is routable, not a dead-end.
         if (back.isEmpty() || back.get() != target) {
            return false;
         }
      }
      // Every recipe for the ingredient circles straight back into target, and the ingredient is not on
      // hand -> this candidate is an unfunded circular dead-end.
      return true;
   }
}
