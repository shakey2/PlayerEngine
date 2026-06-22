package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.resolver.IngredientInspectorImpl;
import com.player2.playerengine.tasks.crafting.resolver.MaterialResolver;
import com.player2.playerengine.tasks.crafting.resolver.RecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.resolver.ResolverResult;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.tasks.movement.GetWithinRangeOfBlockTask;
import com.player2.playerengine.tasks.resources.MineAndCollectTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.helpers.LookHelper;
import com.player2.playerengine.util.helpers.MaterialAvailability;
import com.player2.playerengine.util.time.TimerGame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class CraftMacroResourceTask extends ResourceTask implements DescribesProgress {
   private CraftMacroPlan plan;
   private CraftMacroPhase phase = CraftMacroPhase.PLAN;
   private BlockPos craftingTablePos;
   private boolean tablePlacedByTask;
   /**
    * The {@link PlaceBlockNearbyTask} dispatched by {@link CraftMacroPhase#FIND_OR_PLACE_TABLE} to place a
    * crafting table. Held so the macro can read back the exact placed {@link BlockPos}
    * ({@link PlaceBlockNearbyTask#getPlaced()}) and seed {@link #craftingTablePos} directly, instead of
    * waiting for the world {@code BlockScanner} to re-index the just-placed block. Without this the
    * just-placed table is not recognized as reachable for several ticks and the FSM re-provisions / crafts a
    * SECOND table (the player-observed "made a second crafting table" / "couldn't find a chest").
    */
   private PlaceBlockNearbyTask pendingTablePlaceTask;
   private final TimerGame craftDelay = new TimerGame(0.5);
   private final TimerGame lookHold = new TimerGame(0.25);
   private boolean delayActive;
   private int inventoryStepIndex;
   private int tableCraftsRemaining;
   private String failureReason;

   /**
    * Deterministic material resolver (zero model calls). The macro consults it from LIVE inventory at
    * every decision point instead of trusting the seed plan's static targets, so mid-execution
    * inventory changes are always reflected and no stale target strands materials. Constructed once;
    * each {@code resolve} call recomputes from inventory (cheap), never a world block re-scan.
    */
   private final MaterialResolver resolver = new MaterialResolver(new IngredientInspectorImpl(), new RecipeAccessImpl(), new CookingRecipeAccessImpl());

   /**
    * Latest resolve result for the current tick, recomputed by {@link #resolveNow} as ONE
    * {@link MaterialResolver#resolveBudget} pass over {@link #requirements}. The single owed-truth:
    * every executor decision (COLLECT dispatch, phase entry/exit, guards, inventory steps) reads it.
    */
   private ResolverResult lastResult;

   /**
    * Ordered live requirement set: the output first, on-demand additions after (today: at most one
    * {@code (CRAFTING_TABLE, 1)} entry, added by FIND_OR_PLACE_TABLE exactly when the macro has
    * actually failed to find a usable table — never predicted up front). The ONLY persistent
    * provisioning state: reservations exist only inside each per-tick {@code resolveBudget} pass over
    * this list, so ADDING a requirement is what creates its reservations on the next pass and
    * REMOVING it (collapse) is what releases them — there is no reservation ledger that can drift
    * from live inventory, and all counts are re-derived live each decision.
    */
   private final List<MaterialResolver.BudgetTarget> requirements = new ArrayList<>();

   /** Times the table requirement has been added this task run (a re-add means the table was lost). */
   private int tableRequirementAdds = 0;
   private static final int MAX_TABLE_REQUIREMENT_ADDS = 3;

   // Dispatch pin (anti-escalation): ONE slot — the executor gathers one external at a time
   // (externals.get(0)); switching externals re-pins as NEW by key change, so expect one pin=NEW per
   // external episode. Persists across COLLECT re-entries; reset ONLY in onResourceStart and
   // implicitly via key change (requirementSetSig is part of the key) — never in enterCollectPhase,
   // which would re-arm both the drift clamp and the stall counters on every re-entry.
   private String dispatchPinKey;
   private int dispatchPinAbsolute;
   private int heldAtPin;

   /** Change-gate for the DISPATCH arithmetic diag line (log transitions, not identical repeats). */
   private String lastDispatchDiag = "";

   // -- WS3 convergence / bounded-failure guard ----------------------------------------------------
   // Track the TIERED remainingDeficit across resolves so a recompute loop provably terminates: a
   // productive intermediate craft strictly shrinks the deficit and resets the no-progress counter,
   // while a genuinely stuck state trips the cap and routes to terminateMacro (the single bounded
   // failure primitive — no new self-stop path).
   private int lastDeficit = Integer.MAX_VALUE;
   private int noProgressCount = 0;
   private int resolveAttempts = 0;
   private static final int MAX_NO_PROGRESS_RESOLVES = 3;
   private static final int MAX_RESOLVE_ATTEMPTS = 64; // backstop against deficit oscillation only

   // Collect-phase stall bound. COLLECT must raise the STRICT inventory count, not merely satisfy the
   // sufficiency axis (which counts reachable ground drops). If the held count of still-needed externals
   // does not rise for this many consecutive collect ticks, the material is effectively
   // uncollectable/unobtainable -> craft from what we have if possible, else terminate cleanly. Reset on
   // any inventory gain, so a normal (even slow) gather that periodically picks items up never trips it.
   private int collectStallTicks = 0;
   private int lastCollectHeld = -1;
   private static final int MAX_COLLECT_STALL_TICKS = 300; // ~15s at 20 TPS without ANY inventory gain

   // HARD held-flat backstop (defense-in-depth against a false "progressing" positive).
   // MAX_COLLECT_STALL_TICKS is held at 0 while reachableSourceForAnyExternal is true, so if a source is
   // scored "reachable" by the weak canReach/scanner probe but is in fact UNcollectable by baritone
   // (canBreak / MineProcess.plausibleToBreak: e.g. a scanned block inside a structure, no valid path),
   // the strict held count never rises yet collectStallTicks is pinned at 0 every tick -> the macro is
   // wedged in COLLECT forever (the live 1.20.1 hang: held=2 spruce_log, progressing=true, the run only
   // ended on player disconnect). Narrowing the "log" target to NATURAL_LOG removes the usual SOURCE of
   // that false positive (structure *_wood / hyphae / stripped blocks are no longer scan targets), but a
   // genuinely-reachable-by-scanner-yet-unminable natural block could still pin it. This counter increments
   // on every collect tick where the STRICT held count did NOT rise AND no matching drop is in flight,
   // INDEPENDENT of the reachability probe, so it cannot be reset by the false positive. It is bounded well
   // above any legitimate cross-chunk mine/travel gap (the agentic travel cap is ~96 blocks; pathing + a
   // chop is far under 60s) and is reset by an actual inventory gain or an in-flight drop, so a slow-but-
   // real gather never trips it. On trip the macro crafts from what it has if possible, else terminates
   // with a meaningful reason (surfaced to player + model per DESIGN.md S3).
   private int collectNoGainTicks = 0;
   private static final int MAX_COLLECT_NO_GAIN_TICKS = 1200; // ~60s with zero strict inventory gain

   // Issue A (scan-before-pickup race) pickup-settle gate. A freshly-broken material drop has pickup
   // delay (~10 ticks) + flight time before it enters inventory; the COLLECT decision reads the STRICT
   // held count, so a read in that window sees 0 and the bot re-targets a SECOND block before the first
   // drop lands ("read 0 before pickup"). When the SUFFICIENCY axis already credits the in-flight drop
   // (inventory + reachable drops >= need) but strict inventory is still short, we wait out this short
   // settle window instead of issuing a new gather, so the drop is collected before the next strict-count
   // decision. Reset whenever a new gather is issued. Reuses the same mineCollectSettleSeconds config and
   // TimerGame pattern as MineOrCollectTask. The MAX_COLLECT_STALL_TICKS backstop still bounds the phase,
   // so an UNCOLLECTABLE drop can never make this wait forever.
   private final TimerGame collectSettleTimer = new TimerGame(1.0);

   // Inventory-craft stall bound. A structurally UNSATISFIABLE first-open inventory step (e.g. the plan
   // wants a wood variant whose logs are not obtainable) keeps needsMoreInventorySteps(mod) permanently
   // true, which would otherwise keep craftOrGatherPending permanently true in resolveNow and suppress
   // the no-progress / attempt-cap termination forever (the ~47s chest wedge). Track how many consecutive
   // resolves the SAME first-open inventory step has stayed open while NOTHING was advancing it (no craft
   // delay running, no external acquisition in flight). Beyond this bound the step is treated as stuck and
   // is NO LONGER counted as "pending" by resolveNow, so the existing bounded-failure path engages with a
   // human-meaningful reason. Reset whenever the first-open step changes or its output count rises, so a
   // legitimately progressing multi-craft build (each craft satisfies a step and advances to the next)
   // never trips it. Bound chosen well above the worst per-craft craftDelay (~10 ticks) so a single slow
   // craft is never mistaken for a stall.
   private String invStallStepSig = null;
   private int invStallOutputHeld = -1;
   private int invStallTicks = 0;
   private static final int MAX_INV_STALL_TICKS = 60; // ~3s of a flat, unadvancing open inventory step

   // Failed-craft-ATTEMPT bound for a structurally unsatisfiable inventory step. The tick-based
   // invStall* counter above is reset to 0 on EVERY tick where delayActive is true; a craft-RETRY cycle
   // keeps delayActive true ~10 of every ~12 ticks, so a step whose craft can never land (e.g. a
   // variant-LOCKED sub-craft like oak_planks whose only obtainable wood is the WRONG variant -- the
   // generic "log" external collected spruce, satisfying the external + sufficiency axes, but the
   // inventory craft has no oak logs to consume) never trips the tick bound and the macro ping-pongs
   // CRAFT_2X2_INTERMEDIATES <-> beginIngredientRecovery indefinitely. This counts ATTEMPTS, not ticks:
   // each time a performCrafts for the first-open step returns 0 AND the step stays unsatisfied, with no
   // rise in that step's output count, the counter increments. Reset whenever the open step's output
   // count rises (a craft landed) or the open step changes (real progress). Past the bound the step is
   // judged genuinely unsatisfiable and the macro terminates bounded with a variant-naming reason.
   private String craftFailStepSig = null;
   private int craftFailOutputHeld = -1;
   private int craftFailAttempts = 0;
   private static final int MAX_CRAFT_FAIL_ATTEMPTS = 4; // failed crafts on the same flat, unsatisfiable step

   // Table-approach stall bound (DEFECT 4 fix). Counts consecutive MOVE_TO_TABLE ticks where the target
   // table is NOT yet within REACH (the bot is trying to walk to / path to a table but has not arrived).
   // Reset to 0 the moment the table becomes reachable (a successful approach) so a normal, even slow,
   // walk-to-table never trips it. Once it exceeds the bound the table is effectively unreachable (e.g. an
   // elevated player-placed table with no path, or baritone wandering), and tableCraftInProgress() stops
   // suppressing the no-progress / attempt-cap termination so the macro bounded-fails with a meaningful
   // reason instead of looping MOVE_TO_TABLE<->CRAFT_3X3 forever (the player's save-and-quit-only exit).
   // Bound chosen well above a worst-case cross-base path (~15s) so a legitimate long approach is safe.
   private int tableApproachStallTicks = 0;
   private static final int MAX_TABLE_APPROACH_STALL_TICKS = 300; // ~15s of failing to reach the table

   // TABLE-REUSE (Issue #2): true while craftingTablePos points at a PRE-EXISTING table this macro adopted
   // to walk to (within getCraftingTableReuseRadius, pathable) rather than one it placed itself. Used by
   // the MOVE_TO_TABLE approach-stall guard: if an adopted FOREIGN table proves unreachable within the
   // approach bound, the macro must NOT terminate — it falls back to placing/crafting its own table (the
   // deterministic path), exactly as it would have if no nearby table had been found. Reset whenever we
   // place our own table, adopt a freshly placed one, or clear the table pin.
   private boolean adoptedForeignTable;

   /**
    * SPECIES PIN: the concrete output item the FIRST plan resolved (a bare multi-match "sign" request
    * is resolved to ONE species by {@code CraftMacroSupport.pickSignSpecies}). Every {@link #replan}
    * re-plans against THIS pinned item, never the original multi-match target, so a mid-macro inventory
    * change (the wide table-log gather landing off-species wood, a player handing the bot planks) can
    * never re-roll the species pick and re-target the variant-locked chain mid-run — which would strand
    * the already-reserved planks. A fresh command creates a fresh task and a fresh pick; the bounded
    * UNOBTAINABLE terminate ends THIS task, which is exactly the "re-pick only once the pinned species
    * proves unobtainable" recovery.
    */
   private final Item pinnedOutputItem;

   public CraftMacroResourceTask(CraftMacroPlan initialPlan) {
      this(initialPlan, null);
   }

   /**
    * WS4: construct with the chain-scoped reservation ledger so the resolver subtracts later agentic
    * steps' pre-seeded reservations as an additive floor in {@code freeCount}. {@code ledger} is
    * {@code null} for standalone crafts (no agentic run), in which case the resolver behaves exactly as
    * before the ledger existed. The ledger is consulted only as a floor; the resolver's per-pass rebuild
    * and slot-narrowing are untouched.
    */
   public CraftMacroResourceTask(CraftMacroPlan initialPlan,
         @org.jetbrains.annotations.Nullable com.player2.playerengine.agentic.MaterialReservationService ledger) {
      super(initialPlan.requestedOutput());
      this.plan = initialPlan;
      this.pinnedOutputItem = initialPlan.outputItem();
      this.resolver.setLedger(ledger);
   }

   @Override
   public boolean isFinished() {
      return this.controller != null
         && this.phase == CraftMacroPhase.DONE
         && this.controller.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount();
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController mod) {
      this.phase = CraftMacroPhase.PLAN;
      this.tableApproachStallTicks = 0;
      this.adoptedForeignTable = false;
      this.replan(mod);
      // Seed the requirement set OUTPUT-ONLY (materials on demand, not prediction of demand): the
      // crafting-table requirement is added at FIND_OR_PLACE_TABLE exactly when the macro actually
      // fails to find a usable table, and nowhere else.
      this.requirements.clear();
      this.requirements.add(new MaterialResolver.BudgetTarget(this.plan.outputItem(), this.plan.targetCount()));
      this.tableRequirementAdds = 0;
      this.dispatchPinKey = null;
      this.dispatchPinAbsolute = 0;
      this.heldAtPin = 0;
      this.lastDispatchDiag = "";
   }

   @Override
   protected Task onResourceTick(PlayerEngineController mod) {
      if (this.failureReason != null) {
         this.setDebugState(this.failureReason);
         return null;
      }

      if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
         this.phase = CraftMacroPhase.DONE;
         return null;
      }

      switch (this.phase) {
         case PLAN -> {
            this.replan(mod);
            if (this.resolveNow(mod)) {
               return null;
            }
            if (this.lastResult.status() == ResolverResult.Status.READY) {
               this.phase = CraftMacroPhase.DONE;
               return null;
            }
            if (this.resolvedExternals().isEmpty()) {
               this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
               this.inventoryStepIndex = 0;
            } else {
               this.enterCollectPhase(mod);
            }
         }
         case COLLECT_MISSING_MATERIALS -> {
            Task collect = this.tickCollectMissingMaterials(mod);
            if (collect != null) {
               return collect;
            }
         }
         case CRAFT_2X2_INTERMEDIATES -> {
            if (this.resolveNow(mod)) {
               return null;
            }
            if (!this.resolvedExternals().isEmpty() && !this.externalMaterialsMet(mod)) {
               this.enterCollectPhase(mod);
               return null;
            }
            Task t = this.runNextInventoryStep(mod);
            if (t != null) {
               return t;
            }
            if (!this.isInventoryPhaseComplete(mod)) {
               return null;
            }
            if (this.plan.requiresCraftingTable() && !this.craftMacroTableReachable(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
            } else if (this.plan.finalTableRecipe() != null) {
               this.syncCraftingTablePos(mod);
               this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
               this.phase = this.craftMacroTableReachable(mod)
                     ? CraftMacroPhase.MOVE_TO_TABLE
                     : CraftMacroPhase.FIND_OR_PLACE_TABLE;
            } else {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
            }
         }
         case FIND_OR_PLACE_TABLE -> {
            // Table-reuse fix: if a PlaceBlockNearbyTask we dispatched has reported a placed position, adopt
            // it as craftingTablePos BEFORE re-deriving via the world block scanner. The scanner does not
            // index a just-placed block for several ticks (the place is driven client-side via
            // InteractionManager.interactBlock), so without this the freshly placed table reads "unreachable"
            // and the FSM would provision/craft a SECOND table instead of walking to the one it just placed.
            this.adoptPlacedTable(mod);
            this.syncCraftingTablePos(mod);
            if (this.craftMacroTableReachable(mod)) {
               if (this.requirementsContainTable()) {
                  // Collapse arm 3: the SAME craftMacroTableReachable evaluation that gates the on-demand
                  // add below is true this tick, so the collapse and the add site can never disagree.
                  this.collapseTableRequirement("reachable table adopted");
               }
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            // TABLE-REUSE fix: a table this MACRO placed but that is not within REACH right now is NOT the
            // same as "no table exists" — WALK to it (MOVE_TO_TABLE, which now uses a finite GoalNear)
            // instead of placing a SECOND table. This stops the place-another-table churn the log showed
            // (placing 2,64,-11 while MOVE_TO_TABLE targeted the stale 3,67,-10). We only auto-walk to a
            // table THIS task placed; an arbitrary pre-existing scanned table that is not currently
            // reachable is NOT chased here (it may be unreachable/elevated like the player-placed 3,67,-10,
            // which would otherwise wedge the approach) — when we hold a table item, placing our own is the
            // deterministic path. (A reachable pre-existing table is already adopted by craftMacroTableReachable above.)
            BlockPos ownPlaced = this.ownPlacedTableBlock(mod);
            if (ownPlaced != null) {
               this.craftingTablePos = ownPlaced;
               this.adoptedForeignTable = false;
               this.setDebugState("Walking to placed crafting table " + ownPlaced.toShortString());
               Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] FIND_OR_PLACE_TABLE: reusing own placed table at "
                     + ownPlaced.toShortString() + " (walk, do not place another)");
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            // ISSUE #2 (table-REUSE) fix: before placing/crafting a NEW table, REUSE a pre-existing,
            // pathable crafting_table within the reuse radius (default 48 blocks = 3 chunks) by WALKING to
            // it (MOVE_TO_TABLE) — the prior code only adopted a table within arm's-reach REACH=3.5, so a
            // perfectly usable table ~3-5 blocks away was ignored and the bot provisioned a SECOND table
            // (the player-observed "made another crafting table"). REACH=3.5 stays the arm's-reach
            // can-I-craft-now gate; this is the wider REUSE decision. The pathability filter
            // (CraftingTableLocator.findReusableNearby -> WorldHelper.canReach) keeps an unreachable /
            // elevated table from being chosen, and the bounded MOVE_TO_TABLE approach
            // (MAX_TABLE_APPROACH_STALL_TICKS) plus the adoptedForeignTable fallback below ensure that if a
            // chosen nearby table turns out unreachable we place our own rather than chasing it forever.
            BlockPos reusable = CraftingTableLocator
                  .findReusableNearby(mod, mod.getModSettings().getCraftingTableReuseRadius())
                  .orElse(null);
            if (reusable != null) {
               this.craftingTablePos = reusable;
               this.tablePlacedByTask = false;
               this.adoptedForeignTable = true;
               this.tableApproachStallTicks = 0;
               this.setDebugState("Walking to existing crafting table " + reusable.toShortString());
               Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] FIND_OR_PLACE_TABLE: reusing pre-existing table at "
                     + reusable.toShortString() + " within "
                     + mod.getModSettings().getCraftingTableReuseRadius() + " blocks (walk, do not place another)");
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
               this.setDebugState("Placing crafting table");
               this.tablePlacedByTask = true;
               this.adoptedForeignTable = false;
               Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] FIND_OR_PLACE_TABLE: placing crafting table from inventory");
               // INSTANCE-CHURN fix: create the PlaceBlockNearbyTask ONCE and reuse the same instance every
               // tick. The task framework keeps the FIRST-returned instance running as Task.sub (isEqual
               // matches on the block array), so a fresh instance assigned here each tick would never tick
               // and its getPlaced() would stay null forever -> adoptPlacedTable never fired -> a second
               // table got placed. Returning the SAME instance that is actually running makes getPlaced()
               // observable so adoptPlacedTable can seed craftingTablePos from the just-placed block.
               if (this.pendingTablePlaceTask == null) {
                  this.pendingTablePlaceTask = new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE);
               }
               return this.pendingTablePlaceTask;
            }
            // ON-DEMAND REQUIREMENT ADD (the single add site): the macro has ACTUALLY failed to find a
            // usable table this tick — not reachable (checked above), no own-placed table standing, no
            // table item held. A crafting table is the STATION, not an ingredient, so the output's own
            // resolve never demands it; add the (CRAFTING_TABLE, 1) requirement exactly here and nowhere
            // else. The very next resolveBudget pass reserves its materials ON TOP of the output's (the
            // output is requirements[0], so its planks are earmarked first and the table sub-craft can
            // never silently consume them), and COLLECT/CRAFT_2X2 provision the table through the same
            // bounded machinery (stall + no-gain counters, settle/drop gates) as the output itself.
            // Re-adds (the table was lost after a collapse) are hard-capped with a truthful terminate.
            if (!this.requirementsContainTable()) {
               if (this.tableRequirementAdds >= MAX_TABLE_REQUIREMENT_ADDS) {
                  this.terminateMacro("Cannot finish crafting " + this.outputName()
                        + ": the crafting table was lost repeatedly (placed "
                        + this.tableRequirementAdds + " times); giving up.");
                  return null;
               }
               this.tableRequirementAdds++;
               this.requirements.add(new MaterialResolver.BudgetTarget(Items.CRAFTING_TABLE, 1));
               Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] REQUIREMENT-ADD crafting_table (add #"
                     + this.tableRequirementAdds + ") requirements=" + this.describeRequirements()
                     + " sig=" + this.requirementSetSig());
            }
            // beginIngredientRecovery re-resolves first (over the requirement set, table included) and
            // routes to COLLECT (externals owed), CRAFT_2X2 (inventory steps owed, incl. the table's
            // planks + the table craft), the final table craft, or a bounded terminate. It is also the
            // collapse-return point: once the table is crafted, collapseSatisfiedRequirements (arm 1)
            // releases the requirement and the hasItem branch above places it — control returns to the
            // step that needed the table, with the output's reserved materials intact.
            this.beginIngredientRecovery(mod);
         }
         case MOVE_TO_TABLE -> {
            if (!this.ensureCraftingTablePos(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
               return null;
            }
            if (!CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
               this.tableApproachStallTicks++;
               // BOUNDED-TERMINATION fix: MOVE_TO_TABLE / LOOK_AT_TABLE never call resolveNow, so the
               // convergence accounting in resolveNow cannot catch a stuck approach on its own. If the bot
               // has failed to bring the table within REACH for the whole approach window (unreachable
               // elevated table, no path, or baritone wandering), terminate the macro here with a
               // human-meaningful reason that reaches both the player and the model (DESIGN.md §3) instead
               // of looping MOVE_TO_TABLE<->CRAFT_3X3 until the player quits.
               if (this.tableApproachStallTicks >= MAX_TABLE_APPROACH_STALL_TICKS) {
                  // ISSUE #2 anti-wedge fallback: if the table we are approaching is a PRE-EXISTING one this
                  // macro adopted for reuse (not one it placed), an unreachable-within-bound result must NOT
                  // terminate the whole craft — fall back to placing its OWN table (the deterministic path),
                  // exactly as if no nearby table had been found. Blacklist the unreachable table so the
                  // scanner does not immediately re-adopt the same one, clear the pin, and re-enter
                  // FIND_OR_PLACE_TABLE. The MAX_TABLE_REQUIREMENT_ADDS cap on the place path still bounds
                  // this, so we cannot loop adopt->fail->adopt forever.
                  if (this.adoptedForeignTable && this.craftingTablePos != null) {
                     Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] MOVE_TO_TABLE: adopted pre-existing table "
                           + this.craftingTablePos.toShortString()
                           + " unreachable within approach bound -> place own table instead");
                     // allowedFailures=0 -> the single bounded-approach failure (~15s) marks it unreachable
                     // immediately (unreachable() is numberOfFailures > allowed -> 1 > 0), so the scanner /
                     // canReach filter won't re-adopt this same table on the next FIND_OR_PLACE_TABLE tick.
                     mod.getBlockScanner().requestBlockUnreachable(this.craftingTablePos, 0);
                     this.craftingTablePos = null;
                     this.adoptedForeignTable = false;
                     this.tableApproachStallTicks = 0;
                     this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
                     return null;
                  }
                  this.terminateMacro("Cannot finish crafting " + this.outputName()
                        + ": the crafting table is unreachable (approach stalled).");
                  return null;
               }
               this.setDebugState("Moving to table " + this.craftingTablePos.toShortString());
               // MOVEMENT FIX: request an explicit, FINITE interaction-distance goal (GoalNear(pos, 3)) so
               // baritone has a real goal to path toward and completes standing adjacent to the table. The
               // old `new GetCloseToBlockTask(pos)` seeded range=Integer.MAX_VALUE which overflowed to a
               // degenerate "stand on the block" goal and made the bot wander. Range 3 keeps the bot inside
               // the CraftingTableLocator.REACH=3.5 threshold that LOOK_AT_TABLE / CRAFT_3X3_OUTPUT expect.
               return new GetWithinRangeOfBlockTask(this.craftingTablePos, 3);
            }
            this.tableApproachStallTicks = 0;
            this.lookHold.setInterval(mod.getModSettings().getCraftTableLookHoldSeconds());
            this.lookHold.reset();
            this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
            this.phase = CraftMacroPhase.LOOK_AT_TABLE;
         }
         case LOOK_AT_TABLE -> {
            this.lookAtTable(mod);
            if (this.lookHold.elapsed()) {
               this.phase = CraftMacroPhase.CRAFT_3X3_OUTPUT;
               this.delayActive = false;
            }
         }
         case CRAFT_3X3_OUTPUT -> {
            if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            RecipeTarget tableRecipe = this.plan.finalTableRecipe();
            if (tableRecipe == null) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            if (!this.ensureCraftingTablePos(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
               return null;
            }
            if (!CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            if (!CraftingInventoryOps.hasMaterials(mod, tableRecipe)) {
               // The craft AT the table (the output's own 3x3 recipe) is short — NOT the table's own
               // materials. Worded precisely: the old "Missing table-craft ingredients" fired right after
               // a successful table placement and poisoned log analysis.
               this.setDebugState("Missing ingredients for the table craft of " + this.outputName() + "; recovering");
               this.beginIngredientRecovery(mod);
               return null;
            }
            if (this.tableCraftsRemaining <= 0) {
               this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
            }
            if (this.tableCraftsRemaining <= 0) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            this.setDebugState("Table crafting " + this.plan.outputItem().getDescription().getString());
            if (!this.delayActive) {
               this.lookAtTable(mod);
               this.craftDelay.setInterval(mod.getModSettings().getCraftDelaySeconds());
               this.craftDelay.reset();
               this.delayActive = true;
               return null;
            }
            if (!this.craftDelay.elapsed()) {
               this.lookAtTable(mod);
               return null;
            }
            this.delayActive = false;
            this.lookAtTable(mod);
            // WS5/WS6: carry the MC-recipe carrier (mcRecipe / mcResultStack / registries) through to
            // performSingleCraft so generic/modded table crafts preserve output NBT/DataComponents
            // (copyWithCount) and return container-item remainders. Legacy null-carrier wrappers pass
            // null through and keep the 3-arg `new ItemStack(outputItem, yield)` fallback. Mirrors
            // CraftingInventoryOps.performCrafts.
            RecipeTarget single = new RecipeTarget(
                  tableRecipe.getOutputItem(), tableRecipe.getRecipe().outputCount(), tableRecipe.getRecipe(),
                  tableRecipe.getMcRecipe(), tableRecipe.getMcResultStack(), tableRecipe.getRegistries());
            if (CraftingInventoryOps.performSingleCraft(mod, single)) {
               this.tableCraftsRemaining--;
            } else {
               this.beginIngredientRecovery(mod);
            }
         }
         case VERIFY_OUTPUT -> {
            if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
               this.phase = CraftMacroPhase.DONE;
            } else {
               this.resumeAfterIncompleteOutput(mod);
            }
         }
         case DONE -> {
            return null;
         }
      }
      return null;
   }

   private void enterCollectPhase(PlayerEngineController mod) {
      this.phase = CraftMacroPhase.COLLECT_MISSING_MATERIALS;
      this.collectStallTicks = 0;
      this.collectNoGainTicks = 0;
      this.lastCollectHeld = -1;
      // [[CRAFT-RESOLVER-DIAG]] Requirement-set entry snapshot: which requirements drive this COLLECT
      // episode and what the one owed-truth says is still externally owed. NOTE: the dispatch pin is
      // deliberately NOT reset here — it persists across COLLECT re-entries and retires only on key
      // change (requirement-set signature / external switch) or task restart.
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] enterCollectPhase: requirements=" + this.describeRequirements()
            + " sig=" + this.requirementSetSig() + " externals=" + this.resolvedExternals()
            + " pin=" + (this.dispatchPinKey == null ? "none" : this.dispatchPinKey + "@" + this.dispatchPinAbsolute));
      // Issue A: refresh the pickup-settle interval from config and start "settled" so the first collect
      // tick (nothing mined yet) is never blocked; it only gates AFTER a gather has been issued.
      this.collectSettleTimer.setInterval(mod.getModSettings().getMineCollectSettleSeconds());
      this.collectSettleTimer.forceElapse();
   }

   /**
    * Drive external gathering until the needed materials are actually IN INVENTORY (strict count), so a
    * just-mined ground drop is picked up before crafting. Bounded by {@link #MAX_COLLECT_STALL_TICKS}: if
    * the held count does not rise for that long the material is uncollectable/unobtainable and the macro
    * stops gathering (crafts from what it has if possible, else terminates) instead of mining forever.
    */
   private Task tickCollectMissingMaterials(PlayerEngineController mod) {
      if (this.resolveNow(mod)) {
         return null;
      }
      List<ItemTarget> externals = this.resolvedExternals();

      // WS3 model channel: the gather subtree (MineAndCollectTask) breaks tool-requiring blocks and owns the
      // bounded tool-acquisition guard + the player chat line. But the ONLY channel @get's onGetComplete
      // reads for the MODEL is this macro's failureReason. So when the live gather has latched a terminal
      // tool-acquisition failure (it cannot make the required pickaxe), propagate its machine reason into
      // failureReason via the existing terminateMacro path, so the model is told truthfully instead of
      // getting the generic "materials unreachable" stall reason (DESIGN.md §3 dual-audience). The running
      // gather is this macro's sub (kept by the framework across ticks); walk the sub-chain to find it.
      String toolReason = this.dispatchedGatherToolFailureReason();
      if (toolReason != null) {
         this.terminateMacro(toolReason);
         return null;
      }

      // Progress is measured by the strict HELD count of still-needed externals. A reachable ground drop
      // counted by the sufficiency axis is NOT progress until it is actually picked up: previously the
      // sufficiency axis declared the target "met" off an uncollected drop, advanced, and then the craft
      // failed with "Failed to collect required materials".
      //
      // ISSUE 3 fix: the stall counter MUST NOT trip while the bot is legitimately working a reachable
      // source. The prior logic reset ONLY on a strict held-count INCREASE, so once the bot picked up the
      // first of N spread-out logs it got MAX_COLLECT_STALL_TICKS (~15s) total to obtain the REST — the
      // normal mine->travel-to-next-reachable-log gap between two placed/spread logs exceeds 15s and tripped
      // "materials unreachable" mid-swing on the second block (confirmed in the 1.20.1 testbed log:
      // first log picked up at 12:45:27, held flat at 1 while actively mining the second, stall fired
      // exactly 15s later at 12:45:42). Treat the phase as PROGRESSING (reset the counter) whenever ANY of:
      //   (a) the strict held count rose this tick (a needed external actually entered inventory), OR
      //   (b) a matching drop for a needed external is on the ground / in flight within pickup range
      //       (a just-broken log still being collected — never abandon it as "unreachable"), OR
      //   (c) a reachable local source can still cover the remaining deficit for some needed external
      //       (reachable wood still exists to mine — the bot is mid-gather, not stuck).
      // Only when NONE of these hold for MAX_COLLECT_STALL_TICKS consecutive ticks is the material
      // genuinely unreachable: the held count is flat, no drop is incoming, and no reachable source remains.
      // This keeps the bound as a true no-reachable-target backstop (the genuinely-no-source case is still
      // bounded by this counter AND by MineOrCollect's bounded wander) while letting a normal multi-log
      // gather across spread-out logs complete. Keep [[CRAFT-RESOLVER-DIAG]] intact.
      int held = this.totalExternalHeld(mod, externals);
      boolean progressing = held > this.lastCollectHeld
         || this.matchingDropsInFlight(mod)
         || this.reachableSourceForAnyExternal(mod, externals);
      if (progressing) {
         this.collectStallTicks = 0;
      } else {
         this.collectStallTicks++;
      }
      // HARD held-flat backstop: independent of the reachability probe (which can falsely keep
      // collectStallTicks pinned at 0). Counts ONLY a genuine inventory gain or an in-flight drop as
      // progress; a "reachable" source that the bot can never actually mine does NOT reset it. Bounded far
      // above any legitimate mine/travel gap, so it only fires on a true wedge (held flat, nothing incoming).
      boolean strictGain = held > this.lastCollectHeld;
      if (strictGain || this.matchingDropsInFlight(mod)) {
         this.collectNoGainTicks = 0;
      } else {
         this.collectNoGainTicks++;
      }
      this.lastCollectHeld = held;
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] COLLECT stall: held=" + held + " progressing="
         + progressing + " collectStallTicks=" + this.collectStallTicks
         + " collectNoGainTicks=" + this.collectNoGainTicks);

      // HELD-VS-RESERVED fix: drive collection off the resolver's NET externalNeeded() list, NOT a raw
      // getItemCount(matches) >= targetCount per target. The resolver already nets held-as-input stock
      // against reservations, so any target still PRESENT in externalNeeded() is genuinely still owed even
      // if the bot holds matching items reserved for a sub-craft (the held jungle_log earmarked for
      // jungle_planks must NOT count as satisfying the "1 more log" external). resolveNow re-resolves each
      // tick, so as gathered logs land the resolver re-nets and the external shrinks/empties naturally.
      if (!externals.isEmpty()) {
         ItemTarget target = externals.get(0);
         // Bounded fallback: collecting has raised no inventory for too long (uncollectable drop, or no
         // source in reach). Terminate cleanly with the owed externals ENUMERATED so the reason can never
         // contradict the inventory snapshot delivered with it (the parent's obtain-attempt cap engages).
         // Trip on EITHER bound: the standard no-progress stall (all three progress arms flat for ~15s) OR
         // the hard held-flat backstop (~60s with zero strict inventory gain, INDEPENDENT of the reachability
         // probe -- catches a falsely-"reachable" source the bot can never actually mine, the live wedge).
         // One-truth note: the old "output craftable from inventory" stall-escape is gone — the state it
         // rescued (output craftable yet externals non-empty) is unrepresentable now that these externals
         // ARE the requirement set's externals: if everything is craftable from inventory, externalNeeded()
         // is empty and COLLECT exits through the normal empty-externals path the same tick.
         if (this.collectStallTicks >= MAX_COLLECT_STALL_TICKS
               || this.collectNoGainTicks >= MAX_COLLECT_NO_GAIN_TICKS) {
            // ISSUE 3 fix: never abandon a just-mined log that is still being picked up. The stall-terminate
            // path (unlike the advance-to-craft path below) previously ignored drops in flight; defer the
            // terminate one more tick while a matching drop for a needed external is on the ground / in
            // flight within pickup range. The next tick's progressing check (matchingDropsInFlight) will
            // have reset the counter once the drop is credited, so this only delays a GENUINE terminate.
            if (this.matchingDropsInFlight(mod)) {
               this.setDebugState("Waiting for just-mined drop before deciding materials unreachable");
               return null;
            }
            this.terminateMacro(this.collectShortfallReason(mod, externals));
            return null;
         }
         // REQUIREMENT-PINNED dispatch (traps B + C). The dispatched ResourceTask judges its count as an
         // ABSOLUTE inventory total (isFinished() -> StorageHelper.itemTargetsMet), while
         // target.getTargetCount() is the resolver's NET shortfall — so dispatch absolute = held + net
         // (never born-finished: net >= 1 at pin time, so absolute > heldMatching). The absolute is
         // computed ONCE per (external key, requirement-set signature) and PINNED: post-membership-
         // coherence the recomputed value is constant while net > 0 (a gained matching item lowers net by
         // exactly one as held rises by one), so a stable pin keeps the SAME MineAndCollect running (no
         // per-unit STOP/START churn) and the gather finishes exactly when the requirement is met — the
         // same resolve empties externalNeeded() and COLLECT exits below. Inventory LOSS is the only
         // legitimate riser (the heldMatching < heldAtPin re-pin); a recomputed RISE without loss is
         // genuine drift, clamped and logged as DISPATCH-DRIFT (the standing regression tripwire).
         int heldMatching = mod.getItemStorage().getItemCount(target.getMatches());
         int recomputed = heldMatching + target.getTargetCount();
         String key = externalKeyOf(target) + "|" + this.requirementSetSig();
         String pinEvent;
         if (!key.equals(this.dispatchPinKey)) {
            this.dispatchPinKey = key;
            this.dispatchPinAbsolute = recomputed;
            this.heldAtPin = heldMatching;
            pinEvent = "NEW";
         } else if (heldMatching < this.heldAtPin) {
            // Inventory shrank (theft, death, despawn, or a craft consumed matching stock): the ONLY
            // legitimate riser under an unchanged key. Re-pin freely.
            this.dispatchPinAbsolute = recomputed;
            this.heldAtPin = heldMatching;
            pinEvent = "REPIN(inventory shrank)";
         } else if (recomputed > this.dispatchPinAbsolute) {
            // Must never fire for 1:1 recipe families post-membership-coherence. Clamp to the pin —
            // bounded and truthful either way (the next resolve still owes any remainder).
            pinEvent = "CLAMPED(DISPATCH-DRIFT)";
         } else {
            if (recomputed < this.dispatchPinAbsolute) {
               // The requirement legitimately shrank: re-pin downward silently.
               this.dispatchPinAbsolute = recomputed;
               this.heldAtPin = heldMatching;
            }
            pinEvent = "HELD";
         }
         // Copy ctor preserves catalogueName + matches, so TaskCatalogue routing stays named (F1).
         ItemTarget absoluteTarget = new ItemTarget(target, this.dispatchPinAbsolute);
         String dispatchDiag = "[[CRAFT-RESOLVER-DIAG]] DISPATCH external=" + target
               + " net=" + target.getTargetCount() + " heldMatching=" + heldMatching
               + " absolute=" + this.dispatchPinAbsolute + " pin=" + pinEvent
               + " requirements=" + this.describeRequirements();
         if (!dispatchDiag.equals(this.lastDispatchDiag)) {
            this.lastDispatchDiag = dispatchDiag;
            Debug.logInternal(dispatchDiag);
         }
         this.setDebugState("Collecting " + absoluteTarget);
         ResourceTask gather = TaskCatalogue.getItemTask(absoluteTarget);
         if (gather == null) {
            return this.advanceAfterCollectFail(target);
         }
         // A gather (which may break a block) is being issued: (re)start the pickup-settle window so the
         // resulting drop is credited before we advance to crafting on a strict-count read.
         this.collectSettleTimer.reset();
         return gather;
      }

      // The resolver reports no external acquisition still owed. Issue A pickup-settle gate: before
      // ADVANCING to crafting, do not trust a strict-count read taken in the window between a block break
      // and the drop entering inventory. If a matching drop for ANY external the macro was collecting is
      // still on the ground / in flight within pickup range and the short settle window since the last
      // gather has not elapsed, wait one more tick (return null) so the drop is fully picked up and indexed
      // before the craft step reads inventory. This stops the "read 0 right after a chop" race that made the
      // resolver mis-decide. The MAX_COLLECT_STALL_TICKS backstop bounds this, so an uncollectable drop can
      // never block the advance forever.
      if (!this.collectSettleTimer.elapsed() && this.matchingDropsInFlight(mod)) {
         this.setDebugState("Waiting for just-mined drop to be picked up before crafting");
         return null;
      }
      this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
      this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
      return null;
   }

   /**
    * Issue A helper: true when at least one ground/in-flight drop matching this macro's output or any of
    * its resolved external materials is still within pickup range. Uses the shared entity-tracker drop
    * enumeration (no world block scan, no model call); a non-empty result means a just-mined drop has not
    * yet entered inventory, so a strict-count read would be premature.
    */
   private boolean matchingDropsInFlight(PlayerEngineController mod) {
      Vec3 origin = mod.getPlayer().position();
      double dropRadius = mod.getModSettings().getAggregateCountDropRadius();
      ItemTarget outputTarget = new ItemTarget(this.plan.outputItem(), 1);
      if (!MaterialAvailability.targetsMetSufficiency(mod, origin, dropRadius, outputTarget)
            && mod.getEntityTracker().itemDropped(outputTarget)) {
         return true;
      }
      for (ItemTarget ext : this.resolvedExternals()) {
         if (!ItemTarget.nullOrEmpty(ext) && mod.getEntityTracker().itemDropped(ext)) {
            return true;
         }
      }
      return false;
   }

   /**
    * ISSUE 3 helper: true when a reachable LOCAL source (nearby mineable blocks or reachable ground drops)
    * can still cover the remaining deficit for at least one still-needed external target. Reuses the same
    * SOURCE-ROUTING axis ({@link MaterialAvailability#localSourceCanCoverRemainder}) and the same
    * cap-clamped radius that {@code MineAndCollectTask.getWanderTask} uses to decide "mine locally vs
    * wander", so "reachable wood still exists" is judged consistently with the gather subtree. While this
    * is true the COLLECT stall counter is held at 0 (the bot is mid-gather of a reachable source, not
    * stuck); the counter only accrues — and "materials unreachable" can only fire — once NO external has a
    * reachable source AND no drop is in flight AND the held count is flat. The genuinely-no-source case is
    * still bounded: this returns false, the counter accrues, and MineOrCollect's bounded wander caps any
    * far-source seeking, so the macro provably terminates when wood is truly unreachable.
    */
   private boolean reachableSourceForAnyExternal(PlayerEngineController mod, List<ItemTarget> externals) {
      Vec3 origin = mod.getPlayer().position();
      double dropRadius = mod.getModSettings().getAggregateCountDropRadius();
      double localSourceBlockRadius = Math.min(
         mod.getModSettings().getAggregateLocalSourceBlockRadius(),
         mod.getModSettings().getAgenticMaxTravelRadius());
      for (ItemTarget target : externals) {
         if (ItemTarget.nullOrEmpty(target) || target.getTargetCount() <= 0) {
            continue;
         }
         // HELD-VS-RESERVED fix (Issue 2): do NOT re-subtract held stock here. target.getTargetCount() comes
         // from the resolver's externalNeeded(), which is ALREADY the NET shortfall — the resolver reserved
         // any held-but-input stock against its sub-crafts before reporting how many raw units are still owed
         // (held 1 oak_log earmarked for planks AND still 1 log short => external "log count=1"). The old
         // `remainder = targetCount - getItemCount(matches)` re-counted that same reserved held log against
         // the pooled "log" match set, zeroing the remainder (1 - 1 = 0) and skipping the reachability probe
         // exactly when one log is held and a second is reachable nearby — so seeking the genuinely reachable
         // 2nd log was mis-read as no-progress and the stall accrued to "materials unreachable". A target
         // still present in externalNeeded() is by definition a genuine, net, non-zero shortfall, so probe
         // its reachability directly. localSourceCanCoverRemainder itself subtracts sufficiencyCount once
         // (its own net read), which is correct there — the double-net was solely this extra gate. The
         // genuine no-source case still returns false (no reachable mineable block / drop), so the
         // MAX_COLLECT_STALL_TICKS backstop + MineOrCollect bounded wander still terminate.
         //
         // Use the RESERVATION-AWARE source probe: target.getTargetCount() is the resolver's net external
         // demand (held-but-reserved stock already subtracted). localSourceCanCoverRemainder would re-credit
         // that held reserved inventory via its sufficiencyCount term and so (a) falsely zero this remainder
         // when one log is held + a second is reachable (the very stall we are fixing) and (b) falsely report
         // "reachable" off reserved inventory when NO source exists. reachableLocalSourceCoversNetDemand
         // judges progress purely on reachable EXTERNAL capacity (nearby drops + mineable yield) vs the net
         // demand, so seeking a genuinely reachable 2nd log counts as progress while a true no-source case
         // still returns false and lets the bounded backstop terminate.
         if (MaterialAvailability.reachableLocalSourceCoversNetDemand(
               mod, target, origin, dropRadius, localSourceBlockRadius)) {
            return true;
         }
      }
      return false;
   }

   /** Total STRICT inventory count held across every still-needed external target. */
   private int totalExternalHeld(PlayerEngineController mod, List<ItemTarget> externals) {
      int sum = 0;
      for (ItemTarget target : externals) {
         sum += mod.getItemStorage().getItemCount(target.getMatches());
      }
      return sum;
   }

   /**
    * Terminate the macro WITHOUT wedging: record the reason, mark the phase DONE, and self-stop so the
    * task becomes {@code stopped()}. The parent {@link ResolveStorageChestTask} then drops the inert
    * child and its bounded obtain-attempt cap / absolute timeout engages, instead of re-returning a
    * never-finishing child forever.
    */
   private void terminateMacro(String reason) {
      this.failureReason = reason;
      this.setDebugState(reason);
      this.phase = CraftMacroPhase.DONE;
      this.stop();
   }

   /** True when the live requirement set currently contains the on-demand crafting-table entry. */
   private boolean requirementsContainTable() {
      for (MaterialResolver.BudgetTarget bt : this.requirements) {
         if (bt.item() == Items.CRAFTING_TABLE) {
            return true;
         }
      }
      return false;
   }

   /**
    * Collapse arms 1 + 2, checked at the top of every {@link #resolveNow}: the table requirement
    * collapses the moment a crafting-table item is HELD (arm 1, the negation of the add chain's
    * hasItem guard) or a table THIS macro placed is standing (arm 2, {@link #ownPlacedTableBlock} —
    * deliberately NEVER the bare craftingTablePos-block check: a stale pin to a real-but-distant
    * FOREIGN table the add site judged unusable must not collapse the requirement, or add/collapse
    * would ping-pong to the re-add cap and a false "lost repeatedly" terminate while the truthful
    * behavior is to craft an own table). Arm 3 (reachable table adopted) lives in
    * FIND_OR_PLACE_TABLE's reachable branch — the same evaluation that gates the add. All three arms
    * route through {@link #collapseTableRequirement}: one collapse meaning, three triggers mirroring
    * the add site's guards one-for-one.
    */
   private void collapseSatisfiedRequirements(PlayerEngineController mod) {
      if (!this.requirementsContainTable()) {
         return;
      }
      if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
         this.collapseTableRequirement("table item held");
      } else if (this.ownPlacedTableBlock(mod) != null) {
         this.collapseTableRequirement("own placed table standing");
      }
   }

   /**
    * The ONE collapse meaning: remove the table requirement from the live set. Removal IS the
    * reservation release — the very next {@code resolveBudget} pass reserves nothing for the table, so
    * the needing step's arithmetic ("has table in inventory? yes -> place it") comes out correct. The
    * requirement-set signature change also retires the old dispatch pin automatically (key change).
    */
   private void collapseTableRequirement(String why) {
      this.requirements.removeIf(bt -> bt.item() == Items.CRAFTING_TABLE);
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] REQUIREMENT-COLLAPSE crafting_table (" + why
            + ") -> requirements=" + this.describeRequirements() + " sig=" + this.requirementSetSig());
   }

   /** Ordered {@code item:count} signature of the live requirement set (part of the dispatch pin key). */
   private String requirementSetSig() {
      StringBuilder sb = new StringBuilder();
      for (MaterialResolver.BudgetTarget bt : this.requirements) {
         if (sb.length() > 0) {
            sb.append(',');
         }
         sb.append(ItemHelper.stripItemName(bt.item())).append(':').append(bt.count());
      }
      return sb.toString();
   }

   /** Human-readable requirement set for diag/reason lines, e.g. {@code [chest x1, crafting_table x1]}. */
   private String describeRequirements() {
      StringBuilder sb = new StringBuilder("[");
      for (MaterialResolver.BudgetTarget bt : this.requirements) {
         if (sb.length() > 1) {
            sb.append(", ");
         }
         sb.append(ItemHelper.stripItemName(bt.item())).append(" x").append(bt.count());
      }
      return sb.append(']').toString();
   }

   /**
    * Frozen pin-key definition for a dispatched external: the catalogue name when present, else the
    * registry names of the match set, sorted and comma-joined (the same key idea as the resolver's
    * private coalesce key). Combined with {@link #requirementSetSig} this identifies one gathering
    * episode: the pinned absolute may only change when this key changes, shrinks legitimately, or
    * inventory is lost.
    */
   private static String externalKeyOf(ItemTarget target) {
      if (target.isCatalogueItem()) {
         return target.getCatalogueName();
      }
      Item[] matches = target.getMatches();
      String[] names = new String[matches.length];
      for (int i = 0; i < matches.length; i++) {
         names[i] = matches[i] == null ? "null" : ItemHelper.stripItemName(matches[i]);
      }
      Arrays.sort(names);
      return String.join(",", names);
   }

   /** Display name of an external target for reason strings. */
   private static String describeTarget(ItemTarget target) {
      if (target.isCatalogueItem() && target.getCatalogueName() != null) {
         return target.getCatalogueName();
      }
      Item[] m = target.getMatches();
      return (m.length > 0 && m[0] != null) ? ItemHelper.stripItemName(m[0]) : "unknown";
   }

   /** The macro output's display name for reason strings. */
   private String outputName() {
      return this.plan.outputItem().getDescription().getString();
   }

   /**
    * Enumerated COLLECT-shortfall reason (DESIGN.md §3, both audiences): names the output (and the
    * crafting table while its requirement is active), every still-owed external WITH its count, and a
    * held-wood summary — so the reason can never claim "couldn't get wood" while the same feedback
    * payload shows a wood-stuffed inventory.
    */
   private String collectShortfallReason(PlayerEngineController mod, List<ItemTarget> externals) {
      StringBuilder owed = new StringBuilder();
      for (ItemTarget t : externals) {
         if (ItemTarget.nullOrEmpty(t) || t.getTargetCount() <= 0) {
            continue;
         }
         if (owed.length() > 0) {
            owed.append(", ");
         }
         owed.append(t.getTargetCount()).append(' ').append(describeTarget(t));
      }
      String subject = this.outputName() + (this.requirementsContainTable() ? " and its crafting table" : "");
      return "Cannot finish crafting " + subject + ": still need "
            + (owed.length() == 0 ? "materials" : owed)
            + " and no reachable source (holding " + heldWoodSummary(mod) + ").";
   }

   /** Held log/plank counts for reason strings (mirrors the resolver's held-wood diag), or "nothing". */
   private static String heldWoodSummary(PlayerEngineController mod) {
      StringBuilder sb = new StringBuilder();
      appendHeldCounts(mod, sb, ItemHelper.LOG);
      appendHeldCounts(mod, sb, ItemHelper.PLANKS);
      return sb.length() == 0 ? "nothing" : sb.toString();
   }

   private static void appendHeldCounts(PlayerEngineController mod, StringBuilder sb, Item[] family) {
      for (Item it : family) {
         if (it == null) {
            continue;
         }
         int held = mod.getItemStorage().getItemCount(it);
         if (held > 0) {
            if (sb.length() > 0) {
               sb.append(", ");
            }
            sb.append(ItemHelper.stripItemName(it)).append('=').append(held);
         }
      }
   }

   /**
    * Re-derive the remaining work from LIVE inventory (resolver, not the stale plan) and resume
    * collection or 2x2 crafts — never re-craft a table when one is already in the world. UNOBTAINABLE
    * and no-progress/attempt-cap stuck states route through {@link #resolveNow} -> {@link #terminateMacro}
    * (the single bounded-failure primitive).
    */
   private void beginIngredientRecovery(PlayerEngineController mod) {
      this.replan(mod);
      this.delayActive = false;
      if (this.resolveNow(mod)) {
         return;
      }
      if (!this.resolvedExternals().isEmpty() && !this.externalMaterialsMet(mod)) {
         this.enterCollectPhase(mod);
         return;
      }
      this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
      if (this.inventoryStepIndex < this.inventorySteps(mod).size()) {
         this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
         return;
      }
      if (!this.resolvedExternals().isEmpty()) {
         this.enterCollectPhase(mod);
         return;
      }
      // PRIMARY-bug fix: with no inventory sub-craft and no external gather left, the ONLY remaining work may
      // be the table-bound final craft (CRAFT_OUTPUT_IN_TABLE), which inventorySteps() filters out. That is
      // NOT a "cannot gather ingredients" dead end — the output just needs to be crafted at a table. Route
      // into the table phases instead of terminating. Bounded failure is preserved: finalTableCraftPending
      // returns true ONLY when a final table recipe exists, the output is still short, and no other work is
      // owed; a truly-impossible craft (UNOBTAINABLE / no-progress) is already caught by resolveNow above.
      if (this.finalTableCraftPending(mod)) {
         this.advanceToFinalTableCraft(mod);
         return;
      }
      this.terminateMacro("Cannot finish crafting " + this.outputName()
            + ": no remaining craft step or gatherable material can produce it.");
   }

   /**
    * Table-reuse fix: if the {@link PlaceBlockNearbyTask} this macro dispatched has recorded a placed
    * position (and the block is actually a crafting table now), adopt it as {@link #craftingTablePos} and
    * clear the pending reference. Verifying the block is genuinely a crafting table guards against an
    * interrupted/incomplete place (or the player breaking it) so we never trust a phantom position.
    */
   private void adoptPlacedTable(PlayerEngineController mod) {
      if (this.pendingTablePlaceTask == null) {
         return;
      }
      BlockPos placed = this.pendingTablePlaceTask.getPlaced();
      if (placed != null && mod.getWorld().getBlockState(placed).is(Blocks.CRAFTING_TABLE)) {
         this.craftingTablePos = placed;
         this.tablePlacedByTask = true;
         this.adoptedForeignTable = false;
         this.pendingTablePlaceTask = null;
         Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] adopted just-placed crafting table at " + placed.toShortString());
      }
   }

   /**
    * PRIMARY-bug helper. True when the macro's final output is a table-bound craft that is still owed:
    * the plan has a final 3x3 table recipe, the output is still short of target, and the only remaining
    * resolver work is the {@code CRAFT_OUTPUT_IN_TABLE} step (no inventory sub-crafts, no external gathers
    * remain). In that state the FSM must advance into the table phases (FIND_OR_PLACE_TABLE / MOVE_TO_TABLE
    * -> CRAFT_3X3_OUTPUT) to actually craft the output at a table — it is NOT a "cannot gather ingredients"
    * dead end. {@link #inventorySteps} deliberately filters {@code CRAFT_OUTPUT_IN_TABLE} out of the 2x2
    * set, so without this check {@link #beginIngredientRecovery} sees an empty inventory-step list + empty
    * externals and falsely terminates (the chest bug: a lone CRAFT_OUTPUT_IN_TABLE step + externals=0).
    *
    * <p>Restricted to the genuinely-progressable case so bounded failure is preserved: if there is no final
    * table recipe, or the output is already met, or any non-table inventory/external work is still
    * outstanding, this returns false and the existing recovery/terminate logic runs unchanged.
    */
   private boolean finalTableCraftPending(PlayerEngineController mod) {
      if (this.plan.finalTableRecipe() == null) {
         return false;
      }
      if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
         return false;
      }
      // The resolver must still report the table-bound output craft as remaining work...
      boolean tableStepRemains = this.resolvedSteps().stream()
            .anyMatch(s -> s.kind() == CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE);
      if (!tableStepRemains) {
         return false;
      }
      // ...and there must be NO non-table inventory sub-craft and NO external gather still owed, otherwise
      // those take priority (the normal collect / 2x2 paths handle them) and this is not yet "table only".
      return this.findFirstOpenInventoryStep(mod) >= this.inventorySteps(mod).size()
            && this.externalMaterialsMet(mod);
   }

   /**
    * PRIMARY-bug fix. Route a pending table-bound output craft into the table phases. Picks
    * MOVE_TO_TABLE when a table is already reachable (e.g. one this macro just placed), else
    * FIND_OR_PLACE_TABLE to obtain/place one. Seeds {@code tableCraftsRemaining} so CRAFT_3X3_OUTPUT
    * knows how many crafts to issue. Centralized so {@link #beginIngredientRecovery} and the
    * FIND_OR_PLACE_TABLE fall-through stay in sync.
    */
   private void advanceToFinalTableCraft(PlayerEngineController mod) {
      this.adoptPlacedTable(mod);
      this.syncCraftingTablePos(mod);
      this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
      boolean reachable = this.craftMacroTableReachable(mod);
      this.phase = reachable ? CraftMacroPhase.MOVE_TO_TABLE : CraftMacroPhase.FIND_OR_PLACE_TABLE;
      Debug.logInternal("[[CRAFT-RESOLVER-DIAG]] advanceToFinalTableCraft: output="
            + this.plan.outputItem().getDescription().getString()
            + " tableReachable=" + reachable + " -> phase=" + this.phase
            + " craftsRemaining=" + this.tableCraftsRemaining);
   }

   /**
    * DEFECT 2 guard term for {@link #resolveNow}. True when the FSM is in one of the four table phases
    * (FIND_OR_PLACE_TABLE / MOVE_TO_TABLE / LOOK_AT_TABLE / CRAFT_3X3_OUTPUT) AND the current live resolve
    * still carries the table-bound final output craft ({@code CRAFT_OUTPUT_IN_TABLE}). In that window the
    * tiered deficit legitimately sits flat at the single table-craft step while the bot finds/places a
    * table, walks to it, looks at it, and runs the craftDelay'd 3x3 craft — so the no-progress counter must
    * NOT accrue. Reads {@link #resolvedSteps} (the last resolve, refreshed every {@link #resolveNow} call,
    * so it reflects the live remaining work). Returns false the instant the table craft is no longer in the
    * resolve (output obtained, or it became impossible and the resolver dropped/UNOBTAINABLE'd it), so a
    * genuinely stuck state is never masked.
    */
   private boolean tableCraftInProgress() {
      boolean tablePhase = this.phase == CraftMacroPhase.FIND_OR_PLACE_TABLE
            || this.phase == CraftMacroPhase.MOVE_TO_TABLE
            || this.phase == CraftMacroPhase.LOOK_AT_TABLE
            || this.phase == CraftMacroPhase.CRAFT_3X3_OUTPUT;
      if (!tablePhase) {
         return false;
      }
      // DEFECT 4 fix: the table craft must still be the live remaining work...
      boolean tableStepRemains = this.resolvedSteps().stream()
            .anyMatch(s -> s.kind() == CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE);
      if (!tableStepRemains) {
         return false;
      }
      // ...AND the approach must be DEMONSTRABLY progressing, not merely "in a table phase". The old guard
      // suppressed no-progress for the entire table-phase window regardless of whether the bot was actually
      // getting anywhere, so a MOVE_TO_TABLE that wandered (degenerate goal, unreachable elevated table)
      // looped forever with a flat deficit and never bounded-failed. Progress is real when EITHER a craft
      // delay is genuinely mid-flight at the table (delayActive) OR the approach has not been stalled past
      // the bound (the bot reached the table recently, or is still within its allotted approach window).
      // Once tableApproachStallTicks exceeds the bound the table is effectively unreachable: return false so
      // the no-progress / attempt-cap path in resolveNow engages and terminateMacro fires with a reason.
      if (this.delayActive) {
         return true;
      }
      return this.tableApproachStallTicks < MAX_TABLE_APPROACH_STALL_TICKS;
   }

   private void syncCraftingTablePos(PlayerEngineController mod) {
      // Keep the pinned table ONLY while it is still a table AND still reachable. STALE-PIN fix: the old
      // early-return clung to any-still-a-table pinned pos forever, so a pre-existing but unreachable table
      // (e.g. an elevated 3,67,-10) stayed pinned and MOVE_TO_TABLE / CRAFT_3X3 kept walking to a table they
      // could never reach, while the reachability gate looked at a DIFFERENT freshly-placed table -> the two
      // diverged. When the pinned table is gone OR not reachable, re-derive to the nearest REACHABLE table
      // (prefer a table this macro placed). Only re-derives to a genuinely reachable table, so this never
      // thrashes onto another unreachable one.
      if (this.craftingTablePos != null
            && mod.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE)
            && CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
         return;
      }
      CraftingTableLocator.findReachable(mod, this.craftingTablePos)
            .filter(t -> CraftingTableLocator.isReachable(mod, t.pos()))
            .ifPresent(t -> {
               this.craftingTablePos = t.pos();
               this.tablePlacedByTask = t.placedByThisTask();
            });
   }

   private boolean ensureCraftingTablePos(PlayerEngineController mod) {
      this.syncCraftingTablePos(mod);
      return this.craftingTablePos != null && mod.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE);
   }

   private void lookAtTable(PlayerEngineController mod) {
      if (this.craftingTablePos != null) {
         LookHelper.lookAt(mod, this.craftingTablePos.getCenter());
      }
   }

   private int tableCraftsStillNeeded(PlayerEngineController mod) {
      RecipeTarget tableRecipe = this.plan.finalTableRecipe();
      if (tableRecipe == null) {
         return 0;
      }
      int outHave = mod.getItemStorage().getItemCount(this.plan.outputItem());
      int outNeed = this.plan.targetCount() - outHave;
      if (outNeed <= 0) {
         return 0;
      }
      return (int)Math.ceil((double)outNeed / tableRecipe.getRecipe().outputCount());
   }

   private void resumeAfterIncompleteOutput(PlayerEngineController mod) {
      this.beginIngredientRecovery(mod);
   }

   private boolean externalMaterialsMet(PlayerEngineController mod) {
      // HELD-VS-RESERVED fix: the resolver's externalNeeded() is the authoritative NET-OF-RESERVATIONS
      // figure — it already subtracted any held stock it reserved as a sub-craft input before deciding how
      // much raw material is still owed. So if the bot holds 1 jungle_log that is reserved to become
      // jungle_planks AND still needs 1 MORE log, the resolver emits external "log count=1" and ALSO an
      // inventory step that consumes the held log. The OLD raw `getItemCount(matches) >= targetCount` check
      // re-credited that same reserved log against the external (1 held >= 1 needed -> "met"), so COLLECT
      // never re-gathered and the bot proceeded to the table with insufficient materials and looped forever.
      // Trust the resolver: a NON-EMPTY externalNeeded() means raw acquisition is still genuinely owed.
      return this.resolvedExternals().isEmpty();
   }

   private List<CraftMacroStep> inventorySteps(PlayerEngineController mod) {
      // Source of truth is the LIVE resolve, not the seed plan's (empty) step list. The final
      // table-bound output craft (CRAFT_OUTPUT_IN_TABLE) is sequenced by the FSM's table phases, so it
      // is filtered out of the 2x2 inventory-craft set here, exactly as before.
      return this.resolvedSteps().stream()
            .filter(s -> s.kind() != CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE)
            .filter(s -> !this.shouldSkipInventoryStep(mod, s))
            .toList();
   }

   private int findFirstOpenInventoryStep(PlayerEngineController mod) {
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      for (int i = 0; i < steps.size(); i++) {
         if (!this.inventoryStepSatisfied(mod, steps.get(i))) {
            return i;
         }
      }
      return steps.size();
   }

   private boolean needsMoreInventorySteps(PlayerEngineController mod) {
      return this.findFirstOpenInventoryStep(mod) < this.inventorySteps(mod).size();
   }

   /**
    * Advance the inventory-craft stall tracker and report whether the current first-open inventory step
    * has been STUCK (open, with its OUTPUT count flat, and nothing advancing it) for longer than
    * {@link #MAX_INV_STALL_TICKS}. Called once per {@link #resolveNow}.
    *
    * <p>"Nothing advancing it" means: no craft delay is currently running ({@code !delayActive}) and no
    * external acquisition is in flight ({@code !acquisitionInFlight}). While either is true the step is
    * legitimately being worked, so the counter is held at 0. The counter also resets whenever the
    * first-open step CHANGES (a prior craft satisfied a step and the macro moved on) or its held output
    * RISES (a craft landed) — so a normal multi-craft build never trips it. Only a step that stays the
    * same and flat while idle accrues stall ticks. Returns true once the bound is exceeded, which lets
    * {@link #resolveNow} stop treating the step as "pending" so the bounded-failure path can fire.
    */
   private boolean inventoryStepStuck(PlayerEngineController mod, boolean acquisitionInFlight) {
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      int open = this.findFirstOpenInventoryStep(mod);
      if (open >= steps.size()) {
         // No open inventory step -> not stuck on one. Reset the tracker.
         this.invStallStepSig = null;
         this.invStallOutputHeld = -1;
         this.invStallTicks = 0;
         return false;
      }
      CraftMacroStep step = steps.get(open);
      String sig = step.kind() + ":" + step.recipeTarget().getOutputItem().getDescriptionId();
      int held = this.inventoryStepOutputHeld(mod, step);
      // A step is "being worked" while a craft delay is mid-flight or a gather is in flight: hold at 0.
      if (this.delayActive || acquisitionInFlight) {
         this.invStallStepSig = sig;
         this.invStallOutputHeld = held;
         this.invStallTicks = 0;
         return false;
      }
      if (!sig.equals(this.invStallStepSig) || held > this.invStallOutputHeld) {
         // First-open step changed, or its output rose -> real progress. Reset and start tracking anew.
         this.invStallStepSig = sig;
         this.invStallOutputHeld = held;
         this.invStallTicks = 0;
         return false;
      }
      // Same open step, output flat, nothing advancing it -> accrue stall.
      this.invStallTicks++;
      return this.invStallTicks >= MAX_INV_STALL_TICKS;
   }

   /** Held count of an inventory step's OUTPUT (pooled match set when present, else the single output). */
   private int inventoryStepOutputHeld(PlayerEngineController mod, CraftMacroStep step) {
      if (step.outputMatches() != null && step.outputMatches().length > 0) {
         return mod.getItemStorage().getItemCount(step.outputMatches());
      }
      return mod.getItemStorage().getItemCount(step.recipeTarget().getOutputItem());
   }

   /**
    * False while a craft delay is running or any resolved 2x2 inventory sub-craft remains unsatisfied
    * (null from runNext is not "done"). Driven off the LIVE resolved inventory steps rather than a
    * persisted index, since the resolved list shrinks as crafts land.
    */
   private boolean isInventoryPhaseComplete(PlayerEngineController mod) {
      if (this.delayActive) {
         return false;
      }
      return !this.needsMoreInventorySteps(mod);
   }

   /**
    * STRICT "can we craft at a table RIGHT NOW" gate: a crafting table exists within
    * {@link CraftingTableLocator#REACH}. Used to decide whether to enter MOVE_TO_TABLE vs craft, and
    * (in FIND_OR_PLACE_TABLE) whether the just-placed/known table is already in arm's reach.
    *
    * <p>COHERENCE fix: this no longer returns true off {@link CraftingTableLocator#findReachable}'s
    * preferLocal=false else-branch, which returns the nearest scanned table WITHOUT a reachability check.
    * That made the reachability gate (a distant table judged "reachable") diverge from the position the
    * table phases actually walk to / craft against (strict REACH), so the FSM oscillated. "A table exists
    * but is far" is handled by {@link #knownTableBlock} -> MOVE_TO_TABLE, not by claiming reachability.
    */
   private boolean craftMacroTableReachable(PlayerEngineController mod) {
      if (this.craftingTablePos != null && CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
         return true;
      }
      return CraftingTableLocator.findReachable(mod, this.craftingTablePos)
            .filter(t -> CraftingTableLocator.isReachable(mod, t.pos()))
            .isPresent();
   }

   /**
    * TABLE-REUSE helper: the position of a crafting_table block THIS macro placed and that still exists,
    * regardless of current REACH, or {@code null} if none. Used so a freshly-placed-but-not-yet-in-REACH
    * table is WALKED to (MOVE_TO_TABLE) rather than triggering a SECOND placement — the exact churn the log
    * showed. Deliberately does NOT return an arbitrary pre-existing scanned table: an unreachable
    * pre-existing one (e.g. a player-placed elevated 3,67,-10) must not be chased into a wedge; a REACHABLE
    * pre-existing table is already handled by {@link #craftMacroTableReachable} before this is consulted.
    */
   private BlockPos ownPlacedTableBlock(PlayerEngineController mod) {
      if (this.tablePlacedByTask
            && this.craftingTablePos != null
            && mod.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE)) {
         return this.craftingTablePos;
      }
      return null;
   }

   private boolean shouldSkipInventoryStep(PlayerEngineController mod, CraftMacroStep step) {
      return step.kind() == CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY && this.craftMacroTableReachable(mod);
   }

   /**
    * COLLECT could not dispatch a gather for a still-owed external (no catalogue task exists for its
    * name — e.g. a modded raw variant with no gather entry). One-truth: there is no second resolve to
    * escape into; terminate bounded, NAMING the undispatchable external so the true blocker reaches
    * both the player and the model (DESIGN.md §3).
    */
   private Task advanceAfterCollectFail(ItemTarget undispatchable) {
      this.terminateMacro("Cannot finish crafting " + this.outputName() + ": no gather task can obtain "
            + undispatchable.getTargetCount() + " " + describeTarget(undispatchable) + ".");
      return null;
   }

   private Task runNextInventoryStep(PlayerEngineController mod) {
      // Operate on the FIRST unsatisfied resolved inventory step. Because the resolver recomputes the
      // remaining steps from live inventory each tick (deepest sub-crafts first), the list shrinks as
      // crafts land; a persisted index would skip a still-needed step. Driving off "first open" keeps
      // the macro converging with the live deficit. inventoryStepIndex is kept only as a debug marker.
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      this.inventoryStepIndex = 0;
      while (this.inventoryStepIndex < steps.size()) {
         CraftMacroStep step = steps.get(this.inventoryStepIndex);
         if (this.inventoryStepSatisfied(mod, step)) {
            this.inventoryStepIndex++;
            this.delayActive = false;
            continue;
         }
         this.setDebugState("Crafting " + step.debugLabel());
         if (!this.delayActive) {
            this.craftDelay.setInterval(mod.getModSettings().getCraftDelaySeconds());
            this.craftDelay.reset();
            this.delayActive = true;
            return null;
         }
         if (!this.craftDelay.elapsed()) {
            return null;
         }
         this.delayActive = false;
         int completed = CraftingInventoryOps.performCrafts(mod, step.recipeTarget(), step.craftsNeeded());
         if (completed > 0 || this.inventoryStepSatisfied(mod, step)) {
            this.resetCraftFailTracker();
            this.inventoryStepIndex++;
         } else if (this.recordCraftFailAndShouldTerminate(mod, step)) {
            this.terminateMacro(unsatisfiableStepReason(step));
         } else {
            this.beginIngredientRecovery(mod);
         }
         return null;
      }
      return null;
   }

   /**
    * Record one FAILED craft attempt for {@code step} (performCrafts returned 0 and the step is still
    * unsatisfied) and report whether the step has now failed {@link #MAX_CRAFT_FAIL_ATTEMPTS} times in a
    * row while flat (its output count never rose and it stayed the first-open step). This is the
    * ATTEMPT-based companion to {@link #inventoryStepStuck}: the tick-based stall counter is suppressed
    * during the craft-retry cycle (delayActive is true for most of every craftDelay), so a sub-craft
    * whose inputs can never be obtained (e.g. a variant-LOCKED {@code oak_planks} step when only the
    * wrong wood variant is collectable) would ping-pong CRAFT_2X2_INTERMEDIATES <-> beginIngredientRecovery
    * forever without it. Resets on real progress (output rose, or the open step changed).
    */
   private boolean recordCraftFailAndShouldTerminate(PlayerEngineController mod, CraftMacroStep step) {
      String sig = step.kind() + ":" + step.recipeTarget().getOutputItem().getDescriptionId();
      int held = this.inventoryStepOutputHeld(mod, step);
      if (!sig.equals(this.craftFailStepSig) || held > this.craftFailOutputHeld) {
         // Different step, or this step's output actually rose since last time -> real progress; restart.
         this.craftFailStepSig = sig;
         this.craftFailOutputHeld = held;
         this.craftFailAttempts = 1;
         return false;
      }
      this.craftFailAttempts++;
      return this.craftFailAttempts >= MAX_CRAFT_FAIL_ATTEMPTS;
   }

   /** Clear the failed-craft-attempt tracker; called whenever an inventory craft actually lands. */
   private void resetCraftFailTracker() {
      this.craftFailStepSig = null;
      this.craftFailOutputHeld = -1;
      this.craftFailAttempts = 0;
   }

   /**
    * Human-meaningful, variant-NAMING reason for a structurally unsatisfiable inventory craft step
    * (reaches player + model per DESIGN.md §3). Names the specific output that cannot be crafted (e.g.
    * "Cannot obtain oak_planks: no oak logs reachable") so the AI can answer the user truthfully rather
    * than report a generic stall.
    */
   private String unsatisfiableStepReason(CraftMacroStep step) {
      String output = step.recipeTarget().getOutputItem().getDescription().getString();
      return "Cannot finish crafting " + this.outputName()
            + ": unable to craft " + output + " (required ingredients are not obtainable).";
   }

   private boolean inventoryStepSatisfied(PlayerEngineController mod, CraftMacroStep step) {
      if (step.kind() == CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY) {
         if (this.craftMacroTableReachable(mod)) {
            return true;
         }
         if (mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) >= 1) {
            return true;
         }
      }
      int need = step.recipeTarget().getTargetCount();
      if (step.outputMatches() != null && step.outputMatches().length > 0) {
         // Pooled satisfaction (chest plan): any item in the match set counts (e.g. mixed planks).
         return mod.getItemStorage().getItemCount(step.outputMatches()) >= need;
      }
      Item output = step.recipeTarget().getOutputItem();
      return mod.getItemStorage().getItemCount(output) >= need;
   }

   private void replan(PlayerEngineController mod) {
      // Refresh the STRUCTURAL seed (output identity, requiresCraftingTable, final table recipe). The
      // per-tick remaining WORK is supplied by the resolver, not by the seed's (empty) step list.
      // Replan against the PINNED concrete output, never the original (possibly multi-match) request:
      // re-running the bare-"sign" species pick against live inventory could flip the species mid-run
      // (see pinnedOutputItem). An already-concrete request passes through unchanged.
      ItemTarget request = this.plan.requestedOutput();
      if (this.pinnedOutputItem != null && request.getMatches().length != 1) {
         request = new ItemTarget(this.pinnedOutputItem, request.getTargetCount());
      }
      CraftMacroPlanner.plan(mod, request).ifPresent(p -> this.plan = p);
   }

   /**
    * Recompute the remaining work from LIVE inventory and run the WS3 convergence guard. Caches the
    * result in {@link #lastResult}. Returns {@code true} iff the macro was terminated this call (the
    * caller must then stop touching the macro). UNOBTAINABLE, the no-progress gate, and the hard
    * attempt-cap backstop ALL route to the existing {@link #terminateMacro} — never a new self-stop
    * path. The no-progress increment is gated on "tiered deficit unchanged AND no external acquisition
    * is currently in flight" so a long-but-progressing gather can never prematurely terminate.
    */
   private boolean resolveNow(PlayerEngineController mod) {
      // Collapse FIRST (arms 1+2): a satisfied table requirement leaves the set before the resolve, so
      // its reservations are released structurally — they only ever existed inside the pass below, and
      // the next pass simply reserves nothing for the table.
      this.collapseSatisfiedRequirements(mod);
      // ONE owed-truth: a single shared-reservation resolveBudget pass over the live requirement set
      // (output first; the on-demand crafting-table entry while active). Every consumer this tick —
      // COLLECT dispatch, phase entry/exit, externalMaterialsMet, drop/no-progress guards, inventory
      // steps — reads THIS result; no second resolve path exists in the executor, so two decisions can
      // never disagree on "what is still needed". The convergence accounting below therefore tracks the
      // unified requirement-set deficit (a requirement ADD raises it, which never increments the
      // no-progress counter; only a flat deficit with nothing pending does).
      this.lastResult = this.resolver.resolveBudget(mod, this.requirements);
      this.resolveAttempts++;

      if (this.lastResult.status() == ResolverResult.Status.UNOBTAINABLE) {
         this.terminateMacro(meaningfulReason(this.lastResult));
         return true;
      }

      int deficit = this.lastResult.remainingDeficit();
      // The gather-in-flight signal reads the SAME one-truth externals the COLLECT dispatcher acts on,
      // so a legitimately-owed acquisition (including the table requirement's materials) always
      // registers here — the no-progress guard can never terminate "no further progress" while the
      // dispatcher still has an owed, obtainable gather.
      boolean acquisitionInFlight = this.phase == CraftMacroPhase.COLLECT_MISSING_MATERIALS
            && !this.resolvedExternals().isEmpty();
      // Keep the macro alive whenever a real, OBTAINABLE external acquisition is still owed for this
      // macro, regardless of which phase resolveNow is called from (PLAN / CRAFT_2X2_INTERMEDIATES /
      // COLLECT all invoke it). "Obtainable" is judged by the SAME reachability/drop probes the
      // COLLECT-stall backstop uses: a nearby reachable source or a matching drop in flight. This is
      // what makes the gate safe — an UNOBTAINABLE external (no wood anywhere) leaves both probes
      // false, so this term stays false and the no-progress / collect-stall / attempt-cap terminations
      // engage exactly as before (a genuinely impossible craft still stops with a meaningful reason).
      // It only suppresses termination while there is genuinely something obtainable left to gather.
      List<ItemTarget> owedExternals = this.resolvedExternals();
      boolean obtainableGatherOwed = !owedExternals.isEmpty()
            && (this.reachableSourceForAnyExternal(mod, owedExternals) || this.matchingDropsInFlight(mod));
      // BUG 1 fix: SEPARATE "resolve for decisions" (this method must still run every tick to drive step
      // selection) from "convergence accounting" (only count a no-progress event when an action was
      // actually attempted and the inventory did NOT move as a result). resolveNow is called every tick,
      // including the ~10 ticks a craft is waiting out craftDelay; during those waiting ticks inventory is
      // flat and the deficit is unchanged, but that is NOT a stall — a craft/gather is genuinely pending.
      // So suppress the no-progress increment whenever ANY action is pending/in-progress:
      //   (a) a craft delay is currently active (waiting out craftDelay before a 2x2 craft), OR
      //   (b) an external acquisition is in flight, OR
      //   (c) there are still unsatisfied inventory sub-craft steps actively being worked.
      // Only a genuine stall — deficit unchanged AND nothing pending/in-progress — increments the counter.
      //
      // WEDGE FIX: the unsatisfiable-step suppression. An inventory step that can NEVER be satisfied (the
      // plan demands a wood variant whose logs are not obtainable) keeps needsMoreInventorySteps(mod)
      // permanently true, which previously kept craftOrGatherPending permanently true and suppressed BOTH
      // termination paths forever (the bot spun ~47s in CRAFT_2X2_INTERMEDIATES "Crafting oak_planks" until
      // disconnect). inventoryStepStuck(...) detects a first-open step that has stayed open + output-flat
      // while NOTHING was advancing it (no craft delay, no gather) for MAX_INV_STALL_TICKS, and once stuck
      // we no longer count it as "pending" — so the genuine-stall increment below, and the
      // no-progress / attempt-cap terminateMacro paths, can finally engage with a human-meaningful reason.
      // It resets on any real progress (first-open step changes, or its output rises), so a legitimately
      // progressing multi-craft build is never cut off.
      boolean invStuck = this.inventoryStepStuck(mod, acquisitionInFlight);
      // DEFECT 2 (defensive): while the FSM is actively working the table-bound FINAL craft, the tiered
      // deficit stays flat at 1 (the lone CRAFT_OUTPUT_IN_TABLE step) from the moment 2x2 sub-crafts +
      // gathers are done until the chest actually lands at the table — across the FIND_OR_PLACE_TABLE ->
      // MOVE_TO_TABLE -> LOOK_AT_TABLE -> CRAFT_3X3_OUTPUT approach. That flat deficit is NOT a stall: a
      // legitimately-progressing table approach/craft is in flight. resolveNow is reachable in this window
      // (FIND_OR_PLACE_TABLE fall-through -> beginIngredientRecovery; CRAFT_3X3_OUTPUT's "Missing
      // ingredients; recovering" and failed-performSingleCraft -> beginIngredientRecovery), where without
      // this term the no-progress counter could accrue and terminate a valid table craft. It is NOT
      // reachable in MOVE_TO_TABLE / LOOK_AT_TABLE today (those phases call no resolve), but the guard
      // covers all four table phases defensively so a future call site cannot reintroduce the wedge.
      // Bounded termination is NOT weakened: this only holds for an output that has a final table recipe,
      // is still short, and STILL HAS a CRAFT_OUTPUT_IN_TABLE step in the live resolve. A genuinely
      // impossible craft still terminates via UNOBTAINABLE (resolveNow above), the failed-craft-attempt cap
      // (CRAFT_3X3_OUTPUT -> performSingleCraft fail -> beginIngredientRecovery -> ... -> terminateMacro),
      // and the collect-stall path; none of those run inside this method during a healthy table approach.
      boolean tableCraftInProgress = this.tableCraftInProgress();
      boolean craftOrGatherPending =
            this.delayActive
            || acquisitionInFlight
            || obtainableGatherOwed
            || tableCraftInProgress
            || (this.needsMoreInventorySteps(mod) && !invStuck);
      if (deficit < this.lastDeficit) {
         // Any productive tier shrank (a sub-craft or acquisition landed): real progress, reset.
         this.noProgressCount = 0;
      } else if (deficit == this.lastDeficit && !craftOrGatherPending) {
         // Deficit flat and NOTHING is pending/in-progress -> a genuinely stalled recompute loop.
         this.noProgressCount++;
      }
      this.lastDeficit = deficit;

      if (this.noProgressCount >= MAX_NO_PROGRESS_RESOLVES && !craftOrGatherPending) {
         this.terminateMacro(meaningfulReason(this.lastResult));
         return true;
      }
      // Hard backstop against deficit OSCILLATION (a deficit that ping-pongs and so never trips the
      // no-progress gate). Only fires while not making progress and nothing is pending/in-progress, so a
      // slow-but-progressing build (whose deficit keeps shrinking / resets noProgressCount, or which has a
      // craft/gather pending) is never cut off mid-build.
      if (this.resolveAttempts >= MAX_RESOLVE_ATTEMPTS && this.noProgressCount > 0 && !craftOrGatherPending) {
         this.terminateMacro(meaningfulReason(this.lastResult));
         return true;
      }
      return false;
   }

   /** A human-meaningful failure reason (reaches player + model per DESIGN.md §3); never blank. */
   private String meaningfulReason(ResolverResult result) {
      if (result != null && result.failureReason() != null && !result.failureReason().isBlank()) {
         return result.failureReason();
      }
      return "Cannot finish crafting " + this.outputName()
            + ": no further progress toward the craft from current materials.";
   }

   /** The latest resolved remaining steps, or an empty list before the first resolve. */
   private List<CraftMacroStep> resolvedSteps() {
      return this.lastResult == null ? List.of() : this.lastResult.remainingSteps();
   }

   /**
    * The one owed-truth externals list: {@code lastResult.externalNeeded()} from the per-tick
    * {@link MaterialResolver#resolveBudget} pass over the live requirement set (output first, plus the
    * on-demand crafting-table entry while active). Every executor decision — COLLECT dispatch, phase
    * entry/exit, {@link #externalMaterialsMet}, the drop and no-progress guards — reads this same list,
    * so no two code paths can disagree on "what is still needed". Empty before the first resolve.
    */
   private List<ItemTarget> resolvedExternals() {
      return this.lastResult == null ? List.of() : this.lastResult.externalNeeded();
   }

   /**
    * WS3 model-channel observation: if the live gather subtree (this macro's running sub-chain) contains a
    * {@link MineAndCollectTask} that has latched a terminal tool-acquisition failure, return its machine
    * reason so the COLLECT loop can {@code terminateMacro(reason)} and {@code getFailureReason()} surfaces it
    * to the model via {@code GetCommand.onGetComplete}. Returns {@code null} when no such failure is present.
    * The gather already delivered the human chat line and stopped mining; this only adds the missing MODEL
    * channel. Walks the {@link Task#thisOrChildSatisfies} sub-chain (the framework keeps the running gather
    * as this macro's sub across ticks).
    */
   private String dispatchedGatherToolFailureReason() {
      String[] holder = new String[1];
      this.thisOrChildSatisfies(t -> {
         if (t instanceof MineAndCollectTask gather && gather.toolAcquisitionFailed()) {
            holder[0] = gather.toolAcquisitionMachineReason()
               .orElse("could_not_acquire_tool");
            return true;
         }
         return false;
      });
      return holder[0];
   }

   @Override
   protected void onResourceStop(PlayerEngineController mod, Task interruptTask) {
      this.craftingTablePos = null;
      this.pendingTablePlaceTask = null;
      this.tablePlacedByTask = false;
      this.tableApproachStallTicks = 0;
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CraftMacroResourceTask task && task.plan.outputItem().equals(this.plan.outputItem());
   }

   @Override
   protected String toDebugStringName() {
      return "CraftMacro: " + this.plan.outputItem().getDescription().getString() + " (" + this.phase + ")";
   }

   @Override
   public String describeProgress() {
      String table = this.craftingTablePos != null ? this.craftingTablePos.toShortString() : "none";
      return "phase=" + this.phase + " target=" + ItemHelper.stripItemName(this.plan.outputItem()) + " table=" + table;
   }

   public CraftMacroPhase getPhase() {
      return this.phase;
   }

   /** Terminal failure reason recorded by {@link #terminateMacro}, or {@code null} if none. */
   public String getFailureReason() {
      return this.failureReason;
   }

   /**
    * Creation-time degradation note (today: the explicit-sign species substitution from
    * {@code CraftMacroSupport.adjustSignRequestForFunding}), set ONCE by
    * {@code CraftMacroTasks.tryCreateMacroTask} before the task runs and NEVER mid-run. Deliberately
    * separate from {@link #failureReason} (a non-null failureReason halts the task at tick); this is
    * informational only. The player already got the chat line at creation; the model receives it via
    * {@code GetCommand.onGetComplete} merging it into the command-completion feedback (DESIGN.md S3
    * dual-audience reporting).
    */
   private String creationNote;

   public void setCreationNote(String note) {
      this.creationNote = note;
   }

   /** Creation-time degradation note for the model's completion feedback, or {@code null} if none. */
   public String getCreationNote() {
      return this.creationNote;
   }

   public BlockPos getCraftingTablePos() {
      return this.craftingTablePos;
   }
}
