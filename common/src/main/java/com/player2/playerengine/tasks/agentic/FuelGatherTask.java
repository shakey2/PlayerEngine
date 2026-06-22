package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.containeraccess.ScanReportFormatter;
import com.player2.playerengine.containeraccess.StorageItemArgs;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.resources.CollectPlanksTask;
import com.player2.playerengine.trackers.storage.ContainerCache;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic four-stage fuel-gather pipeline (the agentic smelt step's PRE-GATHER). Structural copy
 * of {@link ToolAcquisitionTask}, but it acquires a SINGLE bound fuel item (chosen by
 * {@code FuelPlanner.deficitFor}) into the bot's inventory rather than a pickaxe, by trying, in this
 * fixed order:
 * <ol>
 *   <li><b>CHECK_INVENTORY</b> — already holds {@code >= neededCount} of the target fuel → done.</li>
 *   <li><b>SEARCH_MARKED / WITHDRAW_MARKED</b> — EllieGPS marked chests listing the fuel item →
 *       {@link BoundedContainerTransferTask} withdraw, each query CLAMPED to the remaining deficit.</li>
 *   <li><b>SEARCH_UNMARKED / WITHDRAW_UNMARKED</b> — nearby cached chests, {@link ContainerCache}
 *       content pre-check (no blind travel) → clamped withdraw.</li>
 *   <li><b>CLIMB_CHAIN</b> — world acquisition: planks/logs target → ONE {@link CollectPlanksTask}
 *       (chops natural logs with {@link MiningRequirement#HAND}, crafts planks, spawns NO
 *       {@code ToolAcquisitionTask}); coal target → GATE on a held wood pickaxe, then ONE catalogued
 *       coal mine; if the gate fails, {@code terminateFailed("no_acquirable_fuel")}.</li>
 * </ol>
 *
 * <p><b>Design invariants (HARD — violating any is a defect):</b>
 * <ul>
 *   <li><b>Reserves NOTHING.</b> Unlike {@code ToolAcquisitionTask}, this task does NOT copy
 *       {@code reserveDrawnTool}: gathered/mined fuel lands in inventory and {@code FuelPlanner.plan}
 *       performs the single reservation on the wrapper's subsequent re-run. Reserving here would
 *       double-count.</li>
 *   <li><b>No spiral.</b> The coal rung is gated on a HELD wood pickaxe
 *       ({@link StorageHelper#miningRequirementMetInventory} with {@link MiningRequirement#WOOD})
 *       CHECKED before constructing the coal task; without it the coal rung skips to
 *       {@code terminateFailed}. The default planks/logs rung uses {@code CollectPlanksTask(HAND)}
 *       which never acquires a tool. This severs smelt→coal→pickaxe-craft→iron→smelt by construction.</li>
 *   <li>Deterministic only — no model calls.</li>
 *   <li>Common-module only; byte-identical across branches.</li>
 * </ul>
 *
 * <p><b>{@code FuelGatherParams.timeoutSeconds} / {@code climbBudget} are NOT enforced here.</b> The
 * single-attempt climb bound comes from the {@link #climbStarted} latch (one {@code CollectPlanksTask}
 * or one catalogued coal mine per task instance), not from {@code climbBudget}. No wall-clock timeout is
 * applied to the climb child — the catalogued coal {@code MineAndCollectTask} is uncapped, so the coal
 * spiral is severed SOLELY by the held-wood-pickaxe gate (the {@code isCoal()} branch below), not by a
 * timeout. The chest stages are bounded by {@code maxContainers}. Treat those two params as reserved.
 */
public final class FuelGatherTask extends Task {

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

    private final FuelGatherParams params;
    private final AgenticRunState runState;
    @SuppressWarnings("unused") // kept for signature parity with ToolAcquisitionTask; never used to reserve
    private final MaterialReservationService ledger;

    private final Item targetFuelItem;
    private final int neededCount;

    private Phase phase = Phase.CHECK_INVENTORY;
    private boolean finished;

    private final Set<BlockPos> triedContainers = new HashSet<>();
    private final Deque<BlockPos> markedCandidates = new ArrayDeque<>();
    private final Deque<BlockPos> unmarkedCandidates = new ArrayDeque<>();

    private BlockPos activeWithdrawPos;
    private BoundedContainerTransferTask withdrawChild;
    private int markedAttempts;
    private int unmarkedAttempts;

    private Task climbChild;
    private boolean climbStarted;

    public FuelGatherTask(FuelGatherParams params,
                          AgenticRunState runState,
                          MaterialReservationService ledger) {
        this.params = params;
        this.runState = runState;
        this.ledger = ledger;
        this.targetFuelItem = params.targetFuelItem();
        this.neededCount = Math.max(1, params.neededCount());
    }

    @Override
    public boolean isFinished() {
        // The framework may query isFinished() before the first tick assigns controller — guard the
        // null-controller window (mirrors ToolAcquisitionTask). Not finished until the inventory read
        // can run and shows enough fuel.
        if (this.controller == null) {
            return false;
        }
        return heldInventoryOnly() >= neededCount;
    }

    @Override
    protected void onStart() {
        this.phase = isFinished() ? Phase.DONE : Phase.CHECK_INVENTORY;
        setDebugState(phase.name());
    }

    @Override
    protected Task onTick() {
        if (isFinished()) {
            this.phase = Phase.DONE;
            setDebugState(phase.name());
            return null;
        }

        switch (phase) {
            case CHECK_INVENTORY:
                this.phase = Phase.SEARCH_MARKED;
                report("looking for " + fuelWord() + " in marked storage...", false);
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
            Vec3 origin = this.controller.getPlayer().position();
            String dimensionId = dimensionId();
            List<BlockPos> coords = MarkedChestToolLocator.candidateCoordinates(
                    targetFuelItem, origin, params.radius(), dimensionId);
            for (BlockPos pos : coords) {
                if (!triedContainers.contains(pos)) {
                    markedCandidates.add(pos);
                }
            }
            if (markedCandidates.isEmpty()) {
                this.phase = Phase.SEARCH_UNMARKED;
                report("nothing marked; checking nearby chests for " + fuelWord() + "...", false);
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
            Vec3 origin = this.controller.getPlayer().position();
            double radiusSq = params.radius() * params.radius();
            List<BlockPos> chests = new ArrayList<>();
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
                Optional<ContainerCache> cache = this.controller.getItemStorage().getContainerAtPosition(pos);
                if (cache.isEmpty()) {
                    continue; // conservative v1: never blind-travel to an unconfirmed chest
                }
                if (cache.get().hasItem(targetFuelItem)) {
                    unmarkedCandidates.add(pos);
                }
            }
            if (unmarkedCandidates.isEmpty()) {
                this.phase = Phase.CLIMB_CHAIN;
                report("no chest has " + fuelWord() + "; gathering it from the world...", false);
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
        if (withdrawChild != null) {
            if (!withdrawChild.isFinished() && !withdrawChild.stopped()) {
                return withdrawChild; // still running
            }
            // Child finished (success or shortfall). Reserves NOTHING — gathered fuel just lands in
            // inventory; FuelPlanner reserves on the wrapper's re-run.
            withdrawChild = null;
            activeWithdrawPos = null;
            if (isFinished()) {
                this.phase = Phase.DONE;
                setDebugState(phase.name());
                return null;
            }
            // Not yet satisfied — fall through to the next candidate / stage below.
        }

        boolean marked = searchPhaseToReturnTo == Phase.SEARCH_MARKED;
        int stageAttempts = marked ? markedAttempts : unmarkedAttempts;
        if (stageAttempts >= maxContainers() || candidates.isEmpty()) {
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
        // Clamp the withdraw to the REMAINING deficit so the bot never over-withdraws past neededCount.
        int remaining = Math.max(1, neededCount - heldInventoryOnly());
        List<StorageItemArgs.ItemQuery> queries = List.of(
                new StorageItemArgs.ItemQuery(targetFuelItem, ItemHelper.stripItemName(targetFuelItem), remaining));
        withdrawChild = new BoundedContainerTransferTask(
                ScanReportFormatter.TransferDirection.WITHDRAW, pos, queries);
        report("withdrawing " + fuelWord() + " from a chest at "
                + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "...", false);
        setDebugState(phase.name() + "@" + pos.toShortString());
        return withdrawChild;
    }

    private Phase nextStageAfterWithdraw(Phase searchPhase) {
        return searchPhase == Phase.SEARCH_MARKED ? Phase.SEARCH_UNMARKED : Phase.CLIMB_CHAIN;
    }

    // ------------------------------------------------------------------ stage (d): world acquisition

    private Task climbChain() {
        if (climbChild != null) {
            if (!climbChild.isFinished() && !climbChild.stopped()) {
                return climbChild; // gather child still working
            }
            climbChild = null;
            if (isFinished()) {
                this.phase = Phase.DONE;
                setDebugState(phase.name());
                return null;
            }
            // The single climb attempt finished without covering the deficit — partial/zero. Terminate
            // (the wrapper handles "some fuel but not enough" via its post-gather plan re-run and clamp).
            terminateFailed("no_acquirable_fuel");
            return null;
        }

        if (climbStarted) {
            // Defensive: a single attempt is the bound. If we re-enter with climbChild already cleared
            // and not finished, do not start a second attempt.
            terminateFailed("no_acquirable_fuel");
            return null;
        }

        if (isCoal()) {
            // HARD spiral guard: only mine coal when a wood-tier pickaxe is already HELD inventory-only.
            if (!StorageHelper.miningRequirementMetInventory(this.controller, MiningRequirement.WOOD)) {
                terminateFailed("no_acquirable_fuel");
                return null;
            }
            // Catalogued coal mine (Blocks.COAL_ORE/DEEPSLATE_COAL_ORE, MiningRequirement.WOOD) — a
            // single bounded attempt for exactly the deficit. The catalogue gates the mine on a wood
            // pickaxe too; we never construct a ToolAcquisitionTask here.
            Task child = TaskCatalogue.getItemTask(Items.COAL, neededCount);
            if (child == null) {
                terminateFailed("no_acquirable_fuel");
                return null;
            }
            climbChild = child;
            climbStarted = true;
            report("mining coal for fuel...", false);
            setDebugState(phase.name() + ":coal");
            return climbChild;
        }

        // Planks/logs target: chop natural logs with HAND and craft planks (NON-recursive — never spawns
        // a ToolAcquisitionTask). The deficit is the plank-unit count to acquire.
        climbChild = new CollectPlanksTask(neededCount);
        climbStarted = true;
        report("chopping wood for fuel...", false);
        setDebugState(phase.name() + ":planks");
        return climbChild;
    }

    // ---------------------------------------------------------------------------------- terminal

    private void terminateFailed(String reason) {
        this.phase = Phase.FAILED;
        this.finished = false;
        setDebugState("FAILED:" + reason);
        stopChildren();
        this.controller.log("[Agentic] fuel_gather: " + reason + " (item=" + fuelWord()
                + " need=" + neededCount + ")");
        report("couldn't gather " + fuelWord() + ": " + reason, true);
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    private void stopChildren() {
        if (withdrawChild != null && !withdrawChild.stopped()) {
            withdrawChild.stop(this);
        }
        if (climbChild != null && !climbChild.stopped()) {
            climbChild.stop(this);
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Mirror ToolAcquisitionTask / GatherLooseItemsTask: only treat as a fresh interruption if not
        // already terminal, so terminateFailed's self-stop does not recurse.
        if (!finished && phase != Phase.DONE && phase != Phase.FAILED && !isFinished()) {
            this.phase = Phase.FAILED;
            setDebugState("FAILED:interrupted");
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof FuelGatherTask task
                && task.targetFuelItem == this.targetFuelItem
                && task.neededCount == this.neededCount;
    }

    @Override
    protected String toDebugString() {
        return "FuelGather[" + fuelWord() + " x" + neededCount + "]";
    }

    // ----------------------------------------------------------------------------------- helpers

    private int heldInventoryOnly() {
        return this.controller.getItemStorage().getItemCountInventoryOnly(targetFuelItem);
    }

    private boolean isCoal() {
        return targetFuelItem == Items.COAL;
    }

    private int maxContainers() {
        return Math.max(1, params.maxContainers());
    }

    private String fuelWord() {
        try {
            return BuiltInRegistries.ITEM.getKey(targetFuelItem).getPath().replace('_', ' ');
        } catch (Exception e) {
            return "fuel";
        }
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
