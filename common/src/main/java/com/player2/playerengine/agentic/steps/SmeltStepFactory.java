package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.tasks.agentic.FuelGatherParams;
import com.player2.playerengine.tasks.agentic.FuelGatherTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.cooking.FuelPlanner;
import com.player2.playerengine.tasks.cooking.FuelPlanner.DeficitCandidate;
import com.player2.playerengine.tasks.cooking.FuelPlanner.FuelPlan;
import com.player2.playerengine.tasks.cooking.SmeltDeferredParams;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask.Outcome;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask.OutcomeKind;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookKind;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookResolution;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Builds the deferred-smelt task from a validated {@code smelt_items} step spec — the agentic
 * trigger surface (WS9). Reuses the SAME {@link SmeltDeferredTask} the standalone {@code smelt}
 * command uses; only the model-facing reporting sink differs (WS8): the command path uses
 * {@code finishWith*}, the agentic path routes the SAME terminal/degradation outcome classes
 * through the run-state smelt slot ({@link AgenticRunState#setSmeltDegraded}) so
 * {@code AgenticDegradationSummary.forModel} surfaces them — including a factual clean-success
 * clause — into the model's plan-completion feedback (DESIGN.md §3).
 *
 * <p>Arg parsing mirrors {@link DepositItemsStepFactory}: case/underscore-insensitive lookup,
 * lenient int parsing, null-safe args. Args: {@code item} (required, registry id or bare name),
 * {@code count} (optional, default 1, clamped to {@code deferredSmeltMaxBatch}), {@code kind}
 * (optional: smelting/blasting/smoking; default "any").
 */
public final class SmeltStepFactory implements AgenticStepFactory {

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();

        Item input = resolveItem(lookup(args, "item"));
        if (input == null || input == Items.AIR) {
            // No usable item arg — surface as a skipped smelt so the model learns the cause, then
            // refuse to build a task (the executor treats an empty Optional as an unknown/failed step).
            AgenticRunState run = context.runState();
            if (run != null) {
                run.setSmeltDegraded(DegradationLevel.SKIPPED, "no_input");
            }
            return Optional.empty();
        }

        int maxBatch = settings.getDeferredSmeltMaxBatch();
        int count = Math.max(1, Math.min(maxBatch, parseInt(args, "count", 1)));
        CookKind kind = parseKind(lookup(args, "kind"));

        // Reservation-aware count clamp (WS2 Fix B): never plan to smelt more input than is genuinely
        // free after sibling-step reservations. ledger.free is the BROAD read (matches the smelt-input
        // scope) minus reserved; null ledger (no run) is a strict no-op via the convenience accessor.
        MaterialReservationService ledger = context.reservations();
        int free = ledger != null ? ledger.free(context.controller(), input) : count;
        int clampedCount = Math.min(count, free);
        if (clampedCount < 1) {
            // Every unit is reserved for an earlier/later step — refuse to build a task and surface the
            // cause to BOTH audiences: the run-state smelt slot (model channel) AND a player chat line.
            AgenticRunState run = context.runState();
            if (run != null) {
                run.setSmeltDegraded(DegradationLevel.SKIPPED, "no_input_reserved");
            }
            if (run == null || !run.isTerminal()) {
                context.controller().reportAgenticProgress(
                        "Couldn't smelt " + itemName(input) + " — all of it is reserved for another step.",
                        true);
            }
            return Optional.empty();
        }

        return Optional.of(new AgenticSmeltTask(input, clampedCount, kind, ledger, context));
    }

    /** Builds the immutable smelt params for a (possibly clamped) batch count. */
    private static SmeltDeferredParams buildParams(Item input, int count, CookKind kind,
                                                   PlayerEngineSettings settings) {
        return new SmeltDeferredParams.Builder(input, Math.max(1, count))
                .preferredKind(kind)
                .stallPolls(settings.getDeferredSmeltStallPolls())
                .timeoutSeconds(settings.getDeferredSmeltTimeoutSeconds())
                .build();
    }

    // -----------------------------------------------------------------------------------------
    // Agentic wrapper: delegates to the shared SmeltDeferredTask, then records its single Outcome
    // onto the run-state smelt slot (model channel) and fires the shared player line (player
    // channel). Hard failures self-stop with finished=false so SingleTaskChain records FAILED;
    // degraded successes finish=true so the plan advances with the degradation noted.
    // -----------------------------------------------------------------------------------------

    private static final class AgenticSmeltTask extends Task {

        /** Stateless cooking-recipe resolver, reused to derive cookTicks for the fuel pre-gather. */
        private static final CookingRecipeAccess COOKING_RECIPE_ACCESS = new CookingRecipeAccessImpl();

        private final Item input;
        private final int requestedCount;
        private final CookKind kind;
        private final MaterialReservationService ledger;
        private final AgenticExecutionContext context;

        /** Constructed lazily after the (optional) fuel pre-gather, possibly at a clamped count. */
        private SmeltDeferredTask inner;

        // ---- fuel pre-gather state (single-attempt latch) ----
        private Task fuelGatherChild;
        private boolean fuelGatherAttempted;
        private int clampedSmeltCount = -1; // -1 = unset; set to the (possibly reduced) batch count
        /** Set when the post-gather plan re-run found NO usable fuel at all → SKIPPED, no inner. */
        private boolean fuelGatherNoAcquirable;

        private boolean finished;
        private boolean recorded;

        AgenticSmeltTask(Item input, int requestedCount, CookKind kind,
                         MaterialReservationService ledger, AgenticExecutionContext context) {
            this.input = input;
            this.requestedCount = Math.max(1, requestedCount);
            this.kind = kind;
            this.ledger = ledger;
            this.context = context;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        protected void onStart() {
            setDebugState("deferred smelt (agentic)");
        }

        @Override
        protected Task onTick() {
            if (finished || recorded) {
                return null;
            }

            // ---- PRE-GATHER (runs once, before any inner delegation) ----
            // Only the agentic path (runState != null) attempts a fuel gather; the standalone smelt
            // command never enters this wrapper, so its SmeltDeferredTask + NO_FUEL path is untouched.
            // Re-enter while NOT YET attempted (to start the gather) OR while a gather child is still in
            // flight (the latch is set before the child is driven, so the in-flight child must keep
            // ticking across ticks until terminal). Once the latch is set AND no child remains, the
            // gather is fully resolved and we fall through to the inner/skip decision.
            if (context.runState() != null && (!fuelGatherAttempted || fuelGatherChild != null)) {
                Task pre = drivePreGather();
                if (pre != null) {
                    return pre; // gather child still running, or this tick advanced the gather
                }
                // drivePreGather returns null when the gather is fully resolved (latch set). Fall through.
            }

            // ZERO outcome: the gather found no usable fuel → record SKIPPED without spawning inner.
            if (fuelGatherNoAcquirable && !recorded) {
                recordNoAcquirable();
                return null;
            }

            // Ensure an inner task exists. When the pre-gather did not run (e.g. runState null, which
            // cannot happen here, or the latch resolved to a full/clamped batch), build it now.
            if (inner == null) {
                int count = clampedSmeltCount > 0 ? clampedSmeltCount : requestedCount;
                inner = new SmeltDeferredTask(
                        buildParams(input, count, kind, context.settings()), ledger);
            }

            if (!inner.isFinished()) {
                // Delegate every tick to the shared task until it reaches a terminal outcome.
                return inner;
            }
            recordOutcome(inner.outcome(), inner.loadShortfall());
            return null;
        }

        // -------------------------------------------------------------------------------------
        // Fuel pre-gather (single attempt). Returns a child Task while the gather runs; returns
        // null once the gather is fully resolved (latch set and the clamp/skip decided).
        // -------------------------------------------------------------------------------------

        private Task drivePreGather() {
            // A gather child is in flight — drive it to terminal, then re-plan ONCE.
            if (fuelGatherChild != null) {
                if (!fuelGatherChild.isFinished() && !fuelGatherChild.stopped()) {
                    return fuelGatherChild;
                }
                fuelGatherChild = null;
                resolveAfterGather();
                return null; // latch already set true; the next onTick proceeds to inner / skip
            }

            // First entry: resolve cookTicks, run plan() for the full batch. If already satisfiable,
            // no gather is needed (latch set, full batch).
            int cookTicks = resolveCookTicks();
            if (cookTicks < 1) {
                // No recipe / no world yet: let the inner task surface NO_RECIPE/SETUP_FAILED truthfully.
                fuelGatherAttempted = true;
                clampedSmeltCount = requestedCount;
                return null;
            }

            Optional<FuelPlan> full = new FuelPlanner()
                    .plan(requestedCount, cookTicks, context.controller(), peekLedger());
            if (full.isPresent()) {
                // Already enough fuel for the full batch — skip the gather. peekLedger() is null here,
                // so this pre-check plan() reserves NOTHING; the inner SmeltDeferredTask performs the
                // single authoritative fuel reservation (with the real ledger) on its own plan() re-run.
                fuelGatherAttempted = true;
                clampedSmeltCount = requestedCount;
                return null;
            }

            // Need fuel: find the single acquirable item + deficit (reserves nothing).
            Optional<DeficitCandidate> deficit = new FuelPlanner()
                    .deficitFor(requestedCount, cookTicks, context.controller(), peekLedger());
            if (deficit.isEmpty()) {
                // No acquirable fuel at all → ZERO.
                fuelGatherAttempted = true;
                fuelGatherNoAcquirable = true;
                return null;
            }

            // Drive a single bounded FuelGatherTask for exactly the deficit.
            DeficitCandidate c = deficit.get();
            fuelGatherAttempted = true; // latch BEFORE driving — never reset within this instance
            FuelGatherParams gp = new FuelGatherParams(
                    c.item(), c.deficit(), 32.0, 60.0, 1, 1);
            fuelGatherChild = new FuelGatherTask(gp, context.runState(), ledger);
            context.controller().log("[Agentic] smelt: pre-gathering fuel "
                    + BuiltInRegistries.ITEM.getKey(c.item()) + " deficit=" + c.deficit());
            return fuelGatherChild;
        }

        /**
         * After the single gather attempt, re-run FuelPlanner.plan ONCE for the full batch. If it now
         * succeeds → CLEAN path (full count). If still empty but SOME target fuel is now held →
         * compute the fuel-coverable clamped batch (PARTIAL). If zero coverable → ZERO (SKIPPED).
         */
        private void resolveAfterGather() {
            int cookTicks = resolveCookTicks();
            if (cookTicks < 1) {
                clampedSmeltCount = requestedCount; // let inner surface the recipe failure
                return;
            }
            Optional<FuelPlan> full = new FuelPlanner()
                    .plan(requestedCount, cookTicks, context.controller(), peekLedger());
            if (full.isPresent()) {
                clampedSmeltCount = requestedCount; // FULL — gather covered the whole batch
                return;
            }
            // Partial: compute the largest batch the now-held fuel can ACTUALLY cover. This must match
            // what the inner task's FuelPlanner.plan() can commit — and plan() selects ONE fuel species
            // per batch, never a mixed pool. A summed-burn heuristic (1 coal + 1 charcoal = 3200 ticks)
            // would over-clamp to a count plan() cannot satisfy with any single type, so the inner task
            // would then terminate PARTIAL_OUT_OF_FUEL and the model would hear "ran out of fuel
            // mid-batch" — hiding the pre-gather effort (DESIGN §3). Instead descend the batch count and
            // re-plan until plan() returns present: authoritative by construction (same single-species
            // selection the inner task will use). peekLedger() is null, so these probe plans reserve
            // nothing.
            int coverable = maxFuelCoverableCount(cookTicks);
            if (coverable >= 1) {
                clampedSmeltCount = coverable; // PARTIAL — inner runs the reduced batch
            } else {
                fuelGatherNoAcquirable = true; // ZERO — nothing usable was gathered
            }
        }

        /**
         * Largest batch the now-held fuel can ACTUALLY cook with a SINGLE fuel species — the same
         * selection {@link FuelPlanner#plan} makes (it never mixes fuel types within one batch). Found
         * by descending the batch count from {@code requestedCount} and re-running {@code plan()} until
         * it returns present; the first count that plans is the authoritative coverable batch.
         *
         * <p>Deliberately NOT a summed-across-types burn heuristic: summing the burn ticks of every held
         * fuel (e.g. 1 coal + 1 charcoal) would report a coverage that no single fuel species can deliver,
         * over-clamping the inner batch to a count {@code plan()} cannot satisfy. The descent is bounded:
         * at most {@code requestedCount} probe plans, each O(candidate-set). The probe plans pass a null
         * ledger ({@link #peekLedger}) so they reserve nothing — the inner task performs the single
         * authoritative reservation on the chosen reduced batch.
         */
        private int maxFuelCoverableCount(int cookTicks) {
            if (cookTicks < 1) {
                return 0;
            }
            FuelPlanner planner = new FuelPlanner();
            for (int count = requestedCount - 1; count >= 1; count--) {
                if (planner.plan(count, cookTicks, context.controller(), peekLedger()).isPresent()) {
                    return count; // largest single-species-coverable batch
                }
            }
            return 0;
        }

        /** Resolves the per-item cook ticks for the input recipe, or 0 when unresolvable. */
        private int resolveCookTicks() {
            ServerLevel world = context.controller().getWorld();
            if (world == null) {
                return 0;
            }
            RecipeManager mgr = world.getRecipeManager();
            RegistryAccess registries = world.registryAccess();
            Optional<CookResolution> resolved = (kind != null)
                    ? COOKING_RECIPE_ACCESS.resolve(mgr, input, kind, registries)
                    : COOKING_RECIPE_ACCESS.resolveAny(mgr, input, registries);
            return resolved.map(CookResolution::cookTicks).orElse(0);
        }

        /**
         * The ledger to consult during the pre-gather plan checks. We pass {@code null} so the pre-check
         * plan() does NOT reserve fuel (the gather reserves nothing and the inner task's own plan()
         * performs the single authoritative reservation on the reduced/full batch). Reading free-of-
         * reservation is unnecessary here because the wrapper runs before the inner draws anything.
         */
        private MaterialReservationService peekLedger() {
            return null;
        }

        /** Records the ZERO (no-acquirable-fuel) outcome: SKIPPED to both audiences; no inner spawned. */
        private void recordNoAcquirable() {
            recorded = true;
            AgenticRunState run = context.runState();
            if (run != null) {
                run.setSmeltDegraded(DegradationLevel.SKIPPED, "fuel_gather no_acquirable_fuel");
                run.setSmeltProgress("smelted=0 requested=" + requestedCount + " item="
                        + BuiltInRegistries.ITEM.getKey(input) + " reason=fuel_gather_no_acquirable");
            }
            if (run == null || !run.isTerminal()) {
                context.controller().reportAgenticProgress(
                        "Tried to gather fuel automatically but couldn't get any — smelted 0 "
                                + itemName(input) + ".",
                        true);
            }
            // SKIPPED is a soft outcome (the plan advances with the degradation noted), mirroring the
            // existing furnace_in_use / no_fuel SKIPPED handling which finishes the step.
            finished = true;
        }

        @Override
        protected void onStop(Task interruptTask) {
            // If the wrapper is interrupted before the inner task recorded a terminal outcome, leave
            // the run-state slot untouched (a lifecycle stop is not a smelt degradation). The inner
            // task's own onStop is invoked by the chain via its child reference.
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof AgenticSmeltTask t
                    && t.input == this.input
                    && t.requestedCount == this.requestedCount;
        }

        @Override
        protected String toDebugString() {
            return "AgenticSmelt";
        }

        /**
         * Maps the shared {@link Outcome} to the run-state smelt slot + the shared player line.
         *
         * @param loadShortfall how many input items short of the request the bot actually loaded
         *                      (≥ 0; non-zero when fuel == input ate the pool or a reservation clamped
         *                      it). A truthful-clamp note for BOTH audiences (DESIGN §3) even when the
         *                      smaller batch then cooks cleanly.
         */
        private void recordOutcome(Outcome o, int loadShortfall) {
            recorded = true;
            AgenticRunState run = context.runState();
            if (o == null) {
                // No outcome recorded (should not happen once inner.isFinished()): treat as a
                // skipped smelt and finish so the plan does not hang.
                if (run != null) {
                    run.setSmeltDegraded(DegradationLevel.SKIPPED, "no_outcome");
                }
                finished = true;
                return;
            }

            // Player channel (shared with the command path): fire the concise human line as a
            // milestone, guarded by the run-state terminal flag so a late callback cannot overwrite a
            // terminal failure line.
            //
            // BUT skip this generic line when a downgrade branch below (input-shortfall or fuel_gather
            // PARTIAL) is about to fire its own complete, contextual message. Otherwise the player would
            // get two back-to-back lines: a "Smelted N x output." that wrongly implies a clean full run,
            // immediately followed by the partial explanation. The downgrade branches own the sole player
            // line in that case (DESIGN §3: visible once, not contradicted).
            boolean willDowngrade = o.kind() == OutcomeKind.CLEAN_SUCCESS
                    && (loadShortfall > 0
                        || (clampedSmeltCount > 0 && clampedSmeltCount < requestedCount));
            if (!willDowngrade && (run == null || !run.isTerminal())) {
                context.controller().reportAgenticProgress(playerLine(o), true);
            }

            // Truthfulness (DESIGN §3): an input-shortfall-after-fuel-removal (fuel == input ate the
            // pool, or a reservation clamped the load) means fewer items were loaded than requested.
            // Even if the smaller batch then cooked cleanly, the model must not hear an unqualified
            // "smelted N". Surface the shortfall to BOTH audiences and downgrade the model slot to a
            // PARTIAL with a specific reason, so AgenticDegradationSummary tells the user the truth.
            if (loadShortfall > 0 && o.kind() == OutcomeKind.CLEAN_SUCCESS) {
                int requested = o.collected() + loadShortfall;
                if (run == null || !run.isTerminal()) {
                    context.controller().reportAgenticProgress(
                            "Only smelted " + o.collected() + " of " + requested + " "
                                    + itemName(o.outputItem()) + " — couldn't load the rest (it was"
                                    + " also used as fuel or reserved).",
                            true);
                }
                if (run != null) {
                    run.setSmeltDegraded(DegradationLevel.PARTIAL,
                            "input_shortfall loaded=" + o.collected() + " of " + requested);
                    run.setSmeltProgress("smelted=" + o.collected() + " requested=" + requested
                            + " item=" + o.outputName() + " reason=input_shortfall");
                }
                finished = true;
                return;
            }

            // Fuel-gather PARTIAL: the pre-gather only acquired enough fuel for a reduced batch, so the
            // inner task ran a CLAMPED count. Even though that smaller batch cooked cleanly, the model
            // and player must hear the truth — fewer than requested were smelted because fuel was the
            // limit (DESIGN §3). Downgrade to a REAL PARTIAL with the distinctive 'fuel_gather' stem so
            // AgenticDegradationSummary.smeltClause renders the fuel-gather clause (checked first).
            if (clampedSmeltCount > 0 && clampedSmeltCount < requestedCount
                    && o.kind() == OutcomeKind.CLEAN_SUCCESS) {
                int collected = o.collected();
                if (run == null || !run.isTerminal()) {
                    context.controller().reportAgenticProgress(
                            "Auto-gathered fuel but only enough to smelt " + collected + " of "
                                    + requestedCount + " " + itemName(o.outputItem()) + ".",
                            true);
                }
                if (run != null) {
                    run.setSmeltDegraded(DegradationLevel.PARTIAL,
                            "fuel_gather collected=" + collected + " of " + requestedCount);
                    run.setSmeltProgress("smelted=" + collected + " requested=" + requestedCount
                            + " item=" + o.outputName() + " reason=fuel_gather");
                }
                finished = true;
                return;
            }

            switch (o.kind()) {
                case CLEAN_SUCCESS -> {
                    // Clean success: record ONLY the factual progress so AgenticDegradationSummary
                    // emits "smelted N <output>" (never confabulate a generic "finished running").
                    if (run != null) {
                        run.setSmeltProgress("smelted=" + o.collected() + " item=" + o.outputName());
                    }
                    finished = true;
                }
                case PARTIAL_OUT_OF_FUEL -> {
                    degraded(run, DegradationLevel.PARTIAL,
                            "partial out_of_fuel collected=" + o.collected() + " of " + o.expected(),
                            o, true);
                }
                case TAMPERED -> {
                    degraded(run, DegradationLevel.PARTIAL,
                            "tampered collected=" + o.collected() + " of " + o.expected(), o, true);
                }
                case STALLED_RETRYING -> {
                    // SOFT stall — the plan advances, but v1 has NO automatic resume, so record it as
                    // a PARTIAL that needs a re-run near the furnace (do NOT imply auto-finish).
                    degraded(run, DegradationLevel.PARTIAL,
                            "stalled_needs_rerun collected=" + o.collected() + " of " + o.expected(),
                            o, true);
                }
                case FURNACE_IN_USE -> degraded(run, DegradationLevel.SKIPPED, "furnace_in_use", o, false);
                case NO_FUEL -> degraded(run, DegradationLevel.SKIPPED, "no_fuel", o, false);
                case STALLED_TIMEOUT -> degraded(run, DegradationLevel.SKIPPED, "stalled_timeout", o, false);
                case FURNACE_GONE -> degraded(run, DegradationLevel.SKIPPED, "furnace_gone", o, false);
                case NO_RECIPE -> degraded(run, DegradationLevel.SKIPPED, "no_recipe", o, false);
                case SETUP_FAILED -> degraded(run, DegradationLevel.SKIPPED,
                        "setup_failed:" + o.reasonLabel(), o, false);
            }
        }

        /**
         * Records a degradation onto the run-state slot. {@code softSuccess=true} finishes the step
         * (the plan advances with the degradation noted); {@code false} is a hard failure that
         * self-stops with {@code finished=false} so the chain records FAILED.
         */
        private void degraded(AgenticRunState run, DegradationLevel level, String reason,
                              Outcome o, boolean softSuccess) {
            if (run != null) {
                run.setSmeltDegraded(level, reason);
                // Keep a factual progress note too (count) so progressForKind has the detail.
                run.setSmeltProgress("smelted=" + o.collected() + " item=" + o.outputName()
                        + " reason=" + reason);
            }
            if (softSuccess) {
                finished = true;
            } else {
                // Hard failure: self-stop so SingleTaskChain takes the forced-stop path and the
                // adapter records FAILED (mirrors DepositItemsTask.terminateFailed).
                finished = false;
                if (!this.stopped()) {
                    this.stop(this);
                }
            }
        }

        private static String playerLine(Outcome o) {
            String out = o.outputItem() != null
                    ? BuiltInRegistries.ITEM.getKey(o.outputItem()).getPath().replace('_', ' ')
                    : "that item";
            return switch (o.kind()) {
                case CLEAN_SUCCESS -> "Smelted " + o.collected() + " x " + out + ".";
                case PARTIAL_OUT_OF_FUEL -> "Smelted " + o.collected() + " of " + o.expected() + " x "
                        + out + " — ran out of fuel.";
                case NO_FUEL -> "Couldn't smelt — no fuel available (need about " + o.fuelNeeded() + ").";
                case TAMPERED -> "The furnace contents changed while I was away — collected what I could ("
                        + o.collected() + ").";
                case FURNACE_GONE -> "The furnace I was using is gone — couldn't finish.";
                case STALLED_RETRYING -> "The furnace chunk stopped ticking, so it can't finish on its own. I"
                        + " smelted " + o.collected() + " of " + o.expected() + " — ask me to smelt again near"
                        + " the furnace to finish the rest.";
                case STALLED_TIMEOUT -> "Gave up smelting — the furnace chunk wasn't ticking and the job timed out.";
                case FURNACE_IN_USE -> "That furnace already has something in it — I didn't load anything to"
                        + " avoid mixing batches.";
                case NO_RECIPE -> "I can't smelt that — it has no furnace, blast furnace, or smoker recipe.";
                case SETUP_FAILED -> "Couldn't start the smelt (" + o.reasonLabel() + ").";
            };
        }
    }

    // -----------------------------------------------------------------------------------------
    // Plan-time forward-reservation derivation (WS5)
    //
    // The chain executor pre-seeds the ledger at plan start with every not-yet-run step's
    // resolvable inputs, and releases each step's OWN forward reservation at its step-start.
    // Both operations need the SAME (input item, count) a smelt step will draw — so it is
    // derived here, reusing this factory's own arg parsing, rather than duplicated in the
    // executor (single source of truth for "what a smelt step reserves").
    //
    // Derivation mirrors {@link #createTask}: resolve the {@code item} arg, parse {@code count}
    // clamped to {@code deferredSmeltMaxBatch}. It deliberately does NOT apply the
    // reservation-aware clamp from createTask (that clamp is relative to the ledger AFTER the
    // pre-seed has populated it; the pre-seed reserves the requested input, and the service's
    // own reserve() clamps the grant to what is genuinely free). Fuel is NOT derived: fuel is
    // chosen at load time by FuelPlanner and is protected at consult time, never pre-seeded.
    // -----------------------------------------------------------------------------------------

    /** A smelt step's plan-time forward reservation: the input item and the count it will draw. */
    public record SmeltReservation(Item input, int count) {}

    /**
     * Derives the (input item, count) a {@code smelt_items} step would reserve at plan time, or
     * {@link Optional#empty()} when the input cannot be resolved (no usable {@code item} arg) — in
     * which case the executor SKIPS this step's pre-seed (no forward protection for that one step),
     * never crashing. Reuses this factory's own arg parsing so the pre-seed and the actual draw
     * never drift.
     */
    public static Optional<SmeltReservation> forwardReservation(AgenticStepSpec step,
                                                                AgenticExecutionContext context) {
        Map<String, String> args = step.args() != null ? step.args() : Map.of();
        Item input = resolveItem(lookup(args, "item"));
        if (input == null || input == Items.AIR) {
            return Optional.empty();
        }
        PlayerEngineSettings settings = context.settings();
        int maxBatch = settings != null ? settings.getDeferredSmeltMaxBatch() : 64;
        int count = Math.max(1, Math.min(maxBatch, parseInt(args, "count", 1)));
        return Optional.of(new SmeltReservation(input, count));
    }

    // -----------------------------------------------------------------------------------------
    // Arg parsing (mirrors DepositItemsStepFactory)
    // -----------------------------------------------------------------------------------------

    /** Human-friendly item name (spaces, no namespace) for player chat lines; {@code "that item"} if null. */
    private static String itemName(Item item) {
        return item != null
                ? BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ')
                : "that item";
    }

    private static Item resolveItem(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private static CookKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null; // "any" — resolver tries SMELTING -> BLASTING -> SMOKING
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "blast", "blasting", "blast_furnace" -> CookKind.BLASTING;
            case "smoke", "smoking", "smoker" -> CookKind.SMOKING;
            case "smelt", "smelting", "furnace" -> CookKind.SMELTING;
            default -> null;
        };
    }

    private static int parseInt(Map<String, String> args, String key, int def) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String lookup(Map<String, String> args, String key) {
        if (args == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        for (Map.Entry<String, String> e : args.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            if (k.equals(normalized)) {
                return e.getValue();
            }
        }
        return null;
    }
}
