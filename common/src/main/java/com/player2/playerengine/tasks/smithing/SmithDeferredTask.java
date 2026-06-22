package com.player2.playerengine.tasks.smithing;

import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.UpgradeInSmithingTableTask;
import com.player2.playerengine.tasks.container.UpgradeInSmithingTableTask.SmithStepResult;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess.SmithingResolution;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccessImpl;
import com.player2.playerengine.util.ItemTarget;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deferred smithing-upgrade task: resolves the recipe, gathers the required
 * template/base/addition via the existing {@link UpgradeInSmithingTableTask} primitive
 * (refactored additively in WS2), performs the upgrade, and records a single {@link Outcome}
 * consumable by both the command surface ({@code SmithCommand.onSmithComplete()}) and the
 * agentic step factory ({@code SmithStepFactory}).
 *
 * <p>Mirrors the shape of {@code SmeltDeferredTask}:
 * extends {@link Task} directly, uses the parked idiom (returns {@code null} from
 * {@link #onTick()} while waiting, {@link #isFinished()} stays {@code false}), and does NOT
 * itself push any chat or model feedback — it only records the outcome. Reporting is the
 * caller's responsibility (dual-audience, player first, then model — DESIGN.md §3).
 *
 * <h3>State machine</h3>
 * <pre>
 *   RESOLVE -> DRIVE -> DONE / DEGRADED
 * </pre>
 * <ol>
 *   <li><b>RESOLVE</b> — call {@code SMITHING_RECIPE_ACCESS.resolve(...)}. Empty →
 *       terminal {@link OutcomeKind#NO_RECIPE}.</li>
 *   <li><b>DRIVE</b> — construct the parameterized {@code UpgradeInSmithingTableTask} with
 *       the resolved template/base/addition and output count; delegate ticks to it; read its
 *       typed terminal result each tick. Bound gather stalls via {@link SmithDeferredParams#gatherBudget}
 *       and {@link SmithDeferredParams#pollCap}.</li>
 * </ol>
 *
 * <p>Null-outcome lifecycle convention (mirrors {@code SmeltDeferredTask}): a lifecycle stop
 * without a recorded {@link #outcome()} is NOT treated as a failure by callers — the
 * {@code onComplete} callback checks {@code outcome() == null} and calls plain
 * {@code this.finish()} in that case.
 */
public final class SmithDeferredTask extends Task {

    /** Stateless smithing recipe resolver (mirrors the {@code COOKING_RECIPE_ACCESS} singleton). */
    private static final SmithingRecipeAccess SMITHING_RECIPE_ACCESS = new SmithingRecipeAccessImpl();

    // -------------------------------------------------------------------------
    // Outcome type
    // -------------------------------------------------------------------------

    /** Terminal outcome classifications for a smithing upgrade job. */
    public enum OutcomeKind {
        /** Output count == expected — full success, all upgrades performed. */
        CLEAN_SUCCESS,
        /**
         * 0 < collected < expected — some upgrades succeeded but not all
         * (e.g. gather stalled mid-batch or mid-run tamper cut the count short).
         */
        PARTIAL,
        /**
         * Collected fewer than expected with no missing-input or no-table explanation
         * (inventory contents changed unexpectedly mid-run — a player or other process
         * interfered with the bot's inputs or outputs).
         */
        TAMPERED,
        /** Pre-flight: no {@code SmithingTransformRecipe} produces the requested output. */
        NO_RECIPE,
        /**
         * No smithing table reachable and none placeable (none in inventory, none in
         * the block scanner's range, {@code TaskCatalogue} acquire path also failed).
         */
        NO_TABLE,
        /**
         * The required template item (e.g. {@code NETHERITE_UPGRADE_SMITHING_TEMPLATE})
         * could not be gathered within the gather budget — no acquisition path available
         * or budget exhausted.
         */
        MISSING_TEMPLATE,
        /**
         * The required base item (the tool/armor to upgrade, e.g. {@code DIAMOND_PICKAXE})
         * could not be gathered within the gather budget.
         */
        MISSING_BASE,
        /**
         * The required addition item (the upgrade material, e.g. {@code NETHERITE_INGOT})
         * could not be gathered within the gather budget.
         */
        MISSING_ADDITION,
        /**
         * A construction or lifecycle precondition failed before the upgrade could start
         * (e.g. null world, recipe resolution error, internal error).
         */
        SETUP_FAILED
    }

    /**
     * Immutable terminal outcome. Read by the command {@code onSmithComplete} and the agentic
     * step factory via {@link #outcome()} to produce the dual-audience report (player first,
     * then model — DESIGN.md §3).
     *
     * <p>This task does NOT itself push any chat or model feedback — it only records the
     * outcome. Callers are responsible for dual-audience reporting on every terminal path.
     *
     * @param kind        terminal classification.
     * @param collected   how many output items resulted from the upgrade(s).
     * @param expected    how many output items a fully-successful run would have produced
     *                    (== {@code params.count} for single-output recipes).
     * @param outputName  registry name of the output item (e.g. {@code "minecraft:netherite_pickaxe"})
     *                    or {@code "?"} when unavailable; pre-formatted for display.
     * @param outputItem  the resolved output item, or {@code null} before recipe resolution
     *                    succeeds (e.g. on {@link OutcomeKind#NO_RECIPE} or
     *                    {@link OutcomeKind#SETUP_FAILED}).
     * @param reasonLabel machine-readable reason token used for the model-facing note and the
     *                    agentic run-state smith slot
     *                    (e.g. {@code "no_recipe"}, {@code "no_table"}, {@code "missing_template"},
     *                    {@code "missing_base"}, {@code "missing_addition"}, {@code "tampered"},
     *                    {@code "partial"}, {@code "clean"}, {@code "setup_failed"}).
     */
    public record Outcome(
            OutcomeKind kind,
            int collected,
            int expected,
            String outputName,
            @Nullable Item outputItem,
            String reasonLabel) {

        /** True only for {@link OutcomeKind#CLEAN_SUCCESS}. */
        public boolean isCleanSuccess() {
            return kind == OutcomeKind.CLEAN_SUCCESS;
        }

        /**
         * True when the outcome is a degradation (not fully successful) — any kind other than
         * {@link OutcomeKind#CLEAN_SUCCESS}. Used by the agentic step factory to route to
         * {@code finishWithNote} / {@code finishWithError} vs {@code finishWithInfo}.
         */
        public boolean isDegraded() {
            return kind != OutcomeKind.CLEAN_SUCCESS;
        }

        /**
         * Builds an {@link Outcome} for a {@link OutcomeKind#NO_RECIPE} terminal: no
         * transform recipe produces the requested item.
         *
         * @param requestedItem the item the caller asked for (may be null on error).
         * @param count         the requested count.
         */
        public static Outcome noRecipe(@Nullable Item requestedItem, int count) {
            String name = requestedItem != null
                    ? BuiltInRegistries.ITEM.getKey(requestedItem).toString()
                    : "?";
            return new Outcome(OutcomeKind.NO_RECIPE, 0, count, name, requestedItem, "no_recipe");
        }

        /**
         * Builds an {@link Outcome} for a {@link OutcomeKind#SETUP_FAILED} terminal.
         *
         * @param requestedItem the item the caller asked for (may be null on error).
         * @param count         the requested count.
         * @param reason        short reason token (e.g. {@code "no_world"}).
         */
        public static Outcome setupFailed(@Nullable Item requestedItem, int count, String reason) {
            String name = requestedItem != null
                    ? BuiltInRegistries.ITEM.getKey(requestedItem).toString()
                    : "?";
            return new Outcome(OutcomeKind.SETUP_FAILED, 0, count, name, requestedItem, reason);
        }
    }

    // -------------------------------------------------------------------------
    // Internal state machine phases
    // -------------------------------------------------------------------------

    private enum Phase {
        RESOLVE,
        DRIVE,
        DONE,
        DEGRADED
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final SmithDeferredParams params;

    /**
     * Chain-scoped material reservation ledger (WS3), or {@code null} for the standalone
     * {@code smith} command path (which runs outside any agentic run). Threaded into the
     * {@link UpgradeInSmithingTableTask} so the gather-decision read is reservation-aware and
     * the three roles are reserved before removal; released on the single {@link #terminate}
     * exit. {@code null} is byte-for-byte identical to the pre-WS3 behaviour.
     */
    @Nullable
    private final MaterialReservationService ledger;

    private Phase phase = Phase.RESOLVE;
    private boolean finished = false;

    /** The terminal outcome, set exactly once when the task reaches a terminal phase. */
    @Nullable
    private Outcome outcome;

    // Resolved during RESOLVE phase; used during DRIVE phase.
    @Nullable
    private SmithingResolution resolution;
    @Nullable
    private UpgradeInSmithingTableTask innerTask;

    // Gather-stall tracking: how many consecutive ticks has inner been in NEEDS_GATHER.
    private int consecutiveGatherTicks = 0;
    // Absolute tick counter for poll-cap enforcement.
    private int totalTicks = 0;
    // Output count at the start of DRIVE phase (for delta / partial detection).
    private int outputCountAtDriveStart = -1;
    // Whether we were in NO_TABLE state last tick (to avoid flip-flopping).
    private int noTableTicks = 0;
    // Threshold of NO_TABLE ticks before giving up (one full poll cycle ~ 20 ticks to try acquire).
    private static final int NO_TABLE_GIVE_UP_TICKS = 40;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the task for a fresh smithing upgrade job.
     *
     * @param params the immutable parameter bundle (output item, count, gather budget, poll cap).
     */
    public SmithDeferredTask(SmithDeferredParams params) {
        this(params, null);
    }

    /**
     * Reservation-aware constructor (WS3). Threads the chain-scoped
     * {@link MaterialReservationService} so the smith step consults the ledger before gathering and
     * reserves its three role species before removal. {@code ledger == null} (e.g. the standalone
     * {@code smith} command) is byte-for-byte identical to the pre-WS3 behaviour.
     *
     * @param params the immutable parameter bundle (output item, count, gather budget, poll cap).
     * @param ledger the run's reservation ledger, or {@code null} outside a run (no-op).
     */
    public SmithDeferredTask(SmithDeferredParams params, @Nullable MaterialReservationService ledger) {
        if (params == null) throw new IllegalArgumentException("params must not be null");
        this.params = params;
        this.ledger = ledger;
    }

    // -------------------------------------------------------------------------
    // Public API (stable — WS3/WS4 depend on these)
    // -------------------------------------------------------------------------

    /** The terminal outcome, or {@code null} while the task is still running. */
    @Nullable
    public Outcome outcome() {
        return outcome;
    }

    // -------------------------------------------------------------------------
    // Task lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    protected void onStart() {
        setDebugState("Preparing deferred smith");
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        totalTicks++;
        return switch (phase) {
            case RESOLVE  -> tickResolve();
            case DRIVE    -> tickDrive();
            case DONE, DEGRADED -> null;
        };
    }

    @Override
    protected void onStop(Task interruptTask) {
        // The task records its outcome. But if it is force-stopped (interrupt) after the inner upgrade
        // task reserved its three roles yet before reaching the single terminate() exit (the only other
        // place releaseReservations() is called), those role reservations would leak into the rest of
        // the run. Release them here, mirroring SmeltDeferredTask.onStop (WS5 clear() is the backstop).
        if (this.innerTask != null) {
            this.innerTask.releaseReservations();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof SmithDeferredTask t)) return false;
        return t.params.outputItem == this.params.outputItem
                && t.params.count == this.params.count;
    }

    @Override
    protected String toDebugString() {
        return "Deferred smith " + params.count + " x "
                + BuiltInRegistries.ITEM.getKey(params.outputItem);
    }

    // -------------------------------------------------------------------------
    // Phase: RESOLVE
    // -------------------------------------------------------------------------

    /**
     * RESOLVE phase: obtain world + recipe manager, run the resolver.
     * On no match → terminal {@link OutcomeKind#NO_RECIPE}.
     * On match → build the parameterized {@code UpgradeInSmithingTableTask}, record
     * the pre-drive output count, and advance to DRIVE.
     */
    private Task tickResolve() {
        if (this.controller == null) {
            return terminate(Outcome.setupFailed(params.outputItem, params.count, "no_controller"));
        }
        ServerLevel world = this.controller.getWorld();
        if (world == null) {
            return terminate(Outcome.setupFailed(params.outputItem, params.count, "no_world"));
        }

        RecipeManager mgr = world.getRecipeManager();
        RegistryAccess registries = world.registryAccess();

        Optional<SmithingResolution> resolved =
                SMITHING_RECIPE_ACCESS.resolve(mgr, params.outputItem, registries);
        if (resolved.isEmpty()) {
            return terminate(Outcome.noRecipe(params.outputItem, params.count));
        }
        this.resolution = resolved.get();

        // Build the parameterized UpgradeInSmithingTableTask using the resolved ingredients.
        // template/base/addition lists: first item is the canonical gather target (plan WS1).
        List<Item> templateItems  = resolution.template();
        List<Item> baseItems      = resolution.base();
        List<Item> additionItems  = resolution.addition();

        if (templateItems.isEmpty() || baseItems.isEmpty() || additionItems.isEmpty()) {
            return terminate(Outcome.setupFailed(params.outputItem, params.count, "empty_ingredient_list"));
        }

        // Build ItemTargets from the resolved item lists (all accepted items, first = canonical).
        ItemTarget templateTarget  = new ItemTarget(templateItems.toArray(Item[]::new), params.count);
        ItemTarget baseTarget      = new ItemTarget(baseItems.toArray(Item[]::new), params.count);
        ItemTarget additionTarget  = new ItemTarget(additionItems.toArray(Item[]::new), params.count);
        // Output uses the first (only) item from the resolved output stack.
        Item outputItem = resolution.output().getItem();
        if (outputItem == null || outputItem == Items.AIR) {
            return terminate(Outcome.setupFailed(params.outputItem, params.count, "empty_output"));
        }
        ItemTarget outputTarget = new ItemTarget(outputItem, params.count);

        // 5-arg constructor: tool=base, material=addition, template=templateTarget, output=outputTarget,
        // ledger=the chain reservation ledger (null on the standalone command path → behaves as 4-arg).
        this.innerTask = new UpgradeInSmithingTableTask(baseTarget, additionTarget, templateTarget, outputTarget, this.ledger);

        // Record how many output items the bot already holds before DRIVE starts (for delta tracking).
        this.outputCountAtDriveStart = this.controller.getItemStorage().getItemCount(outputItem);

        setDebugState("Recipe resolved; driving upgrade for " + BuiltInRegistries.ITEM.getKey(outputItem));
        phase = Phase.DRIVE;
        return tickDrive();
    }

    // -------------------------------------------------------------------------
    // Phase: DRIVE
    // -------------------------------------------------------------------------

    /**
     * DRIVE phase: delegate ticks to the parameterized {@code UpgradeInSmithingTableTask};
     * read its typed terminal result each tick; enforce gather-budget and poll-cap;
     * classify into an {@link Outcome} when terminal.
     */
    private Task tickDrive() {
        if (innerTask == null || resolution == null) {
            // Should not happen — defensive guard.
            return terminate(Outcome.setupFailed(params.outputItem, params.count, "inner_null"));
        }

        // Enforce absolute poll cap.
        if (totalTicks > params.pollCap) {
            return terminate(buildMissingOutcome("poll_cap_exceeded"));
        }

        // Read the typed step result set by the inner task on its last tick.
        // Note: innerTask.isFinished() calls StorageHelper which requires controller != null;
        // guard it so the first tick (before innerTask is started) does not NPE.
        // We use the typed stepResult as the primary finish signal, which is always safe to read.
        SmithStepResult stepResult = innerTask.getStepResult();

        // Check whether the inner task has finished (ResourceTask.isFinished = itemTargetsMet).
        // Only check after innerTask has been started (controller set).
        if (innerTask.controller != null && innerTask.isFinished()) {
            return classifyOnFinished();
        }

        if (stepResult == SmithStepResult.NEEDS_GATHER) {
            consecutiveGatherTicks++;
            noTableTicks = 0;
            if (consecutiveGatherTicks > params.gatherBudget) {
                // Budget exhausted — attribute the missing role by inventory inspection.
                return terminate(buildMissingOutcome("gather_budget_exceeded"));
            }
        } else if (stepResult == SmithStepResult.NO_TABLE) {
            noTableTicks++;
            consecutiveGatherTicks = 0;
            if (noTableTicks > NO_TABLE_GIVE_UP_TICKS) {
                int collected = currentCollected();
                String outputName = BuiltInRegistries.ITEM.getKey(params.outputItem).toString();
                return terminate(new Outcome(OutcomeKind.NO_TABLE, collected, params.count,
                        outputName, params.outputItem, "no_table"));
            }
        } else if (stepResult == SmithStepResult.UPGRADED) {
            // Inner task finished cleanly (by returning null from onResourceTick after upgrade).
            return classifyOnFinished();
        } else {
            // IN_PROGRESS — reset stall counters.
            consecutiveGatherTicks = 0;
            noTableTicks = 0;
        }

        // Delegate the tick to the inner task (which manages its own sub-task chain).
        return innerTask;
    }

    // -------------------------------------------------------------------------
    // Terminal classification helpers
    // -------------------------------------------------------------------------

    /**
     * Called when the inner task reaches {@code isFinished()} == true or
     * {@code stepResult == UPGRADED}. Counts actual output vs expected and
     * classifies as {@link OutcomeKind#CLEAN_SUCCESS}, {@link OutcomeKind#PARTIAL},
     * or {@link OutcomeKind#TAMPERED}.
     */
    private Task classifyOnFinished() {
        int collected = currentCollected();
        String outputName = BuiltInRegistries.ITEM.getKey(params.outputItem).toString();
        Item outputItem = params.outputItem;

        if (collected >= params.count) {
            return terminate(new Outcome(OutcomeKind.CLEAN_SUCCESS, collected, params.count,
                    outputName, outputItem, "clean"));
        } else if (collected > 0) {
            // Some output produced — partial.
            return terminate(new Outcome(OutcomeKind.PARTIAL, collected, params.count,
                    outputName, outputItem, "partial"));
        } else {
            // Zero collected but task says finished — something changed mid-run (tampered) or
            // the output was already in inventory at drive start (count already met).
            // Re-check: if outputCountAtDriveStart was already >= count, treat as clean.
            if (outputCountAtDriveStart >= params.count) {
                return terminate(new Outcome(OutcomeKind.CLEAN_SUCCESS, params.count, params.count,
                        outputName, outputItem, "clean"));
            }
            return terminate(new Outcome(OutcomeKind.TAMPERED, 0, params.count,
                    outputName, outputItem, "tampered"));
        }
    }

    /**
     * Builds a role-specific {@link OutcomeKind#MISSING_TEMPLATE}, {@link OutcomeKind#MISSING_BASE},
     * or {@link OutcomeKind#MISSING_ADDITION} outcome by inspecting the bot's current inventory
     * counts for each resolved role's accepted items.
     *
     * <p>Attribution order: template → base → addition (deterministic, per the plan note).
     * All short roles are included in {@code reasonLabel} for a complete model message.
     *
     * @param extraReason additional context token (e.g. {@code "gather_budget_exceeded"}) appended
     *                    to the reason label.
     */
    private Outcome buildMissingOutcome(String extraReason) {
        if (resolution == null) {
            return Outcome.setupFailed(params.outputItem, params.count, extraReason);
        }

        int needed = params.count;
        boolean templateShort = countItems(resolution.template()) < needed;
        boolean baseShort     = countItems(resolution.base())     < needed;
        boolean additionShort = countItems(resolution.addition()) < needed;

        String outputName = BuiltInRegistries.ITEM.getKey(params.outputItem).toString();
        int collected = currentCollected();

        // Build reason label listing all short roles.
        StringBuilder reason = new StringBuilder();
        if (templateShort) reason.append("missing_template");
        if (baseShort) {
            if (reason.length() > 0) reason.append("+");
            reason.append("missing_base");
        }
        if (additionShort) {
            if (reason.length() > 0) reason.append("+");
            reason.append("missing_addition");
        }
        if (reason.length() == 0) {
            // Nothing appears short by inventory — fall back to generic.
            reason.append("gather_stall");
        }
        if (extraReason != null && !extraReason.isBlank()) {
            reason.append(":").append(extraReason);
        }

        // Primary outcome kind = first short role (template → base → addition).
        OutcomeKind primaryKind;
        if (templateShort) {
            primaryKind = OutcomeKind.MISSING_TEMPLATE;
        } else if (baseShort) {
            primaryKind = OutcomeKind.MISSING_BASE;
        } else if (additionShort) {
            primaryKind = OutcomeKind.MISSING_ADDITION;
        } else {
            // None appear short — gather stall for an unknown reason. Use MISSING_ADDITION as fallback.
            primaryKind = OutcomeKind.MISSING_ADDITION;
        }

        return new Outcome(primaryKind, collected, params.count, outputName, params.outputItem,
                reason.toString());
    }

    /**
     * Count how many items from a role list the bot currently holds in inventory.
     * Uses {@code getItemCount(Item...)} with all accepted items for the role.
     */
    private int countItems(List<Item> items) {
        if (items.isEmpty()) return 0;
        return this.controller.getItemStorage().getItemCount(items.toArray(Item[]::new));
    }

    /**
     * How many of the target output item the bot has collected since DRIVE started.
     * Clamped to [0, params.count].
     */
    private int currentCollected() {
        if (this.controller == null) return 0;
        int currentCount = this.controller.getItemStorage().getItemCount(params.outputItem);
        int delta = currentCount - Math.max(0, outputCountAtDriveStart);
        return Math.max(0, Math.min(params.count, delta));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Records the terminal outcome, flips {@link #isFinished()} true, and returns {@code null}
     * so the task chain advances to the {@code onComplete} callback.
     */
    private Task terminate(Outcome o) {
        // WS3: release every reservation the inner upgrade task granted on this single terminal exit
        // (no leaked reservation can outlive the step). No-op when innerTask is null (early RESOLVE
        // terminals before any inner task / reservation existed) or when the ledger is null.
        if (this.innerTask != null) {
            this.innerTask.releaseReservations();
        }
        this.outcome   = o;
        this.finished  = true;
        this.phase     = (o.kind() == OutcomeKind.CLEAN_SUCCESS) ? Phase.DONE : Phase.DEGRADED;
        setDebugState("Terminal: " + o.kind().name().toLowerCase(Locale.ROOT));
        return null;
    }
}
