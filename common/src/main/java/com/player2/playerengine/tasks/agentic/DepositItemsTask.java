package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStorageTarget;
import com.player2.playerengine.agentic.steps.DepositItemsParams;
import com.player2.playerengine.agentic.storage.StorageChestValidation;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerDepositTask;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * Bounded C3 {@code deposit_items} step: moves the selected items from the bot's inventory into the
 * C2-resolved storage chest, terminating finitely and surfacing full/partial/nothing/target-changed/
 * unreachable/timeout outcomes visibly.
 *
 * <p>After READING_TARGET + REVALIDATING, the actual insert is delegated to the shared
 * {@link com.player2.playerengine.tasks.container.BoundedContainerDepositTask} (the one bounded
 * deposit core), and its typed outcome is mapped back to C3's exact success-string vocabulary. This
 * task keeps the {@code Phase} enum, the absolute timeout enforced before any child is returned,
 * {@code succeed()} setting {@code finished=true}, and {@code terminateFailed()} self-stopping with
 * {@code this.stop(this)} so {@code SingleTaskChain} records FAILED. It does NOT re-implement a
 * low-level container mover and does NOT use {@code StoreInAnyContainerTask}.
 *
 * <p>The version-divergent {@code isSameItemSameComponents}/{@code isSameItemSameTags} item-equality
 * line lives entirely inside {@code StoreInContainerTask} (reached via the bounded core); C3 never writes it.
 */
public final class DepositItemsTask extends Task implements DescribesProgress {

    private enum Phase {
        READING_TARGET,
        REVALIDATING,
        DEPOSITING,
        DONE,
        FAILED
    }

    /** Generous revalidation radius; StoreInContainerTask self-navigates, so range never blocks. */
    private static final double REVALIDATE_RADIUS_SQ = 256.0 * 256.0;

    private final DepositItemsParams params;
    private final AgenticExecutionContext context;
    private Phase phase = Phase.READING_TARGET;
    private boolean finished;
    private long startMs;

    /** The shared bounded deposit engine, created lazily on entry to the DEPOSITING phase. */
    private BoundedContainerDepositTask depositCore;

    private BlockPos targetPos;
    private ItemTarget[] targets = new ItemTarget[0];
    private Item[] targetItems = new Item[0];
    private int initialHeldCount;
    private int depositedCount;
    private String resultDetail = "";

    public DepositItemsTask(DepositItemsParams params, AgenticExecutionContext context) {
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
        return String.format(
                Locale.ROOT,
                "phase=%s target=%s targets=%d deposited=%d%s",
                phase.name().toLowerCase(Locale.ROOT),
                target,
                targets.length,
                depositedCount,
                resultDetail.isEmpty() ? "" : " " + resultDetail);
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        updateProgress("reading storage target");
    }

    @Override
    protected Task onTick() {
        if (finished || phase == Phase.FAILED) {
            return null;
        }
        // Absolute deadline first, even while a child is active: StoreInContainerTask has no overall
        // timeout, so without this the deposit step could run forever against a full container.
        if (elapsedSec() >= params.timeoutSeconds()) {
            terminateFailed("timeout");
            return null;
        }

        return switch (phase) {
            case READING_TARGET -> tickReadingTarget();
            case REVALIDATING -> tickRevalidating();
            case DEPOSITING -> tickDepositing();
            case DONE, FAILED -> null;
        };
    }

    private Task tickReadingTarget() {
        Optional<AgenticStorageTarget> t = context != null ? context.memory().storageTarget() : Optional.empty();
        if (t.isEmpty()) {
            terminateFailed("no_storage_target");
            return null;
        }
        AgenticStorageTarget target = t.get();
        String currentDim = this.controller.getWorld().dimension().location().toString();
        if (target.dimension() != null && !target.dimension().equals(currentDim)) {
            terminateFailed("target_wrong_dimension");
            return null;
        }
        this.targetPos = target.pos();
        phase = Phase.REVALIDATING;
        updateProgress("revalidating chest at " + formatPos(targetPos));
        return null;
    }

    private Task tickRevalidating() {
        if (targetPos == null) {
            terminateFailed("no_storage_target");
            return null;
        }
        Optional<String> reject = StorageChestValidation.validateExistingCandidate(
                this.controller, targetPos, REVALIDATE_RADIUS_SQ, false);
        if (reject.isPresent()) {
            String reason = reject.get();
            // container_full here just means the chest is already full; that is a real (partial/no-op)
            // deposit situation we still want to attempt and classify, not a hard failure.
            switch (reason) {
                case "not_chest", "chunk_unloaded" -> {
                    terminateFailed("target_block_changed");
                    return null;
                }
                case "unreachable", "cannot_reach", "blocked_top" -> {
                    terminateFailed("unreachable");
                    return null;
                }
                case "wrong_dimension" -> {
                    terminateFailed("target_wrong_dimension");
                    return null;
                }
                case "container_full" -> {
                    // fall through: build targets and let the deposit/stall guard report container_full.
                }
                default -> {
                    // out_of_radius / loot_chest etc. are not deposit blockers here; proceed.
                }
            }
        }

        this.targets = buildTargets();
        this.targetItems = ItemTarget.getMatches(targets);
        if (targets.length == 0 || targetItems.length == 0) {
            // Empty selection -> no-op success; never start StoreInContainerTask.
            succeed("nothing_to_deposit");
            return null;
        }
        this.initialHeldCount = heldCount();
        if (initialHeldCount <= 0) {
            succeed("nothing_to_deposit");
            return null;
        }
        phase = Phase.DEPOSITING;
        updateProgress("depositing " + targets.length + " item type(s) into " + formatPos(targetPos));
        report(Component.translatable("message.playerengine.deposit.progress.moving", formatPos(targetPos)), false);
        return null;
    }

