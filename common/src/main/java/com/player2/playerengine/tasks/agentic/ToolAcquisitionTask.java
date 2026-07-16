package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.agentic.steps.ResolveStorageChestParams;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.MaterialChain;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic four-stage tool-acquisition pipeline (WS3). Given a needed {@link MiningRequirement},
 * obtains a sufficient pickaxe into the bot's inventory by trying, in this fixed order:
 * <ol>
 *   <li><b>(a) CHECK_INVENTORY</b> — already holds a sufficient pickaxe → done.</li>
 *   <li><b>(b) SEARCH_MARKED / WITHDRAW_MARKED</b> — EllieGPS marked chests (count + coords) →
 *       {@link BoundedContainerTransferTask} withdraw.</li>
 *   <li><b>(c) SEARCH_UNMARKED / WITHDRAW_UNMARKED</b> — nearby unmarked cached chests, with a
 *       {@link ContainerCache} pre-travel content check → withdraw.</li>
 *   <li><b>(d) CLIMB_CHAIN</b> — recursive material-chain craft via
 *       {@code TaskCatalogue.getItemTask(pickaxe, 1)} (the {@code SatisfyMiningRequirementTask}
 *       primitive); wood→stone→iron→diamond is realized by the catalogue recursion.</li>
 * </ol>
 *
 * <p>{@link #isFinished()} returns {@code true} exactly when
 * {@link StorageHelper#miningRequirementMetInventory} becomes true. On no path,
 * {@code terminateFailed("no_tool_acquisition_path")} stops the task without {@code finished} so the
 * parent observes a forced stop (FAILED), mirroring {@code GatherLooseItemsTask}.
 *
 * <p><b>Design invariants (HARD — violating any is a defect):</b>
 * <ul>
 *   <li>Deterministic only — no model calls, no {@code AiTaskClass}/deepsearch. Tier resolution and
 *       the tier ladder come from {@link MiningRequirement} / {@link MaterialChain} / {@link StorageHelper}.</li>
 *   <li>Never calls {@code isCorrectToolForDrops}, {@code Tier.getLevel()}, {@code Tier.getTag()}, or
 *       {@code TierSortingRegistry} (1.20.1-only / divergent — confined to {@code MiningRequirement}).</li>
 *   <li>Tier ceiling is DIAMOND. No NETHERITE requirement tier.</li>
 *   <li>The ONLY reservation calls are for the TOOL (pickaxe) draw — never the mined product. This task
 *       never deposits, releases, or clears any reservation.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 */
public final class ToolAcquisitionTask extends Task {

    enum Phase {
        CHECK_INVENTORY,
        SEARCH_MARKED,
        WITHDRAW_MARKED,
        SEARCH_UNMARKED,
        WITHDRAW_UNMARKED,
        CLIMB_CHAIN,
        DONE,
        FAILED
    }

    private final MiningRequirement requirement;
    private final MineBlockParams params;
    private final AgenticRunRegistry.AgenticRunState runState;
    private final MaterialReservationService ledger;

    private Phase phase = Phase.CHECK_INVENTORY;
    private boolean finished;

    /** Coordinates already withdraw-attempted; shared across stages (b) and (c) so a chest is never
     * tried twice and stage (c) excludes coords stage (b) already used. */
    private final Set<BlockPos> triedContainers = new HashSet<>();

    /** Pending marked-chest candidate coordinates (nearest-first), drained one withdraw at a time. */
    private final Deque<BlockPos> markedCandidates = new ArrayDeque<>();
    /** Pending unmarked-chest candidate coordinates (nearest-first), cache-confirmed lazily. */
    private final Deque<BlockPos> unmarkedCandidates = new ArrayDeque<>();

    private BlockPos activeWithdrawPos;
    private BoundedContainerTransferTask withdrawChild;
    /** Per-stage withdraw budget counters. Stage (b) and (c) each get a fresh budget up to
     * {@link #maxContainers()}; they must NOT share a counter or stage (c) inherits stage (b)'s
     * depletion and skips unmarked chests that hold the pickaxe. */
    private int markedAttempts;
    private int unmarkedAttempts;

    /** Climb cycle guard (Decision #4): budget + already-failed tiers. */
    private int climbBudgetRemaining;
    private final Set<MiningRequirement> failedTiers = EnumSet.noneOf(MiningRequirement.class);
    private Task climbChild;

    public ToolAcquisitionTask(
            MiningRequirement requirement,
            MineBlockParams params,
            AgenticRunRegistry.AgenticRunState runState,
            MaterialReservationService ledger) {
        this.requirement = requirement;
        this.params = params;
        this.runState = runState;
        this.ledger = ledger;
        this.climbBudgetRemaining = params != null ? Math.max(1, params.toolAcquireClimbBudget()) : 1;
    }

    @Override
    public boolean isFinished() {
        // The framework (and the parent MineBlockTask) may query isFinished() before this task's first
        // tick assigns controller. Guard against the null-controller window — not finished until the
        // live inventory check can actually run.
        if (this.controller == null) {
            return false;
        }
        return StorageHelper.miningRequirementMetInventory(this.controller, this.requirement);
    }

    @Override
    protected void onStart() {
        // HAND requires no pickaxe — the requirement is already met; the parent should never construct
        // this task for HAND, but guard defensively so a stray construction terminates cleanly.
        if (requirement == MiningRequirement.HAND
                || StorageHelper.miningRequirementMetInventory(this.controller, this.requirement)) {
            this.phase = Phase.DONE;
        } else {
            this.phase = Phase.CHECK_INVENTORY;
        }
        setDebugState(phase.name());
    }

    @Override
    protected Task onTick() {
        if (isFinished()) {
            // A prior stage's withdraw/craft satisfied the requirement — observe and finish.
            this.phase = Phase.DONE;
            setDebugState(phase.name());
            return null;
        }

        switch (phase) {
            case CHECK_INVENTORY:
                // isFinished() already covers the "already holds it" case above; advance to marked search.
                this.phase = Phase.SEARCH_MARKED;
                report("looking for a " + tierWord() + " pickaxe in marked storage...", false);
                setDebugState(phase.name());
                return null;

            case SEARCH_MARKED:
                return searchMarked();

            case WITHDRAW_MARKED:
                return driveWithdraw(Phase.SEARCH_MARKED, markedCandidates);

            case SEARCH_UNMARKED:
                return searchUnmarked();

            case WITHDRAW_UNMARKED:
                return driveWithdraw(Phase.SEARCH_UNMARKED, unmarkedCandidates);

            case CLIMB_CHAIN:
                return climbChain();

            case DONE:
            case FAILED:
            default:
                return null;
        }
    }

    // -------------------------------------------------------------------- stage (b): marked chests

    private Task searchMarked() {
        if (markedCandidates.isEmpty()) {
            Item pick = requirement.getMinimumPickaxe();
            Vec3 origin = this.controller.getPlayer().position();
            String dimensionId = dimensionId();
            List<BlockPos> coords = MarkedChestToolLocator.candidateCoordinates(
                    this.controller, pick, origin, params.radius(), dimensionId);
            for (BlockPos pos : coords) {
                if (!triedContainers.contains(pos)) {
                    markedCandidates.add(pos);
                }
            }
            if (markedCandidates.isEmpty()) {
                this.phase = Phase.SEARCH_UNMARKED;
                report("nothing marked; checking nearby chests for a " + tierWord() + " pickaxe...", false);
                setDebugState(phase.name());
                return null;
            }
        }
        this.phase = Phase.WITHDRAW_MARKED;
        setDebugState(phase.name());
        return null;
    }

    // ------------------------------------------------------------------ stage (c): unmarked chests

    private Task searchUnmarked() {
        if (unmarkedCandidates.isEmpty()) {
            Item pick = requirement.getMinimumPickaxe();
            ResolveStorageChestParams scanParams = new ResolveStorageChestParams(
                    params.radius(),   // searchRadius
                    params.radius(),   // placementRadius (unused here; mirror searchRadius)
                    true,              // preferExisting
                    false,             // allowPlacement — never place a chest to find a tool
                    true,              // avoidLootChests — do not raid generated loot chests
                    params.timeoutSeconds());

            // Enumerate nearby cached chests directly (the same cache StorageChestScanner reads) and
            // pre-confirm the pickaxe via ContainerCache WITHOUT travel. The scanner's ScanResult only
            // exposes its single best candidate, so we iterate the known chest locations ourselves and
            // apply the same radius gate, then the cache content pre-check.
            Vec3 origin = this.controller.getPlayer().position();
            double radiusSq = scanParams.searchRadius() * scanParams.searchRadius();
            List<BlockPos> chests = new java.util.ArrayList<>();
            chests.addAll(this.controller.getBlockScanner().getKnownLocations(Blocks.CHEST));
            chests.addAll(this.controller.getBlockScanner().getKnownLocations(Blocks.TRAPPED_CHEST));
            chests.sort(Comparator.comparingDouble(p -> distSq(p, origin)));
            for (BlockPos pos : chests) {
                if (triedContainers.contains(pos)) {
                    continue;
                }
                if (distSq(pos, origin) > radiusSq) {
                    continue;
                }
                // Pre-travel content check via cache. Unknown cache entry => skip (conservative v1:
                // never blind-travel to an unconfirmed chest).
                Optional<ContainerCache> cache = this.controller.getItemStorage().getContainerAtPosition(pos);
                if (cache.isEmpty()) {
                    continue;
                }
                if (cache.get().hasItem(pick)) {
                    unmarkedCandidates.add(pos);
                }
            }

            if (unmarkedCandidates.isEmpty()) {
                this.phase = Phase.CLIMB_CHAIN;
                report("no chest has a " + tierWord() + " pickaxe; trying to craft one...", false);
                setDebugState(phase.name());
                return null;
            }
        }
        this.phase = Phase.WITHDRAW_UNMARKED;
        setDebugState(phase.name());
        return null;
    }

    // ------------------------------------------------------- shared withdraw driver for (b) and (c)

    private Task driveWithdraw(Phase searchPhaseToReturnTo, Deque<BlockPos> candidates) {
        // A prior withdraw may have just succeeded — isFinished() at top of onTick handles DONE.
        if (withdrawChild != null) {
            if (!withdrawChild.isFinished() && !withdrawChild.stopped()) {
                return withdrawChild; // still running
            }
            // Child finished (success or shortfall) — re-evaluate the requirement next tick. Reservation
            // for the drawn tool happens here (tool draws only; never the mined product).
            reserveDrawnTool();
            withdrawChild = null;
            activeWithdrawPos = null;
            if (isFinished()) {
                this.phase = Phase.DONE;
                setDebugState(phase.name());
                return null;
            }
            // Not satisfied — fall through to the next candidate / search phase below.
        }

        boolean marked = searchPhaseToReturnTo == Phase.SEARCH_MARKED;
        int stageAttempts = marked ? markedAttempts : unmarkedAttempts;
        if (stageAttempts >= maxContainers() || candidates.isEmpty()) {
            // Exhausted this stage's OWN budget/candidates → advance to the next stage.
            this.phase = nextStageAfterWithdraw(searchPhaseToReturnTo);
            setDebugState(phase.name());
            return null;
        }

        BlockPos pos = candidates.poll();
        triedContainers.add(pos);
        if (marked) {
            markedAttempts++;
        } else {
            unmarkedAttempts++;
        }
        activeWithdrawPos = pos;
        Item pick = requirement.getMinimumPickaxe();
        List<StorageItemArgs.ItemQuery> queries = List.of(
                new StorageItemArgs.ItemQuery(pick, ItemHelper.stripItemName(pick), 1));
        withdrawChild = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW, pos, queries);
        report("withdrawing a " + tierWord() + " pickaxe from a chest at "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "...", false);
        setDebugState(phase.name() + "@" + pos.toShortString());
        return withdrawChild;
    }

    private Phase nextStageAfterWithdraw(Phase searchPhase) {
        return searchPhase == Phase.SEARCH_MARKED ? Phase.SEARCH_UNMARKED : Phase.CLIMB_CHAIN;
    }

    // ------------------------------------------------------------------ stage (d): material climb

    private Task climbChain() {
        if (climbChild != null) {
            if (!climbChild.isFinished() && !climbChild.stopped()) {
                return climbChild; // catalogue recursion still working
            }
            // Climb child finished. Reserve any freshly-crafted tool draw, then re-check the requirement.
            reserveDrawnTool();
            climbChild = null;
            if (isFinished()) {
                this.phase = Phase.DONE;
                setDebugState(phase.name());
                return null;
            }
            // PRIMARY cycle guard (Decision #4): the climb child finished while the requirement is still
            // unmet → decrement the budget and record the failed tier. This is what prevents the
            // "tool needs tool needs tool" loop, since getItemTask never signals failure for a
            // registered pickaxe.
            failedTiers.add(requirement);
            climbBudgetRemaining--;
            if (climbBudgetRemaining <= 0) {
                terminateFailed("no_tool_acquisition_path");
                return null;
            }
        }

        MaterialChain tier = MaterialChain.forRequirement(requirement);
        if (tier == null) {
            // No chain tier for this requirement (e.g. HAND) — nothing to climb; cannot acquire.
            terminateFailed("no_tool_acquisition_path");
            return null;
        }
        if (failedTiers.contains(requirement)) {
            // Re-encountering a tier already recorded as failed → definitive no-path (no retry storm).
            terminateFailed("no_tool_acquisition_path");
            return null;
        }

        Item pick = requirement.getMinimumPickaxe();
        Task child = TaskCatalogue.getItemTask(pick, 1);
        if (child == null) {
            // Genuinely uncatalogued item (defensive — standard pickaxes are all catalogued).
            terminateFailed("no_tool_acquisition_path");
            return null;
        }
        climbChild = child;
        report("crafting a " + tierWord() + " pickaxe (gathering its materials)...", false);
        setDebugState(phase.name() + ":" + tier.name());
        return climbChild;
    }

    // ------------------------------------------------------------------------------ reservation

    /**
     * Reserves the drawn tool (pickaxe) so a concurrent agentic step cannot double-spend it. Tool draws
     * ONLY — this task never reserves, releases, frees, or clears the mined product.
     */
    private void reserveDrawnTool() {
        if (ledger == null) {
            return;
        }
        Item pick = requirement.getMinimumPickaxe();
        // Consult free stock and reserve a single pickaxe (clamped by the ledger to genuinely-free stock).
        int free = ledger.free(this.controller, pick);
        if (free > 0) {
            ledger.reserve(this.controller, pick, 1);
        }
    }

    // ---------------------------------------------------------------------------------- terminal

    private void terminateFailed(String reason) {
        this.phase = Phase.FAILED;
        this.finished = false;
        setDebugState("FAILED:" + reason);
        if (withdrawChild != null && !withdrawChild.stopped()) {
            withdrawChild.stop(this);
        }
        if (climbChild != null && !climbChild.stopped()) {
            climbChild.stop(this);
        }
        this.controller.log("[Agentic] tool_acquisition: " + reason + " (req=" + requirement + ")");
        report("couldn't get a " + tierWord() + " pickaxe: " + reason, true);
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Mirror GatherLooseItemsTask: only treat as a fresh interruption if not already terminal,
        // so terminateFailed's self-stop does not recurse and overwrite the real reason.
        if (!finished && phase != Phase.DONE && phase != Phase.FAILED && !isFinished()) {
            // External interruption before any terminal state — record as a forced stop. We do NOT
            // call terminateFailed here (that would re-enter stop); the parent observes
            // stopped()==true && isFinished()==false as a FAILED acquisition.
            this.phase = Phase.FAILED;
            setDebugState("FAILED:interrupted");
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ToolAcquisitionTask task
                && task.requirement == this.requirement
                && java.util.Objects.equals(task.params, this.params);
    }

    @Override
    protected String toDebugString() {
        return "ToolAcquisition[" + requirement + "]";
    }

    // ----------------------------------------------------------------------------------- helpers

    private int maxContainers() {
        return params != null ? Math.max(1, params.toolAcquireMaxContainers()) : 1;
    }

    private String tierWord() {
        return requirement.name().toLowerCase(java.util.Locale.ROOT);
    }

    private String dimensionId() {
        try {
            return this.controller.getWorld().dimension().location().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static double distSq(BlockPos pos, Vec3 origin) {
        double dx = pos.getX() + 0.5 - origin.x;
        double dy = pos.getY() + 0.5 - origin.y;
        double dz = pos.getZ() + 0.5 - origin.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Player-facing progress note via the controller seam; suppressed once the run is terminal. */
    private void report(String message, boolean milestone) {
        if (runState != null && runState.isTerminal()) {
            return;
        }
        this.controller.reportAgenticProgress(message, milestone);
    }
}
