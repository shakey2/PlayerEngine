package com.player2.playerengine.tasks.cooking;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.cooking.FurnaceStateReader.FurnaceState;
import com.player2.playerengine.tasks.cooking.FuelPlanner.FuelPlan;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookKind;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookResolution;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.tasks.deferred.DeferredDegradation;
import com.player2.playerengine.tasks.deferred.DeferredJobRecord;
import com.player2.playerengine.tasks.deferred.DeferredJobStore;
import com.player2.playerengine.tasks.deferred.DeferredProgress;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * v1 self-contained deferred smelting task: locate a furnace, walk to it, load input + fuel,
 * park-and-wait while the furnace cooks (polling the live BE every server tick, never a wall-clock
 * timer), park-and-resume if the bot wanders off and the chunk stops ticking, then collect the
 * output and finish.
 *
 * <p>Extends {@link Task} directly and uses the canonical parked idiom: while waiting for the cook,
 * {@link #onTick()} returns {@code null} and {@link #isFinished()} stays {@code false} (mirroring
 * {@code SleepThroughNightTask}/{@code WaitForDragonAndPearlTask}). It does NOT extend
 * {@code ResourceTask} and does NOT use a {@code TimerGame} — the furnace Block Entity is the sole
 * source of truth for completion (output count present + input drained), per the plan invariants.
 *
 * <h3>State machine (internal; invisible to {@code StepState})</h3>
 * <pre>
 *   LOCATE_FURNACE -> GO_TO_FURNACE -> LOAD -> WAIT_FOR_COOK -> COLLECT -> DONE
 *                                                              \-> DEGRADED (terminal)
 * </pre>
 * On resume (a job already persisted for this furnace), the furnace position is read straight from
 * the {@link DeferredJobRecord} — the scanner is NOT consulted, because it repopulates each tick and
 * drops the furnace once the bot roams out of the ~8-block scan radius.
 *
 * <h3>v1 scope (SETTLED)</h3>
 * Self-contained "load -> stand by -> collect". NO front-load / overlap "do other work while it
 * cooks" scheduling — that is a deferred follow-up (plan Decision 7). The "may wander within range"
 * flag is reserved ({@code allowYield}) and defaults off; v1 always stands by the furnace.
 *
 * <h3>Terminal outcome (dual-audience source of truth)</h3>
 * The single terminal {@link Outcome} (success + counts, or which {@link DeferredDegradation} +
 * counts) is exposed via {@link #outcome()} so BOTH trigger surfaces report from one shared source:
 * the standalone-command {@code onComplete} and the agentic {@code SmeltStepFactory}. This task does
 * NOT itself push any chat or model feedback — it only records the outcome. (Pushing an
 * {@code InfoMessage} during the wait would suppress the finish prompt; see plan WS8.)
 */
public final class SmeltDeferredTask extends Task implements DescribesProgress {

    /** Stateless cooking recipe resolver (mirrors the {@code RecipeAccessImpl} singleton pattern). */
    private static final CookingRecipeAccess COOKING_RECIPE_ACCESS = new CookingRecipeAccessImpl();

    /** Distance within which the bot is considered "at" the furnace for loading/collecting. */
    private static final double AT_FURNACE_RANGE = 4.5;

    // -------------------------------------------------------------------------
    // Internal state machine
    // -------------------------------------------------------------------------

    private enum Phase {
        LOCATE_FURNACE,
        GO_TO_FURNACE,
        LOAD,
        WAIT_FOR_COOK,
        COLLECT,
        DONE,
        DEGRADED
    }

    /** Terminal outcome classes, shared by both reporting surfaces. */
    public enum OutcomeKind {
        /** Output count == expected — full success. */
        CLEAN_SUCCESS,
        /** 0 < collected < expected, fuel ran out mid-batch. */
        PARTIAL_OUT_OF_FUEL,
        /** Nothing cooked: no fuel available to load at all. */
        NO_FUEL,
        /** Collected fewer than expected with no fuel/station explanation (contents changed). */
        TAMPERED,
        /** The furnace block was removed/replaced. */
        FURNACE_GONE,
        /** Soft stall (chunk not ticking) — degraded-success-with-retry. */
        STALLED_RETRYING,
        /** Hard real-time timeout — terminal give-up. */
        STALLED_TIMEOUT,
        /** The input item has no smelt/blast/smoke recipe. */
        NO_RECIPE,
        /** The target furnace already had items in its input/fuel slot — refused to load. */
        FURNACE_IN_USE,
        /** A precondition failed (no input items in inventory, no world, etc.). */
        SETUP_FAILED
    }

    /**
     * Immutable terminal outcome. Read by the command {@code onComplete} and the agentic step
     * factory via {@link #outcome()} to produce the dual-audience report.
     *
     * @param kind          the terminal classification.
     * @param outputItem    the resolved output item (may be null on NO_RECIPE / SETUP_FAILED).
     * @param collected     how many output items the bot actually collected.
     * @param expected      how many output items a clean run would have produced.
     * @param fuelItem      the fuel item chosen (may be null when none was available).
     * @param fuelNeeded    fuel units needed for the batch (for the NO_FUEL message).
     * @param furnacePos    the furnace position (may be null before LOAD).
     * @param reasonLabel   machine-readable reason token (matches {@link DeferredDegradation#label()}
     *                      where applicable; used for the model-facing note / agentic slot).
     */
    public record Outcome(
            OutcomeKind kind,
            @Nullable Item outputItem,
            int collected,
            int expected,
            @Nullable Item fuelItem,
            int fuelNeeded,
            @Nullable BlockPos furnacePos,
            String reasonLabel) {

        /** True only for {@link OutcomeKind#CLEAN_SUCCESS}. */
        public boolean isCleanSuccess() {
            return kind == OutcomeKind.CLEAN_SUCCESS;
        }

        /** Registry name of the output item (e.g. {@code minecraft:iron_ingot}) or {@code "?"}. */
        public String outputName() {
            return outputItem != null
                    ? BuiltInRegistries.ITEM.getKey(outputItem).toString()
                    : "?";
        }

        /** Registry name of the fuel item or {@code "?"}. */
        public String fuelName() {
            return fuelItem != null
                    ? BuiltInRegistries.ITEM.getKey(fuelItem).toString()
                    : "?";
        }
    }

    // -------------------------------------------------------------------------
    // Inputs / wiring
    // -------------------------------------------------------------------------

    private final SmeltDeferredParams params;
    @Nullable
    private final BlockPos resumeFurnacePos; // non-null when resuming a persisted job

    /**
     * Per-run material reservation ledger (WS2). {@code null} for the standalone {@code smelt}
     * command path (no agentic run) — every ledger access guards on null and is then a strict no-op,
     * so the command behaves exactly as before. When present, the input free read and FuelPlanner
     * fuel read subtract reservations, and the actually-loaded input is reserved on commit.
     */
    @Nullable
    private final MaterialReservationService ledger;

    /** The input item actually reserved on load, released on terminate (belt-and-suspenders, N1). */
    @Nullable
    private Item reservedInput;
    /** How much of {@link #reservedInput} this task reserved (to release exactly its own grant). */
    private int reservedInputCount = 0;

    /**
     * How many input items short of {@code params.count} the bot actually loaded — non-zero only when
     * fuel removal (fuel == input) or a reservation lowered the loadable input below the requested
     * count. Carried into the terminal {@link Outcome} so a clamped batch is truthful to both audiences
     * (DESIGN §3) even when the (smaller) batch then cooks cleanly.
     */
    private int loadShortfall = 0;

    private Phase phase = Phase.LOCATE_FURNACE;
    private boolean finished = false;

    // Resolved once during LOCATE_FURNACE.
    private BlockPos furnacePos;
    private CookResolution resolution;
    private CookKind chosenKind;
    private FuelPlan fuelPlan;

    // The active cook process (built at LOAD) and progress tracking.
    private DeferredCookProcess process;
    private FurnaceStateReader reader;
    private int collectedCount = 0;

    /** The terminal outcome, set exactly once when the task reaches a terminal phase. */
    @Nullable
    private Outcome outcome;

    /**
     * Constructs the task for a fresh job.
     *
     * @param params the immutable parameter bundle (input item, count, preferred kind, thresholds).
     */
    public SmeltDeferredTask(SmeltDeferredParams params) {
        this(params, null, null);
    }

    /**
     * Constructs the task with a reservation ledger but no resume position (the agentic fresh-job
     * path). The standalone {@code smelt} command uses the single-arg constructor (null ledger).
     *
     * @param params the parameter bundle.
     * @param ledger the per-run reservation ledger, or {@code null} outside an agentic run.
     */
    public SmeltDeferredTask(SmeltDeferredParams params, @Nullable MaterialReservationService ledger) {
        this(params, null, ledger);
    }

    /**
     * Constructs the task, optionally resuming a persisted job at a known furnace position.
     *
     * @param params           the parameter bundle.
     * @param resumeFurnacePos furnace position read from a persisted {@link DeferredJobRecord};
     *                         when non-null, LOCATE_FURNACE skips the scanner and uses this pos.
     * @param ledger           the per-run reservation ledger, or {@code null} outside an agentic run.
     */
    public SmeltDeferredTask(SmeltDeferredParams params, @Nullable BlockPos resumeFurnacePos,
                             @Nullable MaterialReservationService ledger) {
        this.params = params;
        this.resumeFurnacePos = resumeFurnacePos != null ? resumeFurnacePos.immutable() : null;
        this.ledger = ledger;
    }

    /** The terminal outcome, or {@code null} while the task is still running. */
    @Nullable
    public Outcome outcome() {
        return outcome;
    }

    /**
     * How many input items short of the requested {@code count} the bot actually loaded into the
     * furnace (≥ 0). Non-zero only when fuel removal (fuel == input) or a reservation lowered the
     * loadable input below the request — a truthful clamp that the trigger surfaces (DESIGN §3) read
     * alongside {@link #outcome()}. Zero on a full load or before LOAD runs.
     */
    public int loadShortfall() {
        return loadShortfall;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String describeProgress() {
        String fp = furnacePos != null ? furnacePos.toShortString() : "none";
        return String.format(Locale.ROOT,
                "phase=%s furnace=%s collected=%d expected=%d%s",
                phase.name().toLowerCase(Locale.ROOT),
                fp,
                collectedCount,
                resolution != null ? params.count : 0,
                outcome != null ? " outcome=" + outcome.kind().name().toLowerCase(Locale.ROOT) : "");
    }

    // -------------------------------------------------------------------------
    // Task lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onStart() {
        this.reader = new FurnaceStateReader(this.controller);
        setDebugState("Preparing deferred smelt");
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        return switch (phase) {
            case LOCATE_FURNACE -> tickLocate();
            case GO_TO_FURNACE -> tickGoToFurnace();
            case LOAD -> tickLoad();
            case WAIT_FOR_COOK -> tickWait();
            case COLLECT -> tickCollect();
            case DONE, DEGRADED -> null;
        };
    }

    @Override
    protected void onStop(Task interruptTask) {
        // The task records its outcome into the job store + the Outcome field; furnace contents
        // legitimately stay loaded. But if the task is interrupted after LOAD without reaching a
        // terminal (which would have released via terminate), release the input reservation here so a
        // forced stop never leaks a reservation into the rest of the run (WS5 clear() is the backstop).
        releaseInputReservation();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof SmeltDeferredTask t)) return false;
        return t.params.inputItem == this.params.inputItem
                && t.params.count == this.params.count;
    }

    @Override
    protected String toDebugString() {
        return "Deferred smelt " + params.count + " x "
                + BuiltInRegistries.ITEM.getKey(params.inputItem);
    }

    // -------------------------------------------------------------------------
    // Phase: LOCATE_FURNACE
    // -------------------------------------------------------------------------

    private Task tickLocate() {
        ServerLevel world = this.controller.getWorld();
        if (world == null) {
            return terminate(new Outcome(OutcomeKind.SETUP_FAILED, null, 0, params.count,
                    null, 0, null, "no_world"));
        }

        // Resolve the recipe first (independent of the furnace). On no recipe, fail gracefully.
        RecipeManager mgr = world.getRecipeManager();
        RegistryAccess registries = world.registryAccess();
        Optional<CookResolution> resolved = (params.preferredKind != null)
                ? COOKING_RECIPE_ACCESS.resolve(mgr, params.inputItem, params.preferredKind, registries)
                : COOKING_RECIPE_ACCESS.resolveAny(mgr, params.inputItem, registries);
        if (resolved.isEmpty()) {
            return terminate(new Outcome(OutcomeKind.NO_RECIPE, null, 0, params.count,
                    null, 0, null, "no_recipe"));
        }
        this.resolution = resolved.get();
        this.chosenKind = resolution.kind();

        // Furnace position: persisted pos on resume (authoritative), else a near-scan locate.
        if (resumeFurnacePos != null) {
            this.furnacePos = resumeFurnacePos;
        } else {
            Optional<BlockPos> nearest = this.controller.getBlockScanner()
                    .getNearestBlock(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
            if (nearest.isEmpty()) {
                return terminate(new Outcome(OutcomeKind.SETUP_FAILED, resolution.output().getItem(),
                        0, params.count, null, 0, null, "no_furnace_in_range"));
            }
            this.furnacePos = nearest.get().immutable();
        }

        setDebugState("Located furnace at " + furnacePos.toShortString());
        phase = Phase.GO_TO_FURNACE;
        return tickGoToFurnace();
    }

    // -------------------------------------------------------------------------
    // Phase: GO_TO_FURNACE
    // -------------------------------------------------------------------------

    private Task tickGoToFurnace() {
        if (!isAtFurnace()) {
            setDebugState("Going to furnace " + furnacePos.toShortString());
            return new GetCloseToBlockTask(furnacePos);
        }
        // Validate the BE still exists before loading.
        FurnaceState s = reader.read(furnacePos);
        if (!s.present()) {
            return terminate(new Outcome(OutcomeKind.FURNACE_GONE, resolution.output().getItem(),
                    0, params.count, null, 0, furnacePos, DeferredDegradation.STATION_GONE.label()));
        }
        phase = Phase.LOAD;
        return tickLoad();
    }

    // -------------------------------------------------------------------------
    // Phase: LOAD (write input + fuel; persist job record)
    // -------------------------------------------------------------------------

    private Task tickLoad() {
        ServerLevel world = this.controller.getWorld();
        if (world == null || !(world.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return terminate(new Outcome(OutcomeKind.FURNACE_GONE, resolution.output().getItem(),
                    0, params.count, null, 0, furnacePos, DeferredDegradation.STATION_GONE.label()));
        }

        Item input = params.inputItem;
        int cookTicks = resolution.cookTicks();

        // Confirm the bot actually holds the input items to load. Input uses the BROAD free read
        // (getItemCount includes container/chest stock) minus any reservation — scope-consistent with
        // the existing broad read. Do NOT "harmonize" this with the fuel freeInventoryOnly read below:
        // the two reads have deliberately different scopes (B3).
        int heldInput = this.ledger != null
                ? this.ledger.free(this.controller, input)
                : this.controller.getItemStorage().getItemCount(input);
        int loadCount = Math.min(params.count, heldInput);
        if (loadCount < 1) {
            return terminate(new Outcome(OutcomeKind.SETUP_FAILED, resolution.output().getItem(),
                    0, params.count, null, 0, furnacePos, "no_input_items"));
        }

        // Plan fuel from inventory (registry burn-times, charcoal>coal>planks/logs, lava excluded).
        // The ledger makes FuelPlanner skip any reserved item as fuel and reserve the chosen fuel.
        Optional<FuelPlan> planned =
                new FuelPlanner().plan(loadCount, cookTicks, this.controller, this.ledger);
        if (planned.isEmpty()) {
            // Nothing cooked — no fuel available/collectable.
            int fuelNeededHint = estimateFuelHint(loadCount, cookTicks);
            return terminate(new Outcome(OutcomeKind.NO_FUEL, resolution.output().getItem(),
                    0, params.count, null, fuelNeededHint, furnacePos, DeferredDegradation.OUT_OF_FUEL.label()));
        }
        this.fuelPlan = planned.get();

        // Refuse to load a furnace whose input or fuel slot is already occupied (leftover batch, a
        // player loaded it, etc.). Writing was previously guarded with isEmpty() checks that SILENTLY
        // skipped the consumption while the process still expected a full output — parking forever on
        // items that never arrive. Reject truthfully up front so both audiences hear "furnace_in_use"
        // (plan Decision 6 / DESIGN.md §3) rather than a silent indefinite park. (The result slot may
        // legitimately hold prior output; only input/fuel occupancy blocks a fresh load.)
        if (!furnace.getItem(FurnaceStateReader.SLOT_INPUT).isEmpty()
                || !furnace.getItem(FurnaceStateReader.SLOT_FUEL).isEmpty()) {
            return terminate(new Outcome(OutcomeKind.FURNACE_IN_USE, resolution.output().getItem(),
                    0, params.count, fuelPlan.fuelItem(), 0, furnacePos, "furnace_in_use"));
        }

        LivingEntityInventory inv =
                ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();

        // Write fuel into slot 1 (now confirmed empty above).
        int fuelSlotIdx = inv.getSlotWithStack(new ItemStack(fuelPlan.fuelItem()));
        if (fuelSlotIdx != -1) {
            furnace.setItem(FurnaceStateReader.SLOT_FUEL, inv.removeItem(fuelSlotIdx, fuelPlan.fuelCount()));
            furnace.setChanged();
        }

        // Write input into slot 0 (now confirmed empty above). Capture the ACTUAL stack moved: when
        // the fuel item == the input item, the fuel removal above already drained part of the same
        // inventory pool, so this removeItem may return FEWER than loadCount. expectedOutput, the cook
        // ETA, and the persisted record must all be driven by this actual count — not the stale
        // pre-fuel loadCount, which would over-promise output (Fix A, ledger-independent).
        int actualLoadCount = 0;
        int inputSlotIdx = inv.getSlotWithStack(new ItemStack(input));
        if (inputSlotIdx != -1) {
            ItemStack removedInputStack = inv.removeItem(inputSlotIdx, loadCount);
            actualLoadCount = removedInputStack.getCount();
            furnace.setItem(FurnaceStateReader.SLOT_INPUT, removedInputStack);
            furnace.setChanged();
        }
        if (actualLoadCount < 1) {
            // The pool was fully consumed by the fuel removal (fuel == input) or vanished — nothing
            // to cook. Surface truthfully to both audiences rather than parking on a phantom batch.
            return terminate(new Outcome(OutcomeKind.SETUP_FAILED, resolution.output().getItem(),
                    0, params.count, fuelPlan.fuelItem(), 0, furnacePos, "no_input_after_fuel"));
        }

        // Reserve the input actually loaded so a later same-tick consult cannot draw it (N1:
        // belt-and-suspenders — free()'s reconcile-to-live-held already hides the removed stack; the
        // cross-step protection is the plan-time pre-seed in WS5). Released on terminate.
        if (this.ledger != null) {
            this.reservedInput = input;
            this.reservedInputCount = this.ledger.reserve(this.controller, input, actualLoadCount);
        }

        long startGameTime = world.getGameTime();
        int expectedOutput = actualLoadCount * resolution.output().getCount();
        Item outputItem = resolution.output().getItem();

        this.process = new DeferredCookProcess(
                furnacePos, chosenKind, input, actualLoadCount, outputItem, expectedOutput,
                cookTicks, fuelPlan.fuelItem(), fuelPlan.fuelCount(), startGameTime,
                params.stallPolls, params.timeoutSeconds, this.controller);

        // Persist the job record so resume never depends on the scanner.
        persistJobRecord(world, startGameTime, expectedOutput, outputItem, input, actualLoadCount);

        // Truthfulness (DESIGN §3): record the input shortfall so the terminal Outcome can carry it
        // (fuel removal ate into the same pool, OR a reservation lowered free below params.count). This
        // keeps the task an outcome-only recorder — the agentic wrapper turns loadShortfall into the
        // dual-audience report; the standalone command surfaces collected-vs-requested.
        this.loadShortfall = params.count - actualLoadCount;

        setDebugState("Loaded furnace; waiting for cook");
        phase = Phase.WAIT_FOR_COOK;
        return null; // park — do NOT push any InfoMessage here
    }

    // -------------------------------------------------------------------------
    // Phase: WAIT_FOR_COOK (parked; poll BE every tick)
    // -------------------------------------------------------------------------

    private Task tickWait() {
        DeferredProgress progress = process.poll(this.controller);

        if (process.isComplete(progress)) {
            phase = Phase.COLLECT;
            return tickCollect();
        }

        DeferredDegradation degradation = process.degradationOf(progress);
        if (degradation != null) {
            switch (degradation) {
                case STATION_GONE -> {
                    return terminate(new Outcome(OutcomeKind.FURNACE_GONE, process.outputItem(),
                            progress.outputCount(), process.expectedOutputCount(),
                            process.fuelItem(), 0, furnacePos, degradation.label()));
                }
                case OUT_OF_FUEL -> {
                    // Partial vs. nothing distinguished by output collected so far.
                    // Collect whatever finished before reporting the partial.
                    return tickCollectPartial(progress, degradation);
                }
                case STALLED_TIMEOUT -> {
                    return terminate(new Outcome(OutcomeKind.STALLED_TIMEOUT, process.outputItem(),
                            progress.outputCount(), process.expectedOutputCount(),
                            process.fuelItem(), 0, furnacePos, degradation.label()));
                }
                case STALLED_RETRYING -> {
                    // SOFT: navigate back to the furnace so the chunk ticks again, then keep waiting.
                    if (!isAtFurnace()) {
                        setDebugState("Returning to furnace to resume cook");
                        return new GetCloseToBlockTask(furnacePos);
                    }
                    // Already at the furnace but still not simulated — keep parking; the hard
                    // timeout is the only terminal give-up.
                    setDebugState("Waiting for chunk to tick");
                    return null;
                }
                case TAMPERED_OUTPUT -> {
                    // Not classified during the wait by the adapter; handled at COLLECT. No-op here.
                }
            }
        }

        // Healthy in-progress: if the bot drifted off and the chunk isn't ticking, walk back so the
        // furnace makes progress (park-and-resume is a SUCCESS path). Otherwise stand by (park).
        if (!progress.simulated() && !isAtFurnace()) {
            setDebugState("Returning to furnace to keep it ticking");
            return new GetCloseToBlockTask(furnacePos);
        }
        setDebugState("Waiting for items to smelt");
        return null; // park
    }

    // -------------------------------------------------------------------------
    // Phase: COLLECT
    // -------------------------------------------------------------------------

    private Task tickCollect() {
        ServerLevel world = this.controller.getWorld();
        if (world == null || !(world.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return terminate(new Outcome(OutcomeKind.FURNACE_GONE, process.outputItem(),
                    collectedCount, process.expectedOutputCount(),
                    process.fuelItem(), 0, furnacePos, DeferredDegradation.STATION_GONE.label()));
        }
        if (!isAtFurnace()) {
            setDebugState("Returning to furnace to collect");
            return new GetCloseToBlockTask(furnacePos);
        }

        collectFromOutputSlot(furnace);

        int expected = process.expectedOutputCount();
        OutcomeKind kind = (collectedCount >= expected)
                ? OutcomeKind.CLEAN_SUCCESS
                : OutcomeKind.TAMPERED;
        String reason = kind == OutcomeKind.TAMPERED
                ? DeferredDegradation.TAMPERED_OUTPUT.label() : "clean";
        return terminate(new Outcome(kind, process.outputItem(), collectedCount, expected,
                process.fuelItem(), 0, furnacePos, reason));
    }

    /**
     * Collect-then-report for the OUT_OF_FUEL mid-batch case: gather whatever finished, then report
     * PARTIAL (some produced) or NO_FUEL-equivalent partial=0 (nothing produced).
     */
    private Task tickCollectPartial(DeferredProgress progress, DeferredDegradation degradation) {
        ServerLevel world = this.controller.getWorld();
        if (world != null
                && world.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace) {
            if (!isAtFurnace()) {
                setDebugState("Returning to furnace to collect partial");
                return new GetCloseToBlockTask(furnacePos);
            }
            collectFromOutputSlot(furnace);
        }
        int expected = process.expectedOutputCount();
        if (collectedCount > 0) {
            return terminate(new Outcome(OutcomeKind.PARTIAL_OUT_OF_FUEL, process.outputItem(),
                    collectedCount, expected, process.fuelItem(), 0, furnacePos, degradation.label()));
        }
        return terminate(new Outcome(OutcomeKind.NO_FUEL, process.outputItem(),
                0, expected, process.fuelItem(), 0, furnacePos, degradation.label()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Pulls the output slot into the bot's inventory and accumulates {@link #collectedCount}. */
    private void collectFromOutputSlot(AbstractFurnaceBlockEntity furnace) {
        ItemStack output = furnace.getItem(FurnaceStateReader.SLOT_RESULT);
        if (output.isEmpty()) {
            return;
        }
        int before = output.getCount();
        LivingEntityInventory inv =
                ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();
        if (inv.insertStack(output)) {
            // insertStack mutates `output` (the live slot stack); whatever remains stays in the slot.
            int remaining = output.getCount();
            collectedCount += (before - remaining);
            furnace.setChanged();
        }
    }

    private boolean isAtFurnace() {
        if (furnacePos == null) return false;
        return furnacePos.closerThan(
                new Vec3i(
                        (int) this.controller.getEntity().position().x,
                        (int) this.controller.getEntity().position().y,
                        (int) this.controller.getEntity().position().z),
                AT_FURNACE_RANGE);
    }

    /** Best-effort fuel-count hint for the NO_FUEL message (charcoal-equivalent, 1600 ticks). */
    private static int estimateFuelHint(int count, int cookTicksPerItem) {
        int total = count * cookTicksPerItem;
        return Math.max(1, (total + 1600 - 1) / 1600);
    }

    /** Persists (or refreshes) the {@link DeferredJobRecord} for this job in the world store. */
    private void persistJobRecord(ServerLevel world, long startGameTime, int expectedOutput,
                                  Item outputItem, Item input, int loadCount) {
        DeferredJobStore store = DeferredJobStore.get();
        if (store == null) {
            // No world store loaded (should not happen on a live server). Log and proceed —
            // the cook still works; only restart-resume is unavailable.
            this.controller.logWarning("DeferredJobStore unavailable; smelt job not persisted.");
            return;
        }
        try {
            String dimensionId = world.dimension().location().toString();
            DeferredJobRecord rec = new DeferredJobRecord();
            rec.id = DeferredJobRecord.idFor(dimensionId, furnacePos);
            rec.kind = process.kind();
            rec.status = DeferredJobRecord.Status.COOKING;
            rec.pos = new int[]{furnacePos.getX(), furnacePos.getY(), furnacePos.getZ()};
            rec.dimension = dimensionId;
            rec.inputItem = BuiltInRegistries.ITEM.getKey(input).toString();
            rec.outputItem = BuiltInRegistries.ITEM.getKey(outputItem).toString();
            rec.inputCount = loadCount;
            rec.expectedOutputCount = expectedOutput;
            rec.fuelItem = BuiltInRegistries.ITEM.getKey(fuelPlan.fuelItem()).toString();
            rec.fuelCount = fuelPlan.fuelCount();
            rec.startGameTime = startGameTime;
            rec.etaGameTime = process.etaGameTime();
            rec.owningBotUuid = this.controller.getEntity().getUUID().toString();
            rec.degradeReason = "";
            // Carry forward any unknown fields from an existing record at this furnace.
            DeferredJobRecord existing = store.get(rec.id);
            if (existing != null) {
                rec.inheritUnknownFieldsFrom(existing);
            }
            store.upsert(rec);
        } catch (Exception e) {
            this.controller.logWarning("Failed to persist deferred smelt job: " + e.getMessage());
        }
    }

    /**
     * Records the terminal outcome, updates the persisted job record's status, and flips
     * {@link #isFinished()} true. Returns {@code null} so the chain advances to onComplete.
     */
    private Task terminate(Outcome o) {
        this.outcome = o;
        this.finished = true;
        this.phase = (o.kind() == OutcomeKind.CLEAN_SUCCESS) ? Phase.DONE : Phase.DEGRADED;
        releaseInputReservation();
        updateJobStatus(o);
        setDebugState("Terminal: " + o.kind().name().toLowerCase(Locale.ROOT));
        return null;
    }

    /**
     * Releases this task's own input reservation exactly once (belt-and-suspenders, N1). No-op when no
     * ledger / nothing was reserved. The physical removal at LOAD plus {@code free()}'s reconcile to
     * live held already hide the loaded input from a later consult; this just clears the same-tick
     * forward reservation so it cannot linger past the run's own teardown (WS5 clear() is the backstop).
     */
    private void releaseInputReservation() {
        if (this.ledger != null && this.reservedInput != null && this.reservedInputCount > 0) {
            this.ledger.release(this.reservedInput, this.reservedInputCount);
            this.reservedInputCount = 0;
            this.reservedInput = null;
        }
    }

    /** Updates the persisted record's status/reason for a terminal outcome (best-effort). */
    private void updateJobStatus(Outcome o) {
        if (furnacePos == null) return;
        DeferredJobStore store = DeferredJobStore.get();
        if (store == null) return;
        ServerLevel world = this.controller.getWorld();
        if (world == null) return;
        try {
            String id = DeferredJobRecord.idFor(world.dimension().location().toString(), furnacePos);
            DeferredJobRecord rec = store.get(id);
            if (rec == null) return;
            if (o.kind() == OutcomeKind.CLEAN_SUCCESS) {
                // Completed and collected — remove the job from the store.
                store.delete(id);
                return;
            }
            rec.status = DeferredJobRecord.Status.DEGRADED;
            rec.degradeReason = o.reasonLabel();
            store.upsert(rec);
        } catch (Exception e) {
            this.controller.logWarning("Failed to update deferred smelt job status: " + e.getMessage());
        }
    }
}
