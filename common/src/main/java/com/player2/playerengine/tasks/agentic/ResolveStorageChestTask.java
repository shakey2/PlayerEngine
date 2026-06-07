package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStorageTarget;
import com.player2.playerengine.agentic.StorageTargetSource;
import com.player2.playerengine.agentic.steps.ResolveStorageChestParams;
import com.player2.playerengine.agentic.storage.ChestPlacementCandidate;
import com.player2.playerengine.agentic.storage.ChestPlacementSelector;
import com.player2.playerengine.agentic.storage.StorageChestCandidate;
import com.player2.playerengine.agentic.storage.StorageChestScanner;
import com.player2.playerengine.agentic.storage.StorageChestValidation;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceItemBlockAtPosTask;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.crafting.CraftMacroPhase;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Resolves a storage chest by scan or single placement (C2; no deposit). */
public final class ResolveStorageChestTask extends Task implements DescribesProgress {

    private enum Phase {
        SCANNING_EXISTING,
        MOVING_TO_EXISTING,
        ENSURING_CHEST_ITEM,
        SELECTING_PLACEMENT,
        PLACING_CHEST,
        VERIFYING_TARGET,
        DONE,
        FAILED
    }

    private final ResolveStorageChestParams params;
    private final AgenticExecutionContext context;
    private Phase phase = Phase.SCANNING_EXISTING;
    private boolean finished;
    private long startMs;
    private Task child;
    private StorageChestCandidate existingCandidate;
    private ChestPlacementCandidate placementCandidate;
    private BlockPos targetPos;
    private StorageTargetSource targetSource = StorageTargetSource.EXISTING_UNKNOWN;
    private int placementAttempts = 0;
    private int obtainAttempts = 0;
    private static final int MAX_OBTAIN_ATTEMPTS = 4;
    // Give-up bound for a CONTINUOUS stuck wander while obtaining chest materials (Fix 1). A
    // productive mine->craft cycle resets this timer; only an uninterrupted ~25s wander trips it.
    //
    // Stopgap reconciliation (see masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md
    // WS5 / decision 9): the global fixes shipped in that plan -- the type-aware aggregate count
    // (MaterialAvailability sufficiency axis, so "held enough logs already" stops the obtain child
    // promptly without over-mining) and the bounded MineOrCollectTask wander
    // (wanderBoundDefaultSeconds, default 90s, which self-terminates the obtain subtree's wander)
    // -- make this 25s give-up redundant in the common case. The guard is KEPT as a local backstop
    // for the ENSURING_CHEST_ITEM phase (the global bound fires first in the normal case; only a
    // genuinely-unobtainable material trips this local guard). Ordering: 25s continuous-wander
    // give-up fires first if truly stuck; the global 90s deadline on the bounded wander fires next;
    // params.timeoutSeconds() (default 180s absolute) is the final backstop. No triple-timeout
    // off-by-one: the three clocks are independent and the first to fire terminates the child. The
    // wander timer resets whenever any child is returned, so the step always terminates finitely.
    // The 25s clock measures only uninterrupted wander time and resets on any mining/crafting
    // progress, so it never collides with (or pre-empts) the absolute deadline's independent
    // total-elapsed clock.
    private static final double OBTAIN_STUCK_GIVE_UP_SECONDS = 25.0;
    private long obtainWanderStartMs = -1;

