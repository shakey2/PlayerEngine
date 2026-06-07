package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticRunRegistry;
import com.player2.playerengine.agentic.steps.GatherLooseItemsParams;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.tasks.movement.PickupDroppedItemTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.slot.EnsureFreeInventorySlotTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Bounded nearby loose-item gather step for agentic C1 plans. */
public final class GatherLooseItemsTask extends Task implements DescribesProgress {

    private enum Phase {
        SCANNING, MOVING_TO_DROP, FREEING_INVENTORY, SETTLING, DONE, PARTIAL, FAILED
    }

    private final GatherLooseItemsParams params;
    private final AgenticRunRegistry.AgenticRunState runState;
    private Vec3 origin;
    private Phase phase = Phase.SCANNING;
    private boolean finished;
    private int gatheredItems;
    private int skippedUnreachable;
    private long startMs;
    private long settleStartMs = -1;
    private PickupDroppedItemTask pickupChild;
    private ItemEntity currentTarget;
    private final Set<Integer> blacklistedEntityIds = new HashSet<>();
    private int inventoryBaselineCount;
    private boolean triedFreeInventory;

    public GatherLooseItemsTask(GatherLooseItemsParams params, AgenticRunRegistry.AgenticRunState runState) {
        this.params = params;
        this.runState = runState;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String describeProgress() {
        int remaining = countEligibleDrops();
        String target = currentTarget != null && !currentTarget.getItem().isEmpty()
                ? ItemHelper.stripItemName(currentTarget.getItem().getItem())
                : "none";
        return String.format(
                Locale.ROOT,
                "phase=%s gathered=%d skipped=%d remaining=%d target=%s",
                phase.name().toLowerCase(Locale.ROOT),
                gatheredItems,
                skippedUnreachable,
                remaining,
                target);
    }

    @Override
    protected void onStart() {
        this.origin = this.controller.getPlayer().position();
        this.startMs = System.currentTimeMillis();
        this.inventoryBaselineCount = countInventoryItems(this.controller);
        this.phase = Phase.SCANNING;
        updateRunProgress();
        report("gathering loose " + describeFilter(), false);
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        double elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0;
        if (elapsedSec >= params.timeoutSeconds()) {
            if (gatheredItems > 0) {
                finishRun(Phase.PARTIAL, "timeout after partial gather");
            } else {
                finishRun(Phase.FAILED, "timeout with drops still nearby");
            }
            return null;
        }
        if (gatheredItems >= params.maxItems()) {
            finishRun(Phase.DONE, "reached maxItems");
            return null;
        }

        List<ItemEntity> drops = eligibleDrops();
        if (drops.isEmpty()) {
            this.phase = Phase.SETTLING;
            if (settleStartMs < 0) {
                settleStartMs = System.currentTimeMillis();
            }
            double settleElapsed = (System.currentTimeMillis() - settleStartMs) / 1000.0;
            if (settleElapsed >= params.settleSeconds()) {
                if (gatheredItems == 0) {
                    finishRun(Phase.DONE, "no eligible drops nearby");
                } else {
                    finishRun(Phase.DONE, "gather complete");
                }
            }
            updateRunProgress();
            return null;
        }
        settleStartMs = -1;

        if (!hasFreeInventorySlot(this.controller)) {
            if (params.freeInventoryIfFull() && !triedFreeInventory) {
                this.phase = Phase.FREEING_INVENTORY;
                triedFreeInventory = true;
                updateRunProgress();
                report("inventory full; freeing a slot", false);
                return new EnsureFreeInventorySlotTask();
            }
            if (gatheredItems > 0) {
                finishRun(Phase.PARTIAL, "inventory full");
            } else {
                finishRun(Phase.FAILED, "inventory full");
            }
            return null;
        }
        triedFreeInventory = false;

        ItemEntity nearest = pickNearest(drops);
        if (nearest == null) {
            finishRun(gatheredItems > 0 ? Phase.PARTIAL : Phase.DONE, "no reachable drops");
            return null;
        }

        if (pickupChild == null || currentTarget == null || currentTarget.getId() != nearest.getId()
                || pickupChild.stopped()) {
            currentTarget = nearest;
            Item item = nearest.getItem().getItem();
            int count = Math.max(1, nearest.getItem().getCount());
            pickupChild = new PickupDroppedItemTask(new ItemTarget(item, count), params.freeInventoryIfFull());
            this.phase = Phase.MOVING_TO_DROP;
            report("moving to " + ItemHelper.stripItemName(item) + " drop", false);
        }

        int before = gatheredItems;
        detectGatheredFromInventory();
        if (gatheredItems > before) {
            pickupChild = null;
            currentTarget = null;
            updateRunProgress();
            return null;
        }

        if (currentTarget.isRemoved() || currentTarget.getItem().isEmpty()) {
            gatheredItems++;
            pickupChild = null;
            currentTarget = null;
            updateRunProgress();
            return null;
        }

        updateRunProgress();
        return pickupChild;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Only treat this as a fresh interruption if the task has not already reached a terminal
        // phase. finishRun(Phase.FAILED, ...) self-stops, which re-enters onStop; without this
        // guard that would recurse and overwrite the real failure reason with "interrupted".
        if (!finished && !isTerminalPhase()) {
            finishRun(Phase.FAILED, "interrupted");
        }
    }

    private boolean isTerminalPhase() {
        return phase == Phase.DONE || phase == Phase.PARTIAL || phase == Phase.FAILED;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GatherLooseItemsTask task && task.params.equals(this.params);
    }

    @Override
    protected String toDebugString() {
        return "GatherLooseItems";
    }

    private List<ItemEntity> eligibleDrops() {
        return this.controller.getEntityTracker().getItemDropsWithin(
                origin,
                params.radius(),
                entity -> !entity.isRemoved()
                        && !blacklistedEntityIds.contains(entity.getId())
                        && matchesFilter(entity));
    }

    private int countEligibleDrops() {
        return eligibleDrops().size();
    }

    private boolean matchesFilter(ItemEntity entity) {
        if (params.itemIdFilters().isEmpty()) {
            return true;
        }
        String name = ItemHelper.stripItemName(entity.getItem().getItem()).toLowerCase(Locale.ROOT);
        for (String filter : params.itemIdFilters()) {
            if (name.contains(filter) || filter.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private ItemEntity pickNearest(List<ItemEntity> drops) {
        Vec3 pos = this.controller.getPlayer().position();
        return drops.stream()
                .filter(e -> this.controller.getEntityTracker().isEntityReachable(e))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(pos)))
                .orElseGet(() -> {
                    if (!drops.isEmpty()) {
                        ItemEntity blocked = drops.get(0);
                        blacklistedEntityIds.add(blocked.getId());
                        this.controller.getEntityTracker().requestEntityUnreachable(blocked);
                        skippedUnreachable++;
                    }
                    return null;
                });
    }

    private void detectGatheredFromInventory() {
        int now = countInventoryItems(this.controller);
        int delta = now - inventoryBaselineCount;
        if (delta > 0) {
            gatheredItems += delta;
            inventoryBaselineCount = now;
        }
    }

    private static int countInventoryItems(PlayerEngineController mod) {
        int total = 0;
        var inv = mod.getBaritone().getEntityContext().inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean hasFreeInventorySlot(PlayerEngineController mod) {
        var inv = mod.getBaritone().getEntityContext().inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void finishRun(Phase endPhase, String message) {
        this.phase = endPhase;
        // Map the outcome to the correct tracked step state (see TaskStepExecutorAdapter):
        //  - DONE (clean) and PARTIAL (some items gathered) -> finished=true -> SUCCEEDED step,
        //    with a clear partial/degradation note kept in run state (part-c1-plan Workstream 5/7:
        //    a partial gather that made progress is an acceptable, visible outcome).
        //  - genuine FAILED (zero net progress) -> self-stop without finished so the chain takes
        //    the forced-stop path (stopped()==true while isFinished()==false) -> FAILED step.
        // Marking a FAILED outcome finished=true (the previous behavior) wrongly mapped to SUCCEEDED.
        boolean failure = endPhase == Phase.FAILED;
        this.finished = !failure;
        this.setDebugState(endPhase.name());
        if (runState != null) {
            runState.setGatherProgress(describeProgress() + " — " + message);
            if (endPhase == Phase.PARTIAL) {
                runState.setGatherDegraded(
                        AgenticRunRegistry.DegradationLevel.PARTIAL, message);
            }
        }
        this.controller.log("[Agentic] gather_loose_items: " + message + " (" + describeProgress() + ")");
        // Terminal player note (milestone -> bypasses the interval throttle). PARTIAL outcomes are
        // surfaced explicitly so a degraded gather is visible, not silently treated as a clean done.
        String prefix = switch (endPhase) {
            case PARTIAL -> "gather partial: ";
            case FAILED -> "gather failed: ";
            default -> "gathered " + gatheredItems + " item(s): ";
        };
        report(prefix + message, true);
        if (failure && !this.stopped()) {
            this.stop(this);
        }
    }

    private void updateRunProgress() {
        if (runState != null) {
            runState.setGatherProgress(describeProgress());
        }
        this.setDebugState(phase.name());
    }

    /** Human-readable summary of the gather filter for the start note. */
    private String describeFilter() {
        if (params.itemIdFilters().isEmpty()) {
            return "items nearby";
        }
        return String.join(", ", params.itemIdFilters());
    }

    /**
     * Player-facing progress note via the controller seam (WS1/WS2). Guarded by the run-state
     * terminal flag so a late callback cannot overwrite a failure line after the run has terminated.
     * Uses {@code this.controller} (the Task base-class field): this task has no AgenticExecutionContext.
     */
    private void report(String message, boolean milestone) {
        if (runState != null && runState.isTerminal()) {
            return;
        }
        this.controller.reportAgenticProgress(message, milestone);
    }
}
