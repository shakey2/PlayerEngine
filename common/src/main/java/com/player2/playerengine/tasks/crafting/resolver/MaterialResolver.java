package com.player2.playerengine.tasks.crafting.resolver;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.crafting.CraftMacroStep;
import com.player2.playerengine.tasks.crafting.CraftMacroStepKind;
import com.player2.playerengine.tasks.crafting.resolver.IngredientInspector.SlotKind;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Deterministic, on-device material resolver: given a target {@link Item} + count and the bot's
 * CURRENT inventory (read through {@link PlayerEngineController}), compute the remaining work to
 * produce the target right now — an ordered list of sub-craft {@link CraftMacroStep}s plus a list of
 * external raw acquisitions — or a terminal {@link ResolverResult.Status#UNOBTAINABLE} verdict with a
 * human-meaningful reason.
 *
 * <p><b>This class is destined to be byte-identical across the 1.20.1 and 1.21.1 PlayerEngine
 * branches.</b> It depends ONLY on the two cross-version abstractions {@link IngredientInspector} and
 * {@link RecipeAccess} (both constructor-injected by the executor wiring step), never on a
 * version-specific recipe API directly. The MC {@link CraftingRecipe} interface FQN it references
 * exists with the same name in both versions; all version divergence (the {@code RecipeHolder} unwrap
 * and the {@code Recipe<CraftingContainer>} vs {@code Recipe<CraftingInput>} generic bound) lives
 * inside {@code RecipeAccessImpl}.
 *
 * <p><b>Deterministic invariant:</b> this class performs ZERO Player2/AiTask/Joules/player2api calls.
 * It only reads inventory counts and the Minecraft recipe/tag system. Re-derivation is
 * recompute-from-inventory (cheap), never a world block re-scan; raw-material <i>acquisition</i> is
 * left to the executor's existing gather tasks via {@link ResolverResult#externalNeeded()}.
 *
 * <p><b>Variant lock</b> is achieved purely by selecting the recipe whose result equals the concrete
 * target ({@link RecipeAccess#selectRecipeForResult}); that recipe's own ingredient slots are then the
 * only legal inputs, so a {@code get oak_planks} request can never consume spruce. No wood-family map
 * is consulted to choose inputs.
 */
public final class MaterialResolver {

   /**
    * Recursion depth guard: deep enough for the supported macro outputs (log -> planks -> chest/table
    * -> sign is 3 tiers) with headroom, shallow enough that a pathological recipe cycle cannot blow the
    * stack. On overflow the affected branch degrades to an external acquisition rather than crashing.
    */
   private static final int MAX_DEPTH = 8;

   private final IngredientInspector inspector;
   private final RecipeAccess recipes;

   /**
    * Change-gate for the per-pass poolCredit/stepEmit arithmetic diag lines: a steady-state resolve
    * loop logs transitions instead of ~1000 identical lines per minute.
    */
   private String lastArithmeticDiagSig = "";

   public MaterialResolver(IngredientInspector inspector, RecipeAccess recipes) {
      this.inspector = inspector;
      this.recipes = recipes;
   }

   /**
    * Compute the remaining work to obtain {@code count} of {@code target} from the controller's current
    * inventory. Deterministic; no model calls.
    *
    * @param controller live bot controller (inventory counts + server/recipe-manager access)
    * @param target     concrete output item (already variant-resolved upstream by
    *                   {@code CraftMacroSupport.resolveSingleOutputItem})
    * @param count      requested output count
    * @return a {@link ResolverResult} carrying status, ordered sub-craft steps, external acquisitions,
    *         the tiered {@code remainingDeficit}, and (when UNOBTAINABLE) a human-meaningful reason
    */
   public ResolverResult resolve(PlayerEngineController controller, Item target, int count) {
      if (target == null || count <= 0) {
         return new ResolverResult(ResolverResult.Status.READY, List.of(), List.of(), 0, "");
      }

      MinecraftServer server = (controller.getPlayer() != null) ? controller.getPlayer().getServer() : null;
      if (server == null) {
         // No server context => cannot read recipes. Degrade visibly rather than invent certainty.
         return new ResolverResult(
               ResolverResult.Status.UNOBTAINABLE,
               List.of(),
               List.of(),
               count,
               "Cannot resolve " + itemName(target) + ": no server context to read recipes.");
      }
      RecipeManager mgr = server.getRecipeManager();
      RegistryAccess registries = server.registryAccess();

      // Already have enough -> nothing remaining.
      int have = controller.getItemStorage().getItemCount(target);
      if (have >= count) {
         return new ResolverResult(ResolverResult.Status.READY, List.of(), List.of(), 0, "");
      }

      List<CraftMacroStep> steps = new ArrayList<>();
      List<ItemTarget> external = new ArrayList<>();
      List<Integer> externalCredited = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      // Reserved inventory: items already earmarked to satisfy a deeper tier this resolve, so two
      // sibling slots cannot both claim the same held stack. Reservations NEVER persist beyond this
      // call: they exist only inside this one pass and are recomputed fresh from live inventory.
      java.util.Map<Item, Integer> reserved = new java.util.HashMap<>();
      // Per-ITEM planned production-minus-consumption ledger, threaded exactly like `reserved` (see
      // the predicted-held threshold rule and worked examples on resolvePooledSlot). Written only at
      // step emission; read only by pooledThreshold.
      java.util.Map<Item, Integer> plannedDelta = new java.util.HashMap<>();
      // Planned rounding overshoot per crafted item (crafts*yield - shortfall), threaded exactly like
      // `reserved`; later slots in the SAME pass draw on it before emitting crafts/externals (the
      // sign's stick is funded by the plank step's 2 leftover planks). Fresh per pass, never persisted.
      java.util.Map<Item, Integer> surplus = new java.util.HashMap<>();
      List<String> passDiag = new ArrayList<>();

      // Top-level target: no pooled output (the requested item is concrete/variant-locked upstream),
      // the satisfaction threshold defaults to `count`, and agnostic ancestry starts FALSE — it flips
      // true only inside a multi-variant agnostic pooled slot (e.g. a chest's #planks), so a
      // variant-locked request (get oak_planks) emits NARROW per-species externals.
      ResolverResult.Status status = resolveInto(
            controller, mgr, registries, target, count, null, 0, steps, external, externalCredited,
            failures, reserved, plannedDelta, surplus, passDiag, false, 0);

      CoalescedExternals merged = coalesceExternals(external, externalCredited);

      int deficit = 0;
      for (CraftMacroStep step : steps) {
         deficit += Math.max(0, step.craftsNeeded());
      }
      for (ItemTarget t : merged.targets()) {
         deficit += Math.max(0, t.getTargetCount());
      }

      String header = "resolve target=" + itemName(target) + " x" + count;
      if (status == ResolverResult.Status.UNOBTAINABLE) {
         String reason = unobtainableReason(itemName(target), failures, merged.targets());
         logResolvedPlan(controller, header, ResolverResult.Status.UNOBTAINABLE,
               steps, merged.targets(), merged.creditedHeld(), deficit);
         emitArithmeticDiag(passDiag);
         return new ResolverResult(ResolverResult.Status.UNOBTAINABLE, steps, merged.targets(), deficit, reason);
      }
      if (steps.isEmpty() && merged.targets().isEmpty()) {
         logResolvedPlan(controller, header, ResolverResult.Status.READY, List.of(), List.of(), List.of(), 0);
         emitArithmeticDiag(passDiag);
         return new ResolverResult(ResolverResult.Status.READY, List.of(), List.of(), 0, "");
      }
      logResolvedPlan(controller, header, ResolverResult.Status.NEEDS_WORK,
            steps, merged.targets(), merged.creditedHeld(), deficit);
      emitArithmeticDiag(passDiag);
      return new ResolverResult(ResolverResult.Status.NEEDS_WORK, steps, merged.targets(), deficit, "");
   }

   /**
    * One target of a {@link #resolveBudget} multiset request: the concrete output {@link Item} (already
    * variant-resolved upstream) and the requested count. Ordered: earlier targets reserve shared held
    * stock first, exactly as nested ingredient slots do within a single resolve.
    */
   public record BudgetTarget(Item item, int count) {}

   /**
    * Evaluate the executor's LIVE requirement set: resolve an ORDERED multiset of output targets through
    * ONE SHARED reservation pass, so any held raw/intermediate stock shared between the targets is
    * credited EXACTLY ONCE across all of them.
    *
    * <p><b>Contract.</b> The executor calls this every tick with its live requirement set — the macro
    * output first; the crafting-table target is present exactly while the executor's on-demand table
    * requirement is active. Because the {@code reserved} map is shared and the targets are ordered, the
    * FIRST target earmarks the held stock it consumes and later targets see only what is genuinely FREE —
    * so e.g. a crafting table's planks are always requirements ON TOP of the output's still-reserved
    * planks and can never silently consume them. Summing two INDEPENDENT resolves instead would
    * double-credit any held log/plank (each fresh reserved map nets the same held stock against BOTH
    * needs) and under-state the true remaining demand.
    *
    * <p>Reservations never persist: they exist only inside this one pass. Re-running it each tick from
    * live inventory keeps convergence sound — the externals shrink as materials are gathered, and
    * adding/removing a requirement in the executor's set is what creates/releases its reservations on
    * the very next pass.
    *
    * <p><b>Byte-identical / version-stable.</b> Uses only the same server/recipe/registry access as
    * {@link #resolve} and the shared {@code resolveInto}; no 1.20.1-only API, so it ports unchanged to
    * 1.21.1.
    *
    * @param controller live bot controller (inventory counts + server/recipe-manager access)
    * @param targets    the ordered live requirement set (e.g. [(chest, 1), (crafting_table, 1)])
    * @return a merged {@link ResolverResult} whose {@code externalNeeded()} reflects the TOTAL raw demand of
    *         all targets with shared held stock subtracted ONCE; never null
    */
   public ResolverResult resolveBudget(PlayerEngineController controller, List<BudgetTarget> targets) {
      if (targets == null || targets.isEmpty()) {
         return new ResolverResult(ResolverResult.Status.READY, List.of(), List.of(), 0, "");
      }

      MinecraftServer server = (controller.getPlayer() != null) ? controller.getPlayer().getServer() : null;
      if (server == null) {
         return new ResolverResult(
               ResolverResult.Status.UNOBTAINABLE,
               List.of(),
               List.of(),
               0,
               "Cannot obtain materials for " + describeBudgetTargets(targets)
                     + ": no server context to read recipes.");
      }
      RecipeManager mgr = server.getRecipeManager();
      RegistryAccess registries = server.registryAccess();

      List<CraftMacroStep> steps = new ArrayList<>();
      List<ItemTarget> external = new ArrayList<>();
      List<Integer> externalCredited = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      // ONE shared reserved map across ALL targets: held stock a prior target earmarks is invisible (not
      // free) to later targets, so shared held logs/planks are reserved/credited exactly once across the set.
      java.util.Map<Item, Integer> reserved = new java.util.HashMap<>();
      // ONE shared per-item planned-delta ledger too: steps from different targets feeding OVERLAPPING
      // pooled output sets (a sign's locked [oak_planks] + a table's any-planks slot) gate on the same
      // predicted held totals, so one target's planned stock raises a later target's pooled threshold.
      java.util.Map<Item, Integer> plannedDelta = new java.util.HashMap<>();
      // ONE shared planned-overshoot ledger too (threaded like `reserved`): a later target's slot may
      // draw on an earlier target's rounding leftovers instead of emitting a fresh craft/external.
      java.util.Map<Item, Integer> surplus = new java.util.HashMap<>();
      List<String> passDiag = new ArrayList<>();

      ResolverResult.Status worst = ResolverResult.Status.READY;
      for (BudgetTarget bt : targets) {
         if (bt == null || bt.item() == null || bt.count() <= 0) {
            continue;
         }
         // Already have enough of THIS target (after prior reservations)? Reserve it and contribute nothing
         // (no steps, no external) — the final status falls out of whether the accumulators stay empty.
         int free = freeCount(controller, reserved, bt.item());
         if (free >= bt.count()) {
            reserve(reserved, bt.item(), bt.count());
            continue;
         }
         ResolverResult.Status sub = resolveInto(
               controller, mgr, registries, bt.item(), bt.count(), null, 0,
               steps, external, externalCredited, failures, reserved, plannedDelta, surplus, passDiag, false, 0);
         if (sub == ResolverResult.Status.UNOBTAINABLE) {
            worst = ResolverResult.Status.UNOBTAINABLE;
         } else if (worst != ResolverResult.Status.UNOBTAINABLE) {
            worst = ResolverResult.Status.NEEDS_WORK;
         }
      }

      // Coalesce same-pool external entries (two targets may each append a "log" acquisition) so the
      // COLLECT phase sees ONE entry per raw pool with the summed count (e.g. log×2 + log×1 -> log×3),
      // matching the single-entry shape the executor's COLLECT loop expects. Net counts already account for
      // shared held stock once (shared reserved map above); this only merges identical raw pools.
      CoalescedExternals merged = coalesceExternals(external, externalCredited);

      int deficit = 0;
      for (CraftMacroStep step : steps) {
         deficit += Math.max(0, step.craftsNeeded());
      }
      for (ItemTarget t : merged.targets()) {
         deficit += Math.max(0, t.getTargetCount());
      }

      String header = "resolveBudget requirements=" + describeBudgetTargetList(targets);
      if (worst == ResolverResult.Status.UNOBTAINABLE) {
         String reason = unobtainableReason(describeBudgetTargets(targets), failures, merged.targets());
         logResolvedPlan(controller, header, ResolverResult.Status.UNOBTAINABLE,
               steps, merged.targets(), merged.creditedHeld(), deficit);
         emitArithmeticDiag(passDiag);
         return new ResolverResult(ResolverResult.Status.UNOBTAINABLE, steps, merged.targets(), deficit, reason);
      }
      if (steps.isEmpty() && merged.targets().isEmpty()) {
         logResolvedPlan(controller, header, ResolverResult.Status.READY, List.of(), List.of(), List.of(), 0);
         emitArithmeticDiag(passDiag);
         return new ResolverResult(ResolverResult.Status.READY, List.of(), List.of(), 0, "");
      }
      logResolvedPlan(controller, header, ResolverResult.Status.NEEDS_WORK,
            steps, merged.targets(), merged.creditedHeld(), deficit);
      emitArithmeticDiag(passDiag);
      return new ResolverResult(ResolverResult.Status.NEEDS_WORK, steps, merged.targets(), deficit, "");
   }

   /**
    * Compose an UNOBTAINABLE reason naming the concrete target(s) and the owed shortfalls — never
    * internal jargon. These strings reach the player and the model verbatim through the executor's
    * {@code terminateMacro} -> dual-audience plumbing (DESIGN.md §3), so they must be human-readable
    * and must never contradict the inventory snapshot delivered alongside them.
    */
   private static String unobtainableReason(String targetsDesc, List<String> failures, List<ItemTarget> owedExternals) {
      String owed = describeOwedExternals(owedExternals);
      String detail;
      if (!failures.isEmpty()) {
         detail = String.join("; ", failures);
      } else if (!owed.isEmpty()) {
         detail = "need " + owed + " and no reachable source";
      } else {
         detail = "a required ingredient has no obtainable source";
      }
      return "Cannot obtain materials for " + targetsDesc + ": " + detail + ".";
   }

   /** Owed external shortfalls for reason strings, e.g. {@code "1 more log and 2 more iron_ingot"}. */
   private static String describeOwedExternals(List<ItemTarget> external) {
      StringBuilder sb = new StringBuilder();
      for (ItemTarget t : external) {
         if (ItemTarget.nullOrEmpty(t) || t.getTargetCount() <= 0) {
            continue;
         }
         if (sb.length() > 0) {
            sb.append(" and ");
         }
         sb.append(t.getTargetCount()).append(" more ").append(externalDisplayName(t));
      }
      return sb.toString();
   }

   /** Display name of an external target: its catalogue name, else the first match's registry name. */
   private static String externalDisplayName(ItemTarget t) {
      if (t.isCatalogueItem() && t.getCatalogueName() != null) {
         return t.getCatalogueName();
      }
      Item[] m = t.getMatches();
      return (m.length > 0 && m[0] != null) ? itemName(m[0]) : "unknown";
   }

   /** Prose description of a requirement set for reason strings, e.g. "chest and its crafting table". */
   private static String describeBudgetTargets(List<BudgetTarget> targets) {
      List<String> names = new ArrayList<>();
      for (BudgetTarget bt : targets) {
         if (bt != null && bt.item() != null && bt.count() > 0) {
            names.add(itemName(bt.item()));
         }
      }
      if (names.isEmpty()) {
         return "the requested items";
      }
      if (names.size() == 2 && "crafting_table".equals(names.get(1))) {
         return names.get(0) + " and its crafting table";
      }
      return String.join(" and ", names);
   }

   /** Diag echo of a requirement set, e.g. {@code [chest x1, crafting_table x1]}. */
   private static String describeBudgetTargetList(List<BudgetTarget> targets) {
      StringBuilder sb = new StringBuilder("[");
      for (BudgetTarget bt : targets) {
         if (bt == null || bt.item() == null) {
            continue;
         }
         if (sb.length() > 1) {
            sb.append(", ");
         }
         sb.append(itemName(bt.item())).append(" x").append(bt.count());
      }
      return sb.append(']').toString();
   }

   /** A coalesced external list plus the parallel per-entry creditedHeld diag values. */
   private record CoalescedExternals(List<ItemTarget> targets, List<Integer> creditedHeld) {}

   /**
    * Merge a NET external list by summing counts of same-pool targets (e.g. a "log" from the output target
    * and a "log" from the crafting-table target -> a single "log" with the summed count). Pools by catalogue
    * name when present (the resolver names pooled raw inputs, e.g. "log"), else by the exact match-set
    * signature. Counts are already net of shared held stock (single shared reserved map), so this is a pure
    * presentation merge, not a re-net. First-seen order preserved. The parallel creditedHeld values measure
    * held stock over the SAME credit membership for same-key entries, so they merge by max, not sum.
    */
   private static CoalescedExternals coalesceExternals(List<ItemTarget> external, List<Integer> credited) {
      java.util.LinkedHashMap<String, ItemTarget> byKey = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, Integer> sum = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, Integer> creditMax = new java.util.LinkedHashMap<>();
      for (int i = 0; i < external.size(); i++) {
         ItemTarget t = external.get(i);
         if (ItemTarget.nullOrEmpty(t) || t.getTargetCount() <= 0) {
            continue;
         }
         String key = externalCoalesceKey(t);
         byKey.putIfAbsent(key, t);
         sum.merge(key, t.getTargetCount(), Integer::sum);
         int c = (i < credited.size()) ? credited.get(i) : 0;
         creditMax.merge(key, c, Integer::max);
      }
      List<ItemTarget> mergedTargets = new ArrayList<>(byKey.size());
      List<Integer> mergedCredited = new ArrayList<>(byKey.size());
      for (java.util.Map.Entry<String, ItemTarget> e : byKey.entrySet()) {
         mergedTargets.add(new ItemTarget(e.getValue(), sum.get(e.getKey())));
         mergedCredited.add(creditMax.get(e.getKey()));
      }
      return new CoalescedExternals(mergedTargets, mergedCredited);
   }

   /** Stable pooling key for coalescing external targets: catalogue name if present, else match-set signature. */
   private static String externalCoalesceKey(ItemTarget t) {
      if (t.isCatalogueItem() && t.getCatalogueName() != null) {
         return "cat:" + t.getCatalogueName();
      }
      StringBuilder sb = new StringBuilder("set:");
      Item[] matches = t.getMatches();
      String[] names = new String[matches.length];
      for (int i = 0; i < matches.length; i++) {
         names[i] = matches[i] == null ? "null" : matches[i].toString();
      }
      java.util.Arrays.sort(names);
      for (String n : names) {
         sb.append(n).append('|');
      }
      return sb.toString();
   }

   /**
    * Bottom-up deficit computation for {@code need} of {@code want}. Appends the deepest sub-crafts
    * first and the {@code want} craft last (so {@code steps} stays in execution order). Returns the
    * worst sub-status encountered (UNOBTAINABLE if any required input is unreachable, else NEEDS_WORK).
    *
    * <p><b>Executor satisfaction contract (see {@code CraftMacroResourceTask.inventoryStepSatisfied}):</b>
    * each emitted {@link CraftMacroStep} is reported "satisfied" when
    * {@code getItemCount(outputSet) >= recipeTarget().getTargetCount()}, where {@code outputSet} is
    * {@code outputMatches} when non-null else the single {@code recipeTarget().getOutputItem()}. The
    * threshold therefore MUST be the DEMAND for this tier's OUTPUT (how many of the output are needed),
    * NOT the per-craft yield; and {@code outputMatches} MUST describe the step's OUTPUT (the pooled
    * variant set when this craft satisfies an agnostic parent slot, e.g. all planks for a chest), NEVER
    * an input ingredient slot. {@code craftsNeeded} stays the number of craft operations
    * ({@code ceilDiv(shortfall, yield)}); the wrapper recipe keeps {@code yield} as its outputCount for
    * {@code performCrafts}. ({@code RecipeTarget.targetCount} is read ONLY by the satisfaction gate;
    * {@code performCrafts}/{@code performSingleCraft} use the wrapper's {@code outputCount()} and the
    * step's {@code craftsNeeded}, never the target count.)
    *
    * @param outputPool when non-null, the pooled set of items that satisfy this craft's OUTPUT — passed
    *                   by {@link #resolvePooledSlot} for an agnostic parent slot so mixed variants count;
    *                   null for a variant-locked craft, which keeps strict single-output satisfaction.
    * @param satisfyThreshold the DEMAND value to record as the step's {@code recipeTarget.targetCount}
    *                   (the satisfaction gate's threshold); when {@code <= 0} it defaults to {@code need}.
    *                   For a pooled slot this is the PREDICTED-HELD threshold (see
    *                   {@link #pooledThreshold}) so raw held pool stock — including stock reserved under
    *                   OVERLAPPING match sets by other slots — and earlier-executing steps' planned
    *                   deltas count, while {@code need} (the shortfall to produce) drives crafts.
    * @param externalCredited parallel list to {@code external}: held count over each emitted external's
    *                   CREDIT membership (diag; see {@link #creditedHeldFor}).
    * @param plannedDelta per-item planned production-minus-consumption ledger, threaded exactly like
    *                   {@code reserved}; written ONLY at step emission here (production {@code +crafts*yield},
    *                   attributed slot consumption {@code -n}; table-bound output steps are skipped — the
    *                   FSM sequences them AFTER every inventory gate) and read only by
    *                   {@link #pooledThreshold}.
    * @param surplus    per-pass planned rounding overshoot per crafted item, threaded exactly like
    *                   {@code reserved}; FILLED only here (right after step emission) and DRAWN only by
    *                   {@link #resolvePooledSlot}'s surplus draw.
    * @param passDiag   per-pass poolCredit/stepEmit arithmetic diag lines (change-gated at emission).
    * @param agnosticAncestry true when this resolve descends from a multi-variant AGNOSTIC pooled slot
    *                   (e.g. a chest's #planks): raw externals keep the WIDE pool naming ("log"). False
    *                   for a variant-locked descent (get oak_planks, a sign's [oak_planks] slot): raw
    *                   externals are named NARROW by the slot's own raw NATURAL variant, so the
    *                   dispatched gather's match set is a subset of what the arithmetic credits —
    *                   membership coherence, the anti-escalation invariant.
    */
   private ResolverResult.Status resolveInto(
         PlayerEngineController controller,
         RecipeManager mgr,
         RegistryAccess registries,
         Item want,
         int need,
         Item[] outputPool,
         int satisfyThreshold,
         List<CraftMacroStep> steps,
         List<ItemTarget> external,
         List<Integer> externalCredited,
         List<String> failures,
         java.util.Map<Item, Integer> reserved,
         java.util.Map<Item, Integer> plannedDelta,
         java.util.Map<Item, Integer> surplus,
         List<String> passDiag,
         boolean agnosticAncestry,
         int depth) {

      int threshold = (satisfyThreshold > 0) ? satisfyThreshold : need;

      // Consume any unreserved held stock of `want` first.
      int free = freeCount(controller, reserved, want);
      int shortfall = need - free;
      if (shortfall <= 0) {
         reserve(reserved, want, need);
         return ResolverResult.Status.NEEDS_WORK; // satisfied from inventory at this tier
      }
      // Earmark whatever free stock we do hold; only the shortfall must be produced/acquired.
      reserve(reserved, want, free);

      if (depth >= MAX_DEPTH) {
         addExternal(controller, external, externalCredited,
               new Item[]{want}, externalNameFor(want, agnosticAncestry), shortfall);
         return ResolverResult.Status.NEEDS_WORK;
      }

      // FUNDING-AWARE recipe selection: among ALL recipes producing `want`, prefer one whose held free
      // raw inputs FULLY fund this shortfall (so `get stick` with no bamboo picks the planks recipe; a
      // bamboo recipe can only win when held bamboo fully funds it). Emptiness is identical to the old
      // selectRecipeForResult emptiness, so the raw-material branch below is preserved.
      Optional<CraftingRecipe> recipeOpt =
            selectFundedRecipe(controller, reserved, mgr, registries, want, shortfall, passDiag);
      if (recipeOpt.isEmpty()) {
         // No recipe produces `want` -> it is a raw material. Route to external acquisition.
         addExternal(controller, external, externalCredited,
               new Item[]{want}, externalNameFor(want, agnosticAncestry), shortfall);
         return ResolverResult.Status.NEEDS_WORK;
      }

      CraftingRecipe recipe = recipeOpt.get();
      int yield = Math.max(1, recipes.outputCountOf(recipe, registries));
      int crafts = ceilDiv(shortfall, yield);

      // Walk this recipe's ingredient slots; recurse to satisfy each slot's per-craft demand. IDENTICAL
      // slots are AGGREGATED first (a chest has 8 #planks slots) so we recurse ONCE per distinct slot
      // with the combined demand, instead of emitting one redundant sub-craft step per identical slot.
      NonNullList<Ingredient> ingredients = recipe.getIngredients();
      ResolverResult.Status worst = ResolverResult.Status.NEEDS_WORK;
      List<ItemTarget> slotTargets = new ArrayList<>();
      // Per-slot distinct signature, parallel to slotTargets (null for empty/unbound slots), so a
      // fully-attributed slot's grid targets can be NARROWED after the recursion (see below).
      List<String> slotSigs = new ArrayList<>();
      // Aggregate distinct non-empty slots in first-seen order: signature(item-name list) -> aggregate.
      java.util.LinkedHashMap<String, SlotDemand> distinct = new java.util.LinkedHashMap<>();

      for (Ingredient ing : ingredients) {
         if (ing == null || ing.isEmpty()) {
            slotTargets.add(ItemTarget.EMPTY);
            slotSigs.add(null);
            continue;
         }
         SlotKind kind = inspector.classify(ing);
         Set<Item> accepted = inspector.acceptedItems(ing);
         Item[] matchSet = toItemArray(accepted);
         if (matchSet.length == 0) {
            // No known accepted item (e.g. unbound tag): record and treat the slot as unobtainable.
            failures.add("a " + itemName(want) + " ingredient has no obtainable item");
            slotTargets.add(ItemTarget.EMPTY);
            slotSigs.add(null);
            worst = ResolverResult.Status.UNOBTAINABLE;
            continue;
         }
         // The grid representation for this slot (one of this ingredient consumed per craft).
         slotTargets.add(new ItemTarget(matchSet, 1));
         // Aggregate this slot's per-craft demand (= number of crafts) into its distinct bucket.
         String sig = slotSignature(matchSet);
         slotSigs.add(sig);
         SlotDemand agg = distinct.get(sig);
         if (agg == null) {
            distinct.put(sig, new SlotDemand(matchSet, kind, crafts));
         } else {
            agg.demand += crafts;
         }
      }

      // Recurse once per distinct slot with its aggregated demand (deepest-first ordering preserved).
      // Each slot records its per-item CONSUMPTION ATTRIBUTION (which held drains / surplus draws /
      // planned-production allocations satisfy it) so the emission below can ledger this craft's
      // consumption and narrow the grid to exactly the allocated variants.
      java.util.LinkedHashMap<String, java.util.Map<Item, Integer>> slotConsumption =
            new java.util.LinkedHashMap<>();
      for (java.util.Map.Entry<String, SlotDemand> e : distinct.entrySet()) {
         SlotDemand slot = e.getValue();
         java.util.Map<Item, Integer> consumed = new java.util.HashMap<>();
         slotConsumption.put(e.getKey(), consumed);
         // A multi-variant (AGNOSTIC) slot pools its variants for satisfaction; a single-variant slot is
         // variant-locked (no pool). The pool flows to the produced sub-step's outputMatches.
         Item[] pool = (slot.kind == SlotKind.AGNOSTIC && slot.matchSet.length > 1) ? slot.matchSet : null;
         ResolverResult.Status sub = resolvePooledSlot(
               controller, mgr, registries, slot.matchSet, pool, slot.demand,
               steps, external, externalCredited, failures, reserved, plannedDelta, surplus, consumed,
               passDiag, agnosticAncestry, depth + 1);
         if (sub == ResolverResult.Status.UNOBTAINABLE) {
            worst = ResolverResult.Status.UNOBTAINABLE;
         }
      }

      // CONSUMPTION NARROWING (membership coherence extended to EXECUTION): when a slot's whole demand
      // was attributed to concrete items, narrow its grid targets to exactly those variants, so
      // performCrafts' first-matching removal (CraftingInventoryOps.performSingleCraft) cannot consume
      // pool stock another requirement reserved under an OVERLAPPING match set — e.g. a crafting
      // table's any-planks slot eating the sign's 6 reserved oak planks. A slot with external residue
      // (species unknown until gathered) keeps its wide set; the step cannot execute until the external
      // is collected, and the per-tick re-resolve re-attributes it from held stock then.
      for (int i = 0; i < slotTargets.size(); i++) {
         String sig = slotSigs.get(i);
         if (sig == null) {
            continue;
         }
         SlotDemand slot = distinct.get(sig);
         java.util.Map<Item, Integer> consumed = slotConsumption.get(sig);
         Item[] narrowed = (slot != null && consumed != null) ? narrowedSlotMatches(slot, consumed) : null;
         if (narrowed != null) {
            slotTargets.set(i, new ItemTarget(narrowed, 1));
         }
      }

      // Emit the craft step for `want` AFTER its ingredients (deepest-first ordering).
      //   - targetCount = `threshold` (DEMAND for this output at this tier; the predicted-held pooled
      //     threshold for a pooled slot), so the satisfaction gate counts held OUTPUT (including
      //     pre-existing stock) against the real demand, not the per-craft yield.
      //   - outputMatches = `outputPool` (the pooled OUTPUT set when satisfying an agnostic parent slot,
      //     e.g. all planks for a chest), NEVER an input slot; null keeps strict single-output counting.
      //   - the wrapper recipe still carries `yield` as its outputCount for performCrafts.
      CraftMacroStepKind stepKind = stepKindFor(want, recipe);
      RecipeTarget recipeTarget = toRecipeTarget(want, threshold, yield, slotTargets, recipe);
      steps.add(new CraftMacroStep(stepKind, recipeTarget, crafts, debugLabel(want), outputPool));
      int produced = crafts * yield;
      // PLANNED-DELTA LEDGER (single write site): record this step's production and its attributed slot
      // consumption so later pooled thresholds predict the gate's raw held count exactly. A table-bound
      // output craft (CRAFT_OUTPUT_IN_TABLE) is SKIPPED: the FSM sequences it AFTER every inventory-step
      // gate (inventorySteps() filters it out; it runs via the table phases), so its production and
      // consumption must not shift earlier-executing gates. External-fed consumption is never ledgered
      // (raw items never appear in a step's output pool, so no gate counts them).
      if (stepKind != CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE) {
         plannedDelta.merge(want, produced, Integer::sum);
         for (java.util.Map<Item, Integer> consumed : slotConsumption.values()) {
            for (java.util.Map.Entry<Item, Integer> c : consumed.entrySet()) {
               if (c.getValue() > 0) {
                  plannedDelta.merge(c.getKey(), -c.getValue(), Integer::sum);
               }
            }
         }
      }
      passDiag.add("stepEmit output=" + itemName(want) + " u=" + produced
            + " targetCount=" + threshold + " kind=" + stepKind);
      // SINGLE surplus-fill site: record this step's planned rounding overshoot so later slots in the
      // SAME pass can draw it (resolvePooledSlot's surplus draw) instead of emitting fresh work. Every
      // caller passes an exact (already-consumed) need except the ceilDiv-rounded paths, whose callers
      // deliberately pass the true residual so the overshoot is recorded HERE and nowhere else.
      int overshoot = produced - shortfall;
      if (overshoot > 0) {
         int total = surplus.merge(want, overshoot, Integer::sum);
         passDiag.add("surplusCredit(" + itemName(want) + ") +" + overshoot + " -> " + total);
      }
      return worst;
   }

   /**
    * Narrowed grid match set for a slot whose WHOLE demand the resolution attributed to concrete items
    * (held drains, surplus draws, credit-loop/residual planned production). Returns null — keep the
    * slot's original wide set — when any residue went to an external acquisition (the species is
    * unknown until gathered; the per-tick re-resolve re-attributes once the items are held) or when
    * narrowing would change nothing. NOTE: when ONE slot's demand is attributed to MULTIPLE species,
    * execution's first-matching removal may still skew between them (inventory slot order); that skew
    * is bounded and self-corrects on the next per-tick re-resolve.
    */
   private static Item[] narrowedSlotMatches(SlotDemand slot, java.util.Map<Item, Integer> consumed) {
      int total = 0;
      for (int n : consumed.values()) {
         total += n;
      }
      if (total < slot.demand) {
         return null; // external residue -> keep wide; re-derived next pass once gathered
      }
      List<Item> kept = new ArrayList<>();
      for (Item it : slot.matchSet) {
         if (consumed.getOrDefault(it, 0) > 0) {
            kept.add(it);
         }
      }
      if (kept.isEmpty() || kept.size() == slot.matchSet.length) {
         return null;
      }
      return kept.toArray(new Item[0]);
   }

   /** Aggregated per-craft demand for one distinct ingredient slot of a recipe. */
   private static final class SlotDemand {
      final Item[] matchSet;
      final SlotKind kind;
      int demand;

      SlotDemand(Item[] matchSet, SlotKind kind, int demand) {
         this.matchSet = matchSet;
         this.kind = kind;
         this.demand = demand;
      }
   }

   /** Stable signature for a slot's accepted-item set, used to aggregate identical ingredient slots. */
   private static String slotSignature(Item[] matchSet) {
      StringBuilder sb = new StringBuilder();
      for (Item it : matchSet) {
         sb.append(it.getDescriptionId()).append('|');
      }
      return sb.toString();
   }

   /**
    * Satisfy {@code demand} units of an "any-of" pooled slot. Counts free held stock across the whole
    * accepted set, then nets the shortfall against the per-pass {@code surplus} ledger (planned
    * rounding overshoot recorded by earlier steps of this pass — the sign's stick drawing the plank
    * step's leftovers); for the remaining shortfall, an ALL-CRAFTABLE pool runs the WIDE CREDIT LOOP — convert
    * free held raw inputs of EVERY pool variant (the {@code min()} rule: convert only what demand needs)
    * before any external is emitted — while a pool containing a raw (recipe-less) variant routes the
    * residual to an external acquisition. The credit loop makes the emitted external's count net of every
    * creditable held item of every species, so dispatch membership ⊆ credit membership (the
    * anti-escalation coherence invariant): gaining ANY item the dispatched gather counts strictly lowers
    * the owed net, and "picking up wood increases the debt" is structurally impossible.
    *
    * <p><b>Predicted-held pooled step thresholds ({@code plannedDelta} + {@link #pooledThreshold}).</b>
    * Multiple steps can feed ONE pooled output set within a single pass (several plank species toward
    * one chest, plus a crafting table's planks in the same {@code resolveBudget} pass), and the
    * executor's satisfaction gate counts the WHOLE pool RAW ({@code getItemCount(outputMatches) >=
    * targetCount}) — including held stock other slots reserved under OVERLAPPING match-set keys (a
    * sign's locked [oak_planks] vs the table's any-planks slot). The rule, stated once and exactly: an
    * emitted pooled step's {@code recipeTarget.targetCount} is the PREDICTED raw held pool count just
    * after its production lands — current raw held stock over the accepted set, plus the per-ITEM
    * planned production-minus-consumption ledger ({@code plannedDelta}, written only at step emission,
    * created fresh per pass and threaded like {@code reserved}), plus the step's own produced units
    * {@code u = craftsNeeded * yield}. Keying by ITEM instead of by slot signature is what makes
    * overlapping keys exact: the sign chain's planned 6 surviving oak planks raise the table's
    * any-planks threshold, so the table step can no longer instant-satisfy against the sign's reserve
    * (the cross-pool-key blindness bug).
    *
    * <p>Worked example 1 (one resolveBudget pass; held 10 planks; chest demands 8, table demands 4,
    * plank yield 4): chest slot pooledFree=10 >= 8 -> drain 8, no step. Table slot pooledFree=2 &lt; 4
    * -> drain 2; shortfall 2 -> one craft, u=4 -> step targetCount = heldRaw 10 + delta 0 + 4 = 14
    * (the chest is a table-bound FINAL craft, so its plank consumption is deliberately NOT ledgered —
    * it executes after every inventory gate). Before the craft the pool holds 10 (&lt; 14, step open);
    * after it lands the pool holds 14 (>= 14, satisfied) — exact.
    *
    * <p>Worked example 2 (the credit loop's min() rule; held oak_log 2 + acacia_planks 4; chest demands
    * 8, chest-only requirement set): pooledFree=4 -> drain 4, residual 4. Credit loop: oak convertible
    * = 2 crafts, craftsForV = min(2, ceilDiv(4,4)=1) = 1, u=4 -> step targetCount = heldRaw 4 + delta 0
    * + 4 = 8; residual 0 — NO external, ONE oak log converted, one legitimately left held.
    *
    * <p>Worked example 3 (overlapping keys, the sign scenario; held oak_log 2 + birch_log 1, sign
    * demands 6 locked oak_planks + 1 stick, table demands 4 any-planks): the sign's plank step ledgers
    * oak_planks +8, the stick step ledgers oak_planks -2 (its 2-plank slot drew the plank step's
    * surplus), so the table's any-planks step targetCount = heldRaw 0 + delta(+6) + u 4 = 10. At
    * execution the gate reads the sign's 6 surviving oak planks (&lt; 10, step OPEN), the birch log is
    * converted, 10 >= 10 closes — previously the step instant-satisfied at 6 and the table craft ate
    * the sign's reserve.
    */
   private ResolverResult.Status resolvePooledSlot(
         PlayerEngineController controller,
         RecipeManager mgr,
         RegistryAccess registries,
         Item[] accepted,
         Item[] outputPool,
         int demand,
         List<CraftMacroStep> steps,
         List<ItemTarget> external,
         List<Integer> externalCredited,
         List<String> failures,
         java.util.Map<Item, Integer> reserved,
         java.util.Map<Item, Integer> plannedDelta,
         java.util.Map<Item, Integer> surplus,
         java.util.Map<Item, Integer> slotConsumed,
         List<String> passDiag,
         boolean agnosticAncestry,
         int depth) {

      String poolName = poolLabel(accepted);

      // Free held stock pooled across the accepted set.
      int pooledFree = 0;
      for (Item it : accepted) {
         pooledFree += freeCount(controller, reserved, it);
      }
      int shortfall = demand - pooledFree;
      if (shortfall <= 0) {
         // Reserve from the pool, draining held variants in order (per-item takes recorded as this
         // slot's consumption attribution). Sits BEFORE the hoisted partial drain below, so the
         // satisfied-via-held path never double-drains.
         drainPooledReservation(controller, reserved, accepted, demand, slotConsumed);
         passDiag.add("poolDrain pool=" + poolName + " drained=" + demand);
         return ResolverResult.Status.NEEDS_WORK;
      }

      // DOUBLE-COUNT GUARD: the WHOLE of `pooledFree` is credited toward this slot's `demand` (only the
      // residual `shortfall` is left to produce), so that held stock must be RESERVED (drained) before
      // any recursion produces the shortfall — otherwise resolveInto(<variant>, need) re-reads the SAME
      // held stock as "free" in its satisfy-from-inventory early-out, counts it a SECOND time against
      // the shortfall, and emits NO craft step. (Observed: chest #planks demand=8 with spruce_planks=4
      // + jungle_log=1 -> 4 credited to the slot, then resolveInto(spruce_planks, need=4) saw free=4,
      // returned satisfied with no craft, so 4 planks counted as 8 and the held log was never
      // converted.) The drain of EXACTLY `pooledFree` (never `demand`, which would over-reserve) is
      // HOISTED here ONCE for every shortfall>0 path — depth-guard, raw-variant, and all-craftable
      // branches all start from this single drained state (per-item takes recorded as this slot's
      // consumption attribution). The old read-undrained-pool-then-drain ordering constraint (the
      // 2026-06-08 select-before-drain fix) is preserved by construction: held pool OUTPUTS are
      // credited directly via pooledFree (no representative selection reads the pool any more), and
      // the credit loop reads only RAW-INPUT freeCounts, which draining the pool never touches.
      drainPooledReservation(controller, reserved, accepted, pooledFree, slotConsumed);
      if (pooledFree > 0) {
         passDiag.add("poolDrain pool=" + poolName + " drained=" + pooledFree);
      }

      // SURPLUS DRAW: net this slot's shortfall against PLANNED rounding overshoot already recorded by
      // earlier steps of this same pass (e.g. the sign's 2-plank stick input drawing the plank step's
      // 2 leftover planks). Draws only ATTRIBUTION (the parent craft will consume the drawn planned
      // items — recorded into slotConsumed so the emission ledgers it), never a dispatch — the planned
      // items don't exist yet, so nothing is reserved. NOTE: the draw sees only surplus recorded by
      // slots resolved BEFORE this one (recipe ingredient order, row-major); a hypothetical
      // consumer-before-producer recipe would miss the draw this pass and over-emit one craft's worth —
      // the per-tick re-resolve cancels it next pass once items are held (bounded, coherent).
      int drawn = 0;
      for (Item v : accepted) {
         if (drawn >= shortfall) {
            break;
         }
         int avail = surplus.getOrDefault(v, 0);
         int take = Math.min(avail, shortfall - drawn);
         if (take > 0) {
            surplus.put(v, avail - take);
            slotConsumed.merge(v, take, Integer::sum);
            drawn += take;
         }
      }
      if (drawn > 0) {
         passDiag.add("surplusDraw pool=" + poolName + " drawn=" + drawn);
         shortfall -= drawn;
         if (shortfall <= 0) {
            // Satisfied via surplus — AFTER the hoisted drain, never through the demand-draining
            // early-out above (which would drain `demand` a second time).
            return ResolverResult.Status.NEEDS_WORK;
         }
      }

      if (depth >= MAX_DEPTH) {
         // pooledFree already drained/credited by the hoist; route the surplus-netted residual externally.
         addExternal(controller, external, externalCredited,
               accepted, externalNameForPool(accepted, agnosticAncestry), shortfall);
         return ResolverResult.Status.NEEDS_WORK;
      }

      // Prefer acquiring a RAW variant over crafting a craftable sibling. If any accepted variant has no
      // result-specific recipe (it is a raw material -- e.g. oak_log in a #minecraft:oak_logs slot), route
      // the shortfall to external acquisition. Crafting a sibling form of a raw material -- e.g. crafting
      // oak_wood (4 logs -> 3 wood) just to feed a logs slot -- is never simpler than gathering the raw
      // log, and previously made the resolver emit a nonsensical oak_wood craft step that wedged the macro
      // (CRAFT_2X2_INTERMEDIATES "Crafting oak_wood" forever). NOTE: a raw pool never flips agnostic
      // ancestry — under a variant-locked descent (ancestry=false) the external is named NARROW by the
      // pool's raw NATURAL variant; under an agnostic descent (ancestry=true, e.g. below #planks) it
      // keeps the WIDE pool naming. The count is netted over the slot's own accepted set either way.
      // (Presence-only probe: selectRecipeForResult is deliberately kept here — it answers "does ANY
      // recipe produce this item" identically to recipesForResult.isEmpty(), and no arithmetic is
      // driven by WHICH recipe it returns.)
      for (Item it : accepted) {
         if (recipes.selectRecipeForResult(mgr, it, registries).isEmpty()) {
            // pooledFree already drained/credited by the hoist; route the surplus-netted residual externally.
            addExternal(controller, external, externalCredited,
                  accepted, externalNameForPool(accepted, agnosticAncestry), shortfall);
            return ResolverResult.Status.NEEDS_WORK;
         }
      }

      // All accepted variants are craftable (e.g. a #minecraft:planks slot, where every plank is itself
      // crafted from logs). AGNOSTIC ANCESTRY flips TRUE for this slot's descent ONLY when the slot is
      // a genuinely multi-variant agnostic pool (outputPool != null, e.g. a chest's #planks): there any
      // raw external below keeps the wide pool naming, and the WIDE CREDIT LOOP guarantees no
      // convertible held stock of ANY species is left uncredited before such an external is emitted. A
      // single-variant LOCKED slot (a sign's [oak_planks], outputPool == null) keeps the inherited
      // ancestry, so its raw descent stays NARROW ("oak_log", not "log") — dispatch matches remain a
      // subset of the credit membership and off-family wood can never be gathered against the debt.
      //
      // The credit loop replaces the old single-representative pick (rules (a)/(b)/(c)): held pool
      // OUTPUTS are already credited via pooledFree (hoisted above); held raw INPUTS of every variant
      // are converted held-inputs-first (a variant with no free held inputs has convertible == 0 and is
      // skipped, so accepted/tag order among funded variants is deterministic); rule (c) survives as
      // the residual path. THE rule: craftsForV = min(convertible, ceilDiv(residual, yield)) — convert
      // only what demand needs ("materials on demand, not prediction of demand").
      //
      // Invariant: a credit-loop recursion must not emit an external — `convertible` is computed with
      // the SAME freeCount/reserved reads the recursion then performs, AGAINST THE SAME funded recipe
      // selectFundedRecipe returns under the same reserved state, so it is a conservative floor on what
      // the recursion can actually fund. (If a future recipe shape ever makes the recursion emit an
      // external anyway, that external is still membership-coherent, so the system degrades safely.)
      int residual = shortfall;
      boolean descentAncestry = agnosticAncestry || outputPool != null;
      ResolverResult.Status worst = ResolverResult.Status.NEEDS_WORK;
      for (Item variant : accepted) {
         if (residual <= 0) {
            break;
         }
         Optional<CraftingRecipe> variantRecipe =
               selectFundedRecipe(controller, reserved, mgr, registries, variant, residual, passDiag);
         if (variantRecipe.isEmpty()) {
            continue; // unreachable in this branch (all craftable); defensive
         }
         int yield = Math.max(1, recipes.outputCountOf(variantRecipe.get(), registries));
         int convertible = convertibleCrafts(controller, reserved, variantRecipe.get());
         int craftsForV = Math.min(convertible, ceilDiv(residual, yield));
         if (craftsForV <= 0) {
            continue;
         }
         int u = craftsForV * yield;
         // Predicted-held threshold, computed UNCONDITIONALLY (LOCKED single-item pools too): heldRaw
         // + plannedDelta covers prior drains, prior same-pool steps AND overlapping-key plans, so a
         // credit-loop step followed by a residual step on the SAME pool — or a step on an OVERLAPPING
         // pool — can never under-threshold (instant-satisfied churn). outputPool itself stays null
         // for LOCKED slots — strict single-output counting at the executor gate is unchanged.
         int threshold = pooledThreshold(controller, plannedDelta, accepted, u, poolName, passDiag);
         // need = min(residual, u): when craftsForV came from ceilDiv (rounding overshoot exists) the
         // inner resolveInto sees the true residual and records the overshoot at the single
         // surplus-fill site; when convertible-limited, u <= residual and overshoot is 0.
         int consumedHere = Math.min(residual, u);
         ResolverResult.Status sub = resolveInto(
               controller, mgr, registries, variant, consumedHere, outputPool, threshold,
               steps, external, externalCredited, failures, reserved, plannedDelta, surplus, passDiag,
               descentAncestry, depth);
         if (sub == ResolverResult.Status.UNOBTAINABLE) {
            worst = ResolverResult.Status.UNOBTAINABLE;
         }
         // The parent craft consumes the demand-allocated portion of this variant's planned production.
         slotConsumed.merge(variant, consumedHere, Integer::sum);
         residual -= u;
      }
      if (residual <= 0) {
         return worst;
      }

      // Rule (c) residual: recurse on the FIRST producible variant for what held inputs could not fund.
      // The residual step's SPECIES IS PROVISIONAL — a step emitted alongside its own input external can
      // never execute before that external is satisfied (COLLECT runs while externals are non-empty), and
      // the per-tick re-resolve re-derives the step from whatever species was actually gathered: the
      // credit loop names the right variant the moment its inputs are held. Its raw external (emitted
      // by the recursion's input slot; wide pool naming only under a multi-variant agnostic descent)
      // is, by construction, net of every creditable held item of every species.
      Item producible = null;
      for (Item it : accepted) {
         // Presence-only probe (selectRecipeForResult deliberately kept: same emptiness answer as
         // recipesForResult; no arithmetic reads WHICH recipe it returns).
         if (recipes.selectRecipeForResult(mgr, it, registries).isPresent()) {
            producible = it;
            break;
         }
      }
      if (producible != null) {
         Optional<CraftingRecipe> producibleRecipe =
               selectFundedRecipe(controller, reserved, mgr, registries, producible, residual, passDiag);
         int yield = Math.max(1, recipes.outputCountOf(producibleRecipe.get(), registries));
         int u = ceilDiv(residual, yield) * yield;
         // Unconditional predicted-held threshold: same LOCKED-pool symmetry rationale as the credit loop.
         int threshold = pooledThreshold(controller, plannedDelta, accepted, u, poolName, passDiag);
         ResolverResult.Status sub = resolveInto(
               controller, mgr, registries, producible, residual, outputPool, threshold,
               steps, external, externalCredited, failures, reserved, plannedDelta, surplus, passDiag,
               descentAncestry, depth);
         if (sub == ResolverResult.Status.UNOBTAINABLE) {
            worst = ResolverResult.Status.UNOBTAINABLE;
         }
         // The parent craft consumes `residual` of the provisional variant's planned production. The
         // species is provisional (see above), so the attribution is too — the per-tick re-resolve
         // re-derives both from whatever was actually gathered.
         slotConsumed.merge(producible, residual, Integer::sum);
         return worst;
      }

      // Defensive (unreachable in the all-craftable branch): no producible variant -> acquire externally.
      // pooledFree was already drained/credited above.
      addExternal(controller, external, externalCredited,
            accepted, externalNameForPool(accepted, agnosticAncestry), residual);
      return worst;
   }

   /**
    * FUNDING-AWARE recipe selection for {@code want}: among ALL recipes producing it (registry order),
    * pick the best <b>tier-0</b> candidate — one whose currently-free held raw inputs FULLY fund the
    * current {@code shortfall} ({@code convertibleCrafts >= craftsNeeded}; partial funding is
    * deliberately NOT a tie-break) — else the best candidate overall (tier 1). Within a tier the
    * ordering is: fits a 2x2 grid first (preserves the historical no-table preference), then HIGHER
    * yield (fewest crafts; a planks->4 stick recipe beats a bamboo->1 recipe), then registry order.
    * Deterministic: a bamboo stick recipe can only ever win when held bamboo fully funds the shortfall.
    *
    * <p>Empty iff NO recipe produces {@code want} — interchangeable with
    * {@code selectRecipeForResult.isEmpty()}, so callers' raw-material branches are unaffected.
    * Coherence: the credit loop's {@code convertibleCrafts} floor and the inner {@code resolveInto}
    * re-selection both evaluate this same funded choice under the same {@code reserved} state.
    */
   private Optional<CraftingRecipe> selectFundedRecipe(
         PlayerEngineController controller,
         java.util.Map<Item, Integer> reserved,
         RecipeManager mgr,
         RegistryAccess registries,
         Item want,
         int shortfall,
         List<String> passDiag) {
      List<CraftingRecipe> candidates = recipes.recipesForResult(mgr, want, registries);
      if (candidates.isEmpty()) {
         return Optional.empty();
      }
      CraftingRecipe bestT0 = null;
      boolean bestT0Fits = false;
      int bestT0Yield = 0;
      CraftingRecipe bestAny = null;
      boolean bestAnyFits = false;
      int bestAnyYield = 0;
      int chosenFundable = 0;
      int chosenCraftsNeeded = 0;
      for (CraftingRecipe candidate : candidates) {
         int yield = Math.max(1, recipes.outputCountOf(candidate, registries));
         int craftsNeeded = ceilDiv(Math.max(1, shortfall), yield);
         int fundable = convertibleCrafts(controller, reserved, candidate);
         boolean fits = candidate.canCraftInDimensions(2, 2);
         boolean tier0 = fundable >= craftsNeeded;
         // Within-tier ordering: 2x2-fitting first, then higher yield; ties keep the earlier
         // (registry-order) incumbent.
         if (tier0 && betterWithinTier(fits, yield, bestT0 != null, bestT0Fits, bestT0Yield)) {
            bestT0 = candidate;
            bestT0Fits = fits;
            bestT0Yield = yield;
            chosenFundable = fundable;
            chosenCraftsNeeded = craftsNeeded;
         }
         if (betterWithinTier(fits, yield, bestAny != null, bestAnyFits, bestAnyYield)) {
            bestAny = candidate;
            bestAnyFits = fits;
            bestAnyYield = yield;
            if (bestT0 == null) {
               chosenFundable = fundable;
               chosenCraftsNeeded = craftsNeeded;
            }
         }
      }
      CraftingRecipe chosen = (bestT0 != null) ? bestT0 : bestAny;
      if (candidates.size() > 1) {
         // Multi-recipe outputs (stick) log WHY a recipe won; single-recipe outputs stay quiet.
         passDiag.add("recipeSelect output=" + itemName(want) + " shortfall=" + shortfall
               + " candidates=" + candidates.size()
               + " tier=" + ((bestT0 != null) ? 0 : 1)
               + " yield=" + ((bestT0 != null) ? bestT0Yield : bestAnyYield)
               + " fundable=" + chosenFundable + " craftsNeeded=" + chosenCraftsNeeded);
      }
      return Optional.of(chosen);
   }

   /** Within-tier candidate ordering for {@link #selectFundedRecipe}: 2x2 first, then higher yield. */
   private static boolean betterWithinTier(
         boolean fits, int yield, boolean hasIncumbent, boolean incumbentFits, int incumbentYield) {
      if (!hasIncumbent) {
         return true;
      }
      if (fits != incumbentFits) {
         return fits;
      }
      return yield > incumbentYield;
   }

   /**
    * Max crafts of {@code recipe} fundable from currently-FREE held raw inputs, reservation-aware via
    * {@code freeCount}: for a multi-slot recipe, the min over distinct non-empty ingredient slots of
    * {@code floor(pooledSlotFree / perCraftCount)}. This is the credit loop's conservative floor on what
    * a recursion into this recipe can satisfy from inventory without emitting an external.
    */
   private int convertibleCrafts(
         PlayerEngineController controller, java.util.Map<Item, Integer> reserved, CraftingRecipe recipe) {
      java.util.LinkedHashMap<String, Item[]> slotSets = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, Integer> slotCounts = new java.util.LinkedHashMap<>();
      for (Ingredient ing : recipe.getIngredients()) {
         if (ing == null || ing.isEmpty()) {
            continue;
         }
         Item[] matchSet = toItemArray(inspector.acceptedItems(ing));
         if (matchSet.length == 0) {
            return 0; // an unfundable slot (unbound tag) -> nothing convertible
         }
         String sig = slotSignature(matchSet);
         slotSets.putIfAbsent(sig, matchSet);
         slotCounts.merge(sig, 1, Integer::sum);
      }
      if (slotSets.isEmpty()) {
         return 0;
      }
      int convertible = Integer.MAX_VALUE;
      for (java.util.Map.Entry<String, Item[]> e : slotSets.entrySet()) {
         int free = 0;
         for (Item it : e.getValue()) {
            free += freeCount(controller, reserved, it);
         }
         convertible = Math.min(convertible, free / slotCounts.get(e.getKey()));
      }
      return convertible;
   }

   /**
    * Emission threshold for a pooled step: the PREDICTED raw held count over the slot's accepted set
    * at the moment just after this step's planned production lands — current RAW held stock (the
    * executor gate counts ALL held pool members, reserved or not, across every requirement) plus the
    * per-item planned production-minus-consumption delta of every earlier-EXECUTING step, plus this
    * step's own produced units {@code u}. Counting raw held + per-ITEM deltas is what makes thresholds
    * exact across OVERLAPPING pool keys (the cross-pool-key blindness fix): held or planned stock
    * another slot reserved under a different match set (the sign's locked [oak_planks] vs the table's
    * any-planks slot) is counted by the gate, so it is counted here too. Clamped to at least {@code u}
    * so attribution gaps (external-fed consumption is never ledgered) can never close a step before
    * its own production lands.
    */
   private static int pooledThreshold(
         PlayerEngineController controller, java.util.Map<Item, Integer> plannedDelta,
         Item[] accepted, int u, String poolName, List<String> passDiag) {
      int held = 0;
      int delta = 0;
      for (Item it : accepted) {
         held += controller.getItemStorage().getItemCount(it);
         delta += plannedDelta.getOrDefault(it, 0);
      }
      int threshold = u + Math.max(0, held + delta);
      passDiag.add("poolThreshold pool=" + poolName + " heldRaw=" + held + " delta=" + delta
            + " u=" + u + " -> " + threshold);
      return threshold;
   }

   // -- helpers ------------------------------------------------------------------------------------

   private static int freeCount(PlayerEngineController controller, java.util.Map<Item, Integer> reserved, Item item) {
      int held = controller.getItemStorage().getItemCount(item);
      int taken = reserved.getOrDefault(item, 0);
      return Math.max(0, held - taken);
   }

   private static void reserve(java.util.Map<Item, Integer> reserved, Item item, int amount) {
      if (amount > 0) {
         reserved.merge(item, amount, Integer::sum);
      }
   }

   /**
    * Reserve up to {@code amount} units of free held stock across a pooled accepted set, draining held
    * variants in {@code accepted} order. Shared by {@link #resolvePooledSlot}'s two satisfaction branches
    * so the held stock a pooled slot credits toward its demand is earmarked identically whether the slot
    * is fully satisfied from inventory ({@code amount == demand}) or only partially, with the residual
    * recursed ({@code amount == pooledFree}). Earmarking the partial-satisfaction case is what prevents
    * the recursive producing craft from re-counting (double-counting) the same held stock as free.
    * Each per-item take is also recorded into {@code consumedOut} — the slot's consumption attribution
    * (the parent craft will consume exactly this drained stock), feeding the planned-delta ledger and
    * the grid narrowing at step emission.
    */
   private static void drainPooledReservation(
         PlayerEngineController controller, java.util.Map<Item, Integer> reserved, Item[] accepted,
         int amount, java.util.Map<Item, Integer> consumedOut) {
      int toReserve = amount;
      for (Item it : accepted) {
         if (toReserve <= 0) {
            break;
         }
         int avail = freeCount(controller, reserved, it);
         int take = Math.min(avail, toReserve);
         if (take > 0) {
            reserve(reserved, it, take);
            consumedOut.merge(it, take, Integer::sum);
            toReserve -= take;
         }
      }
   }

   private static int ceilDiv(int a, int b) {
      return (a + b - 1) / b;
   }

   private static Item[] toItemArray(Set<Item> items) {
      // Stable, de-duplicated ordering; skip nulls (bamboo edge: never iterate a null family field).
      Set<Item> ordered = new LinkedHashSet<>();
      for (Item it : items) {
         if (it != null) {
            ordered.add(it);
         }
      }
      return ordered.toArray(new Item[0]);
   }

   private static RecipeTarget toRecipeTarget(
         Item output, int targetCount, int yield, List<ItemTarget> slotTargets, CraftingRecipe recipe) {
      // The mod's CraftingInventoryOps consumes via getRecipe().getSlots()+ItemTarget.matches(); build a
      // mod-wrapper CraftingRecipe sized to a 4- or 9-slot grid. A 2x2-fitting recipe uses the 2x2 grid.
      ItemTarget[] grid;
      if (recipe.canCraftInDimensions(2, 2) && slotTargets.size() <= 4) {
         grid = padTo(slotTargets, 4);
      } else {
         grid = padTo(slotTargets, 9);
      }
      // The wrapper recipe's outputCount is the per-craft YIELD (used by performCrafts/performSingleCraft
      // to size the result stack). The RecipeTarget.targetCount is the satisfaction DEMAND (read only by
      // inventoryStepSatisfied). These are deliberately decoupled: yield != demand.
      com.player2.playerengine.util.CraftingRecipe wrapper =
            com.player2.playerengine.util.CraftingRecipe.newShapedRecipe(grid, yield);
      return new RecipeTarget(output, targetCount, wrapper);
   }

   private static ItemTarget[] padTo(List<ItemTarget> slots, int size) {
      ItemTarget[] out = new ItemTarget[size];
      for (int i = 0; i < size; i++) {
         out[i] = (i < slots.size() && slots.get(i) != null) ? slots.get(i) : ItemTarget.EMPTY;
      }
      return out;
   }

   private static CraftMacroStepKind stepKindFor(Item want, CraftingRecipe recipe) {
      if (isPlank(want)) {
         return CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY;
      }
      if (want == net.minecraft.world.item.Items.STICK) {
         return CraftMacroStepKind.CRAFT_STICKS_IN_INVENTORY;
      }
      if (want == net.minecraft.world.item.Items.CRAFTING_TABLE) {
         return CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY;
      }
      // Anything else (chest, sign, ...) is the table-bound output craft if it does not fit a 2x2 grid;
      // a 2x2-fitting non-plank output (e.g. crafting_table handled above) stays an inventory craft.
      return recipe.canCraftInDimensions(2, 2)
            ? CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY
            : CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE;
   }

   private static boolean isPlank(Item item) {
      for (Item p : ItemHelper.PLANKS) {
         if (p == item) {
            return true;
         }
      }
      return false;
   }

   /**
    * Named catalogue acquisition target for a single raw item. Under AGNOSTIC ancestry a log routes to
    * the wide named "log" catalogue entry (also avoiding the self-recursion stack overflow documented at
    * {@code CraftMacroPlanner.java:90-97}); under a variant-locked descent it uses the item's
    * catalogue-probed narrow name (see {@link #narrowCatalogueName}: registry name first, then the
    * "_stem"→"_log" woodTasks form for nether woods), so the dispatched gather counts exactly the items
    * the arithmetic credits (membership coherence). If neither probe hits a catalogue entry,
    * {@code TaskCatalogue.getItemTask} returns null on the registry name and the executor
    * bounded-terminates naming the undispatchable external — degraded but truthful.
    *
    * <p>Bamboo edge: a {@code bamboo_planks} craft inputs the {@code #minecraft:bamboo_blocks} tag,
    * which is NOT in {@link ItemHelper#LOG}, so {@link #isLog} is false and the raw input would route
    * to its own registry name. The tag includes {@code stripped_bamboo_block}, which has NO catalogue
    * gather entry (silent dead-end). Route ALL bamboo-block variants to the gatherable {@code
    * "bamboo_block"} catalogue entry (which crafts bamboo_block from mined bamboo) regardless of
    * ancestry. bamboo_block yields 2 planks per craft — the same ratio as a log — so the deficit
    * arithmetic is unchanged.
    */
   private static String externalNameFor(Item item, boolean agnosticAncestry) {
      if (isLog(item)) {
         return agnosticAncestry ? "log" : narrowCatalogueName(item);
      }
      if (isBambooBlock(item)) {
         return "bamboo_block";
      }
      return ItemHelper.trimItemName(item.getDescriptionId());
   }

   /**
    * Named catalogue acquisition target for a pooled accepted set. Under AGNOSTIC ancestry a log pool
    * routes to the wide "log" entry; under a variant-locked descent (e.g. the #oak_logs slot of an
    * explicit {@code get oak_planks}) it is named NARROW by the pool's raw NATURAL variant via
    * {@link #narrowCatalogueName} (registry name probed against the catalogue first, then the
    * "_stem"→"_log" woodTasks form — so a #crimson_stems pool names "crimson_log", not the
    * unregistered "crimson_stem"), whose catalogue matches are a subset of the slot's accepted set —
    * so held off-family logs are correctly irrelevant to BOTH the credit and the dispatch arithmetic.
    * A pool with no NATURAL member falls back to its first variant's catalogue-probed name; if neither
    * probe hits a catalogue entry the executor bounded-terminates naming the undispatchable external
    * (degraded but truthful).
    */
   private static String externalNameForPool(Item[] accepted, boolean agnosticAncestry) {
      boolean anyLog = false;
      for (Item it : accepted) {
         if (isLog(it)) {
            anyLog = true;
            break;
         }
      }
      if (anyLog) {
         if (agnosticAncestry) {
            return "log";
         }
         for (Item it : accepted) {
            if (isNaturalLog(it)) {
               return narrowCatalogueName(it);
            }
         }
         return narrowCatalogueName(accepted[0]);
      }
      // Bamboo blocks: a #minecraft:bamboo_blocks slot pools {bamboo_block, stripped_bamboo_block};
      // route the whole pool to the gatherable "bamboo_block" catalogue entry regardless of tag order
      // (stripped_bamboo_block alone has no gather task -> would silently dead-end the macro).
      for (Item it : accepted) {
         if (isBambooBlock(it)) {
            return "bamboo_block";
         }
      }
      return (accepted.length > 0) ? ItemHelper.trimItemName(accepted[0].getDescriptionId()) : "unknown";
   }

   /**
    * Catalogue-first probe for a NARROW (variant-locked) acquisition name. The registry-derived name
    * (e.g. "oak_log") is probed against the catalogue first; if absent and it ends in "_stem", the
    * "_stem" suffix is swapped for "_log" and re-probed — vanilla nether woods register as
    * {@code crimson_stem}/{@code warped_stem} but {@code TaskCatalogue.woodTasks} catalogues them as
    * {@code prefix + "_log"} ("crimson_log"/"warped_log", whose matches are exactly the family's stem
    * item, keeping dispatch membership ⊆ credit membership). If neither probe hits, the unprobed
    * registry name is returned so the executor's existing bounded terminate covers unknown/modded
    * families; deliberately NOT the wide "log" name, whose matches would be a SUPERSET of the slot's
    * accepted/credit set and reintroduce dispatch drift.
    */
   private static String narrowCatalogueName(Item item) {
      String candidate = ItemHelper.trimItemName(item.getDescriptionId());
      if (TaskCatalogue.taskExists(candidate)) {
         return candidate;
      }
      if (candidate.endsWith("_stem")) {
         String logForm = candidate.substring(0, candidate.length() - "_stem".length()) + "_log";
         if (TaskCatalogue.taskExists(logForm)) {
            return logForm;
         }
      }
      return candidate;
   }

   /**
    * Append an external acquisition plus its parallel creditedHeld diag value (held count over the
    * emission's CREDIT membership; see {@link #creditedHeldFor}).
    */
   private static void addExternal(
         PlayerEngineController controller,
         List<ItemTarget> external,
         List<Integer> externalCredited,
         Item[] creditSet,
         String name,
         int count) {
      external.add(new ItemTarget(name, count));
      externalCredited.add(creditedHeldFor(controller, creditSet, name));
   }

   /**
    * Held count over an emitted external's CREDIT membership — every held item the resolver's arithmetic
    * can credit against the demand whose remainder this external is. For the wide "log" external
    * (agnostic ancestry) the credit loop converts held stock of ANY species, including non-natural
    * wood/stripped variants, so the membership is the broad {@link ItemHelper#LOG} set; otherwise it is
    * the emitting slot's own accepted set. Dispatch membership ⊆ credit membership always (in diag:
    * {@code heldMatching <= creditedHeld}), so a gained dispatched item always lowers the owed net —
    * the anti-escalation coherence invariant made observable.
    */
   private static int creditedHeldFor(PlayerEngineController controller, Item[] creditSet, String externalName) {
      Item[] membership = "log".equals(externalName) ? ItemHelper.LOG : creditSet;
      int sum = 0;
      for (Item it : membership) {
         if (it != null) {
            sum += controller.getItemStorage().getItemCount(it);
         }
      }
      return sum;
   }

   /** Compact pool label for poolCredit/stepEmit diag lines ("log", "planks", else the first variant). */
   private static String poolLabel(Item[] accepted) {
      for (Item it : accepted) {
         if (isLog(it)) {
            return "log";
         }
      }
      boolean allPlanks = accepted.length > 0;
      for (Item it : accepted) {
         if (!isPlank(it)) {
            allPlanks = false;
            break;
         }
      }
      if (allPlanks) {
         return "planks";
      }
      return (accepted.length > 0) ? itemName(accepted[0]) : "unknown";
   }

   private static boolean isLog(Item item) {
      for (Item l : ItemHelper.LOG) {
         if (l == item) {
            return true;
         }
      }
      return false;
   }

   /** True for a NATURAL trunk log (the mineable {@link ItemHelper#NATURAL_LOG} set; excludes wood/stripped). */
   private static boolean isNaturalLog(Item item) {
      for (Item l : ItemHelper.NATURAL_LOG) {
         if (l == item) {
            return true;
         }
      }
      return false;
   }

   /**
    * True for the bamboo "log-equivalent" block variants that feed a {@code bamboo_planks} craft. These
    * are deliberately NOT added to {@link ItemHelper#LOG} (which would mis-classify bamboo as a log for
    * the mining / log-aware-variant-pick / "log" catalogue routing elsewhere); they are recognized only
    * here so the resolver's external acquisition routes a bamboo plank's raw input to the existing,
    * gatherable {@code "bamboo_block"} catalogue entry instead of an entryless registry name.
    */
   private static boolean isBambooBlock(Item item) {
      return item == net.minecraft.world.item.Items.BAMBOO_BLOCK
            || item == net.minecraft.world.item.Items.STRIPPED_BAMBOO_BLOCK;
   }

   private static String debugLabel(Item item) {
      return ItemHelper.trimItemName(item.getDescriptionId());
   }

   private static String itemName(Item item) {
      return ItemHelper.trimItemName(item.getDescriptionId());
   }

   // -- TEMPORARY DIAGNOSTIC (sentinel [[CRAFT-RESOLVER-DIAG]]) -------------------------------------
   // Logs the final resolved plan so the next single playtest is definitive. REMOVE after the craft
   // macro wedge fix is confirmed. See debug-instrumentation/craft-resolver-diag-2026-06-06.md.
   // The header carries the request shape ("resolve target=chest x1" for single-target resolves, or
   // "resolveBudget requirements=[chest x1, crafting_table x1]" for requirement-set passes); each
   // external line exposes net (owed) vs creditedHeld (held stock over the credit membership) so the
   // anti-escalation coherence invariant (heldMatching <= creditedHeld) is directly assertable in a log.
   private static void logResolvedPlan(
         PlayerEngineController controller, String header, ResolverResult.Status status,
         List<CraftMacroStep> steps, List<ItemTarget> external, List<Integer> externalCredited, int deficit) {
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] " + header
            + " status=" + status + " deficit=" + deficit
            + " steps=" + steps.size() + " externals=" + external.size());
      // Held wood inventory: counts of every log and plank variant the bot currently holds. Makes the next
      // playtest definitive about whether the chosen craft variant matches the wood actually on hand
      // (e.g. plan output=oak_planks while held logs=[spruce_log x2] => variant mismatch, the wedge cause).
      logHeldWood(controller, "logs", ItemHelper.LOG);
      logHeldWood(controller, "planks", ItemHelper.PLANKS);
      for (int i = 0; i < steps.size(); i++) {
         CraftMacroStep s = steps.get(i);
         String om;
         if (s.outputMatches() == null) {
            om = "null";
         } else {
            StringBuilder sb = new StringBuilder();
            for (Item it : s.outputMatches()) {
               sb.append(itemName(it)).append(',');
            }
            om = "[" + s.outputMatches().length + ":" + sb + "]";
         }
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]]   step#" + i + " kind=" + s.kind()
               + " output=" + itemName(s.recipeTarget().getOutputItem())
               + " threshold(targetCount)=" + s.recipeTarget().getTargetCount()
               + " yield=" + s.recipeTarget().getRecipe().outputCount()
               + " craftsNeeded=" + s.craftsNeeded()
               + " outputMatches=" + om);
      }
      for (int i = 0; i < external.size(); i++) {
         ItemTarget t = external.get(i);
         int credited = (i < externalCredited.size()) ? externalCredited.get(i) : 0;
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]]   external#" + i + " " + t
               + " net=" + t.getTargetCount() + " creditedHeld=" + credited);
      }
   }

   /**
    * Emit the per-pass poolCredit/stepEmit arithmetic lines, change-gated on the whole pass signature so
    * a steady-state resolve loop logs transitions instead of ~1000 identical lines (the diag-volume
    * lesson from the 2026-06-09 playtest).
    */
   private void emitArithmeticDiag(List<String> passDiag) {
      String sig = String.join(";", passDiag);
      if (sig.equals(this.lastArithmeticDiagSig)) {
         return;
      }
      this.lastArithmeticDiagSig = sig;
      for (String line : passDiag) {
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] " + line);
      }
   }

   /** Log the held count of each variant in {@code family} that the bot currently holds (>0 only). */
   private static void logHeldWood(PlayerEngineController controller, String label, Item[] family) {
      StringBuilder sb = new StringBuilder();
      for (Item it : family) {
         if (it == null) {
            continue;
         }
         int held = controller.getItemStorage().getItemCount(it);
         if (held > 0) {
            sb.append(itemName(it)).append('=').append(held).append(' ');
         }
      }
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]]   held " + label + ": "
            + (sb.length() == 0 ? "(none)" : sb.toString().trim()));
   }
}