    public ResolveStorageChestTask(ResolveStorageChestParams params, AgenticExecutionContext context) {
        this.params = params;
        this.context = context;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String describeProgress() {
        String target = targetPos != null
                ? String.format(Locale.ROOT, "%d %d %d", targetPos.getX(), targetPos.getY(), targetPos.getZ())
                : "none";
        return String.format(Locale.ROOT, "phase=%s target=%s source=%s",
                phase.name().toLowerCase(Locale.ROOT), target, targetSource);
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        updateProgress("scanning for nearby chest");
        report("scanning for a nearby chest", false);
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        if (phase == Phase.FAILED) {
            return null;
        }
        // Enforce the overall deadline before (potentially) returning an active child, so the
        // resolve step always terminates finitely even while a child task is running.
        if (elapsedSec() >= params.timeoutSeconds()) {
            terminateFailed("timeout");
            return null;
        }
        if (child != null) {
            if (child.isActive() && !child.stopped()) {
                if (phase == Phase.ENSURING_CHEST_ITEM) {
                    if (!isChestObtainChildComplete()) {
                        // Fix 1: bound a CONTINUOUS stuck wander inside the obtain subtree. If the
                        // child is mining/crafting (making progress) the wander timer resets; only an
                        // uninterrupted wander for OBTAIN_STUCK_GIVE_UP_SECONDS gives up fast. The
                        // absolute params.timeoutSeconds() check above stays as the backstop.
                        if (child.containsTask(t -> t
                                instanceof com.player2.playerengine.tasks.movement.TimeoutWanderTask)) {
                            long now = System.currentTimeMillis();
                            if (obtainWanderStartMs < 0) {
                                obtainWanderStartMs = now;
                            } else if ((now - obtainWanderStartMs) / 1000.0
                                    >= OBTAIN_STUCK_GIVE_UP_SECONDS) {
                                terminateFailed("could_not_obtain_chest_materials");
                                return null;
                            }
                        } else {
                            obtainWanderStartMs = -1;
                        }
                        return child;
                    }
                } else if (!child.isFinished()) {
                    return child;
                }
            }
            child = null;
        }

        return switch (phase) {
            case SCANNING_EXISTING -> tickScanExisting();
            case MOVING_TO_EXISTING -> tickMoveExisting();
            case ENSURING_CHEST_ITEM -> tickEnsureChestItem();
            case SELECTING_PLACEMENT -> tickSelectPlacement();
            case PLACING_CHEST -> tickPlaceChest();
            case VERIFYING_TARGET -> tickVerify();
            case DONE, FAILED -> null;
        };
    }

    private Task tickScanExisting() {
        if (!params.preferExisting()) {
            if (!params.allowPlacement()) {
                // Do NOT pre-set phase=FAILED here: terminateFailed sets it and then self-stops,
                // and its guard short-circuits if phase is already FAILED (which would skip the
                // self-stop and leave the task in an infinite-idle hang).
                terminateFailed("no_storage_chest_found");
            } else {
                phase = Phase.ENSURING_CHEST_ITEM;
                updateProgress("no existing chest; obtaining chest item");
                report("no chest nearby; obtaining materials", false);
            }
            return null;
        }
        StorageChestScanner.ScanResult scan = StorageChestScanner.scan(this.controller, params);
        if (scan.best().isPresent()) {
            existingCandidate = scan.best().get();
            targetPos = existingCandidate.pos();
            targetSource = existingCandidate.source();
            phase = Phase.MOVING_TO_EXISTING;
            updateProgress("moving to existing chest at " + formatPos(targetPos));
            report("found existing chest at " + formatPos(targetPos), false);
            child = new GetToBlockTask(targetPos);
            return child;
        }
        if (!params.allowPlacement()) {
            terminateFailed("no_storage_chest_found");
            return null;
        }
        phase = Phase.ENSURING_CHEST_ITEM;
        updateProgress("no existing chest; obtaining chest item");
        report("no chest nearby; obtaining materials", false);
        return null;
    }

    private Task tickMoveExisting() {
        if (targetPos == null) {
            terminateFailed("no_target");
            return null;
        }
        Optional<String> reject = StorageChestValidation.validateExistingCandidate(
                this.controller, targetPos, params.searchRadius() * params.searchRadius(), params.avoidLootChests());
        if (reject.isPresent()) {
            existingCandidate = null;
            targetPos = null;
            phase = Phase.SCANNING_EXISTING;
            updateProgress("existing chest became invalid; rescanning");
            return null;
        }
        recordTarget();
        succeed("resolved existing chest at " + formatPos(targetPos) + " (" + targetSource + ")");
        return null;
    }

    private Task tickEnsureChestItem() {
        if (this.controller.getItemStorage().hasItem(Items.CHEST)) {
            phase = Phase.SELECTING_PLACEMENT;
            updateProgress("selecting placement site");
            return null;
        }
        if (child != null) {
            return child;
        }
        // Bound the number of obtain attempts so a child that keeps finishing without producing a
        // chest cannot respawn indefinitely (the overall timeout is absolute and never reset).
        if (obtainAttempts >= MAX_OBTAIN_ATTEMPTS) {
            terminateFailed("no_chest_item");
            return null;
        }
        obtainAttempts++;
        ItemTarget chestTarget = new ItemTarget(Items.CHEST, 1);
        Task macro = CraftMacroTasks.tryCreateMacroTask(this.controller, chestTarget);
        if (macro != null) {
            updateProgress("obtaining chest item (craft macro)");
            child = macro;
            return child;
        }
        updateProgress("obtaining chest item (catalogue)");
        child = TaskCatalogue.getItemTask(Items.CHEST, 1);
        return child;
    }

    /** Craft-macro children must actually produce a chest, not merely satisfy {@link com.player2.playerengine.tasks.ResourceTask#isFinished()}. */
    private boolean isChestObtainChildComplete() {
        if (child instanceof CraftMacroResourceTask macro) {
            return macro.getPhase() == CraftMacroPhase.DONE
                    && this.controller.getItemStorage().hasItem(Items.CHEST);
        }
        return child.isFinished() && this.controller.getItemStorage().hasItem(Items.CHEST);
    }

    private Task tickSelectPlacement() {
        if (!this.controller.getItemStorage().hasItem(Items.CHEST)) {
            terminateFailed("no_chest_item");
            return null;
        }
        Vec3 origin = this.controller.getPlayer().position();
        ChestPlacementSelector.SelectResult result = ChestPlacementSelector.select(
                this.controller, origin, params.placementRadius());
        if (result.best().isEmpty()) {
            terminateFailed("no_valid_site");
            return null;
        }
        placementCandidate = result.best().get();
        targetPos = placementCandidate.pos();
        phase = Phase.PLACING_CHEST;
        updateProgress("placing chest at " + formatPos(targetPos));
        report("placing chest at " + formatPos(targetPos), false);
        child = new PlaceItemBlockAtPosTask(targetPos, Blocks.CHEST);
        return child;
    }

    private Task tickPlaceChest() {
        if (targetPos == null) {
            terminateFailed("placement_failed");
            return null;
        }
        if (!WorldHelper.isBlock(this.controller, targetPos, Blocks.CHEST)) {
            if (placementAttempts++ < 1) {
                phase = Phase.SELECTING_PLACEMENT;
                updateProgress("placement retry; reselecting site");
                return null;
            }
            terminateFailed("placement_unverified");
            return null;
        }
        targetSource = StorageTargetSource.PLACED_BY_BOT;
        phase = Phase.VERIFYING_TARGET;
        updateProgress("verifying chest at " + formatPos(targetPos));
        report("verifying chest placement", false);
        return null;
    }

    private Task tickVerify() {
        if (targetPos == null || !StorageChestValidation.isVanillaStorageChestBlock(
                this.controller.getWorld().getBlockState(targetPos).getBlock())) {
            terminateFailed("placement_unverified");
            return null;
        }
        if (!WorldHelper.canReach(this.controller, targetPos)) {
            terminateFailed("target_unreachable");
            return null;
        }
        recordTarget();
        succeed("placed chest at " + formatPos(targetPos) + " (PLACED_BY_BOT)");
        return null;
    }

    private void recordTarget() {
        if (targetPos == null || context == null) {
            return;
        }
        String blockId = ItemHelper.stripItemName(
                this.controller.getWorld().getBlockState(targetPos).getBlock().asItem());
        AgenticStorageTarget target = new AgenticStorageTarget(
                targetPos,
                this.controller.getWorld().dimension().location().toString(),
                blockId,
                targetSource,
                targetSource == StorageTargetSource.PLACED_BY_BOT,
                this.controller.getWorld().getGameTime());
        context.memory().setStorageTarget(target);
        if (context.runState() != null) {
            context.runState().setStorageTargetSummary(
                    formatPos(targetPos) + " " + targetSource.name().toLowerCase(Locale.ROOT));
        }
    }

    private void succeed(String message) {
        phase = Phase.DONE;
        finished = true;
        updateProgress(message);
        report("chest ready at " + formatPos(targetPos), true);
        this.controller.log("[Agentic] resolve_storage_chest: " + message);
    }

    private void terminateFailed(String reason) {
        if (phase == Phase.FAILED) {
            return;
        }
        phase = Phase.FAILED;
        finished = false;
        if (child != null && !child.stopped()) {
            child.stop(this);
        }
        child = null;
        updateProgress(reason);
        // Mid-step actionable note (milestone -> bypasses throttle). Emitted here, before the executor's
        // terminal("failed", ...) runs, so the run-state terminal guard does not suppress it.
        report("could not resolve chest: " + describeResolveFailure(reason), true);
        this.controller.log("[Agentic] resolve_storage_chest failed: " + reason);
        // Self-stop so SingleTaskChain takes the forced-stop path (stopped()==true while
        // isFinished()==false) and reaches onTaskFinish; the adapter then records FAILED and
        // AgenticPlanExecutor surfaces terminal("failed", ...). phase is FAILED above so the
        // onStop override no-ops (no rollback; policy stays NONE). Without this the chain never
        // reaches onTaskFinish -> silent infinite idle + leaked run-registry entry.
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    /** Maps a resolve failure-vocabulary code to a concise player-readable cause. Reuses, never redefines. */
    private static String describeResolveFailure(String reason) {
        return switch (reason) {
            case "no_storage_chest_found" -> "no chest found and placement not allowed";
            case "no_chest_item" -> "could not obtain a chest";
            case "could_not_obtain_chest_materials" -> "could not obtain chest materials";
            case "no_valid_site" -> "no valid chest site nearby";
            case "placement_failed", "placement_unverified" -> "chest placement failed";
            case "target_unreachable" -> "chest site unreachable";
            case "no_target" -> "lost the chest target";
            case "timeout" -> "timed out";
            default -> reason;
        };
    }

    private void updateProgress(String message) {
        if (context != null && context.runState() != null) {
            context.runState().setStorageProgress(message);
        }
        this.setDebugState(phase.name());
    }

    /**
     * Player-facing progress note via the controller seam (WS1/WS2). Guarded by the run-state
     * terminal flag so a late callback cannot overwrite a failure line. Uses {@code context.controller()}
     * (this task holds an AgenticExecutionContext).
     */
    private void report(String message, boolean milestone) {
        if (context == null) {
            return;
        }
        if (context.runState() != null && context.runState().isTerminal()) {
            return;
        }
        context.controller().reportAgenticProgress(message, milestone);
    }

    private double elapsedSec() {
        return (System.currentTimeMillis() - startMs) / 1000.0;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Override
    protected void onStop(Task interruptTask) {
        if (finished || phase == Phase.DONE || phase == Phase.FAILED) {
            return;
        }
        phase = Phase.FAILED;
        finished = false;
        if (child != null && !child.stopped()) {
            child.stop(interruptTask);
        }
        child = null;
        updateProgress("interrupted");
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ResolveStorageChestTask task && task.params.equals(this.params);
    }

    @Override
    protected String toDebugString() {
        return "ResolveStorageChest";
    }
}