    private Task tickDepositing() {
        if (depositCore == null) {
            // Delegate the actual insert + stall/timeout guard to the shared bounded engine. The
            // outer absolute deadline (checked first in onTick) remains authoritative; we hand the
            // core the REMAINING budget so it can never overrun this step's overall timeout.
            double remaining = Math.max(0.0, params.timeoutSeconds() - elapsedSec());
            depositCore = new BoundedContainerDepositTask(targetPos, remaining, targets);
        }

        if (depositCore.isFinished()) {
            mapCoreOutcome();
            return null;
        }

        // Mirror live progress from the core so describeProgress()/setDepositProgress stay identical.
        this.depositedCount = depositCore.depositedCount();
        updateProgress("depositing (" + (initialHeldCount - heldCount()) + "/" + initialHeldCount + ")");
        report(Component.translatable("message.playerengine.deposit.progress.depositing", (initialHeldCount - heldCount()), initialHeldCount), false);
        return depositCore;
    }

    /**
     * Maps the shared {@link BoundedContainerDepositTask.Outcome} back to C3's exact success-string
     * vocabulary, preserving observable behavior. TIMEOUT is reported through the existing
     * {@code terminateFailed("timeout")} path so the failure vocabulary is unchanged.
     */
    private void mapCoreOutcome() {
        this.depositedCount = depositCore.depositedCount();
        switch (depositCore.outcome()) {
            case DEPOSITED -> succeed("deposited " + depositedCount + " item(s)");
            case PARTIAL_CONTAINER_FULL -> succeed("partial: container_full");
            case PARTIAL_REMAINING -> succeed("partial: " + depositCore.remainingCount() + " remaining");
            case NOTHING -> succeed("nothing_to_deposit");
            case TIMEOUT -> terminateFailed("timeout");
        }
    }

    /**
     * Builds the {@link ItemTarget}[] selection inside the task (never inside StoreInContainerTask):
     * explicit ids resolved via {@link BuiltInRegistries#ITEM} using current held counts, else the
     * deposit-all enumeration of the bot inventory with an optional tool/armor/weapon exclusion.
     */
    private ItemTarget[] buildTargets() {
        List<ItemTarget> out = new ArrayList<>();
        if (params.itemIds() != null && !params.itemIds().isEmpty()) {
            for (String id : params.itemIds()) {
                Item item = resolveItemById(id);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                int held = this.controller.getItemStorage().getItemCount(item);
                if (held > 0) {
                    out.add(new ItemTarget(item, held));
                }
            }
            return out.toArray(new ItemTarget[0]);
        }
        if (!params.depositAll()) {
            return new ItemTarget[0];
        }
        // Deposit-all: enumerate the bot inventory, build a distinct item set with summed counts.
        Map<Item, Integer> counts = new LinkedHashMap<>();
        LivingEntityInventory inv = this.controller.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.AIR) {
                continue;
            }
            if (params.keepTools() && StorageHelper.isEssentialItem(item)) {
                continue;
            }
            counts.merge(item, stack.getCount(), Integer::sum);
        }
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                out.add(new ItemTarget(e.getKey(), e.getValue()));
            }
        }
        return out.toArray(new ItemTarget[0]);
    }

    private static Item resolveItemById(String idStr) {
        if (idStr == null || idStr.isBlank()) {
            return null;
        }
        String trimmed = idStr.trim().toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    /** Current held count, in the bot inventory, of all selected target items. */
    private int heldCount() {
        if (targetItems.length == 0) {
            return 0;
        }
        return this.controller.getItemStorage().getItemCount(targetItems);
    }

    private void succeed(String message) {
        phase = Phase.DONE;
        finished = true;
        resultDetail = message;
        if (depositCore != null && !depositCore.stopped()) {
            depositCore.stop(this);
        }
        depositCore = null;
        updateProgress(message);
        // Set structured degraded signal on partial/nothing branches. The clean
        // "deposited N item(s)" path leaves depositDegradation at its CLEAN default.
        if (context != null && context.runState() != null) {
            if (message.equals("partial: container_full")
                    || (message.startsWith("partial: ") && message.endsWith(" remaining"))) {
                context.runState().setDepositDegraded(
                        com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel.PARTIAL,
                        message);
            } else if (message.equals("nothing_to_deposit")) {
                // Nothing was deposited — a skipped/no-op outcome, not a partial deposit.
                context.runState().setDepositDegraded(
                        com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel.SKIPPED,
                        message);
            }
        }
        // Terminal player note (milestone). Partial/full-chest/no-op outcomes are surfaced explicitly
        // so the player is never misled into thinking everything was stored.
        report(describeDepositOutcome(message), true);
        this.controller.log("[Agentic] deposit_items: " + message);
    }

    private void terminateFailed(String reason) {
        if (phase == Phase.FAILED) {
            return;
        }
        phase = Phase.FAILED;
        finished = false;
        resultDetail = reason;
        if (depositCore != null && !depositCore.stopped()) {
            depositCore.stop(this);
        }
        depositCore = null;
        updateProgress(reason);
        // Mid-step actionable note (milestone), emitted before the executor's terminal("failed", ...).
        report(Component.translatable("message.playerengine.deposit.failed", describeDepositFailureComponent(reason)), true);
        this.controller.log("[Agentic] deposit_items failed: " + reason);
        // Self-stop so SingleTaskChain takes the forced-stop path (stopped()==true while
        // isFinished()==false) and reaches onTaskFinish; the adapter records FAILED and
        // AgenticPlanExecutor surfaces terminal("failed", ...). phase is FAILED above so onStop
        // no-ops (no rollback). Without this the chain never finishes -> silent infinite idle.
        if (!this.stopped()) {
            this.stop(this);
        }
    }

    /**
     * Player-readable rendering of a deposit success-vocabulary outcome string. Partial/full-chest
     * cases are stated explicitly as partial so the player is not misled into thinking all items were
     * stored. Reuses the existing vocabulary; adds no new failure codes.
     */
    private Component describeDepositOutcome(String message) {
        if (message == null) {
            return Component.translatable("message.playerengine.deposit.success.done");
        }
        if (message.equals("partial: container_full")) {
            return Component.translatable("message.playerengine.deposit.success.partial_container_full", depositedCount);
        }
        if (message.startsWith("partial: ") && message.endsWith(" remaining")) {
            String n = message.substring("partial: ".length(), message.length() - " remaining".length());
            return Component.translatable("message.playerengine.deposit.success.partial_remaining", depositedCount, n);
        }
        if (message.equals("nothing_to_deposit")) {
            // Tailored player line (DESIGN.md §3): the no-op nearly always means the requested item
            // was never in inventory and agentic cannot mine/gather it. Tell the player how to fix it
            // in one step instead of a bare "nothing to deposit" that reads like a silent failure.
            return Component.translatable("message.playerengine.deposit.success.nothing_to_deposit");
        }
        if (message.startsWith("deposited ")) {
            return Component.translatable("message.playerengine.deposit.success.deposited", depositedCount);
        }
        return Component.literal(message);
    }

    /** Maps a deposit failure-vocabulary code to a concise player-readable Component. Reuses, never redefines. */
    private static Component describeDepositFailureComponent(String reason) {
        return switch (reason) {
            case "no_storage_target" -> Component.translatable("message.playerengine.deposit.fail.no_storage_target");
            case "target_wrong_dimension" -> Component.translatable("message.playerengine.deposit.fail.wrong_dimension");
            case "target_block_changed" -> Component.translatable("message.playerengine.deposit.fail.block_changed");
            case "unreachable" -> Component.translatable("message.playerengine.deposit.fail.unreachable");
            case "container_full" -> Component.translatable("message.playerengine.deposit.fail.container_full");
            case "timeout" -> Component.translatable("message.playerengine.deposit.fail.timeout");
            default -> Component.literal(reason);
        };
    }

    private void updateProgress(String message) {
        if (context != null && context.runState() != null) {
            context.runState().setDepositProgress(describeProgress() + " — " + message);
        }
        this.setDebugState(phase.name());
    }

    /**
     * Player-facing progress note via the controller seam (WS1/WS2). Guarded by the run-state
     * terminal flag so a late callback cannot overwrite a failure line. Uses {@code context.controller()}.
     * The Component is resolved to a String at this boundary because the downstream
     * {@code reportAgenticProgress} / {@code AgentSideEffects.broadcastChatToPlayer} chain is
     * String-based; passing {@code Component} throughout this task establishes the translation-key
     * system for a future full-Component upgrade of that infrastructure.
     */
    private void report(Component message, boolean milestone) {
        if (context == null) {
            return;
        }
        if (context.runState() != null && context.runState().isTerminal()) {
            return;
        }
        context.controller().reportAgenticProgress(message.getString(), milestone);
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
        if (depositCore != null && !depositCore.stopped()) {
            depositCore.stop(interruptTask);
        }
        depositCore = null;
        updateProgress("interrupted");
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DepositItemsTask task && task.params.equals(this.params);
    }

    @Override
    protected String toDebugString() {
        return "DepositItems";
    }
}
