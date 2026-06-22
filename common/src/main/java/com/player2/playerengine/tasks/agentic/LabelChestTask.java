package com.player2.playerengine.tasks.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticStorageTarget;
import com.player2.playerengine.agentic.steps.LabelChestParams;
import com.player2.playerengine.agentic.steps.SignLabelText;
import com.player2.playerengine.player2api.AiTaskClass;
import com.player2.playerengine.player2api.ConversationHistory;
import com.player2.playerengine.player2api.Player2APIService;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceSignTask;
import com.player2.playerengine.tasks.crafting.CraftMacroPhase;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.crafting.DescribesProgress;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import com.player2.playerengine.util.sign.SignScanSupport;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Best-effort optional {@code label_chest} step (Part C3, Workstream 3).
 *
 * <p>After a successful deposit, this places and writes a sign on or near the C2-resolved chest.
 * When no sign is held it first attempts to CRAFT one via the same craft-macro path {@code get sign}
 * uses (species fundability pick at macro creation; see {@code CraftMacroSupport}), with a
 * progress-refreshed craft deadline borrowed from {@code ResolveStorageChestTask} ISSUE 1.
 * It follows the {@link GatherLooseItemsTask} PARTIAL/best-effort pattern, NOT the
 * {@code ResolveStorageChestTask} self-stop-on-failure pattern: <b>every</b> terminal path ends
 * {@code finished=true} (SUCCEEDED) with a degradation note in run state — including every craft
 * outcome. It must never {@code this.stop(this)} to FAILED, and it must never block or revert the
 * deposit. The executor's halt-on-first-failure semantics are honored by always reporting SUCCEEDED here.
 */
public final class LabelChestTask extends Task implements DescribesProgress {

    private enum Phase {
        READING_TARGET,
        CHECK_SIGN_ITEM,
        CRAFTING_SIGN,
        SELECT_ANCHOR,
        PLACING,
        DONE
    }

    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private final LabelChestParams params;
    private final AgenticExecutionContext context;

    private Phase phase = Phase.READING_TARGET;
    private boolean finished;
    private long startMs;

    private AgenticStorageTarget target;
    private Item signItem;
    private BlockPos anchor;
    private Direction faceTowardAir;
    private PlaceSignTask placeChild;
    // Sign-craft child (CRAFTING_SIGN phase): the SAME craft-macro path "get sign 1" uses, so the
    // species fundability pick and funding-aware recipes run at macro creation with fresh
    // post-chest/table inventory + vicinity state. Single bounded attempt; any failure degrades to
    // a skip (best-effort umbrella preserved).
    private Task craftChild;
    // Progress-refreshed craft deadline, mirroring ResolveStorageChestTask ISSUE 1: an absolute
    // params.timeoutSeconds() clock (planner default 60s) would kill any legitimate from-scratch
    // sign craft (mine 2 logs, possibly craft+place a table, planks, sticks, sign). While the craft
    // macro child is demonstrably advancing (phase advance OR held material gain) lastProgressMs is
    // reset to now; the timeout only fires after timeoutSeconds with NO such progress. A genuine
    // stall stays bounded by the macro's own collectNoGain/collect-stall/table-approach/attempt-cap
    // terminations, which fire first in practice. Outside CRAFTING_SIGN the clock is never
    // refreshed, so the other phases keep effectively-absolute behavior.
    private long lastProgressMs;
    private CraftMacroPhase lastObservedMacroPhase;
    private int lastObservedHeld = -1;
    private boolean placeOutcomeRecorded;
    private String lastMessage = "";

    public LabelChestTask(LabelChestParams params, AgenticExecutionContext context) {
        this.params = params;
        this.context = context;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String describeProgress() {
        String anchorStr = anchor != null
                ? String.format(Locale.ROOT, "%d %d %d", anchor.getX(), anchor.getY(), anchor.getZ())
                : "none";
        return String.format(Locale.ROOT, "phase=%s anchor=%s face=%s%s",
                phase.name().toLowerCase(Locale.ROOT),
                anchorStr,
                faceTowardAir != null ? faceTowardAir.getName() : "none",
                lastMessage.isEmpty() ? "" : " — " + lastMessage);
    }

    @Override
    protected void onStart() {
        this.startMs = System.currentTimeMillis();
        this.lastProgressMs = this.startMs;
        this.phase = Phase.READING_TARGET;
        // Respect the feature toggle: if labeling is disabled, no-op SUCCEEDED immediately.
        if (!context.settings().isAgenticEnableLabelChest()) {
            finishWithWarning("label_disabled");
            return;
        }
        updateProgress();
        report("labeling chest", false);
    }

    @Override
    protected Task onTick() {
        if (finished) {
            return null;
        }
        // Timeout enforced FIRST, even while a child is active. Always SUCCEEDED. The deadline is
        // PROGRESS-REFRESHED while the sign-craft macro is genuinely advancing (ResolveStorageChestTask
        // ISSUE 1 pattern — see the field comment on lastProgressMs); outside CRAFTING_SIGN it behaves
        // like the original absolute clock. A craft-phase expiry reports a craft-specific reason so
        // both audiences know a craft was attempted (DESIGN.md §3), never plain "label_timeout".
        boolean macroMakingProgress = phase == Phase.CRAFTING_SIGN
                && craftChild instanceof CraftMacroResourceTask macro
                && macro.getPhase() != CraftMacroPhase.DONE
                && detectAndRefreshMacroProgress(macro);
        if (!macroMakingProgress) {
            double idleSec = (System.currentTimeMillis() - lastProgressMs) / 1000.0;
            if (idleSec >= params.timeoutSeconds()) {
                finishWithWarning(phase == Phase.CRAFTING_SIGN
                        ? "sign_craft_failed: no craft progress, timed out"
                        : "label_timeout");
                return null;
            }
        }

        return switch (phase) {
            case READING_TARGET -> tickReadingTarget();
            case CHECK_SIGN_ITEM -> tickCheckSignItem();
            case CRAFTING_SIGN -> tickCraftingSign();
            case SELECT_ANCHOR -> tickSelectAnchor();
            case PLACING -> tickPlacing();
            case DONE -> null;
        };
    }

    private Task tickReadingTarget() {
        var maybe = context.memory().storageTarget();
        if (maybe.isEmpty()) {
            // Validator should prevent this ordering, but defend anyway.
            finishWithWarning("no_storage_target_for_label");
            return null;
        }
        this.target = maybe.get();
        this.phase = Phase.CHECK_SIGN_ITEM;
        updateProgress();
        return null;
    }

    private Task tickCheckSignItem() {
        Item resolved;
        if (!params.signItemId().isBlank()) {
            // A planner-supplied id only proves the sign EXISTS in the game registry, never that the
            // bot OWNS one (the model can hallucinate a signItemId even when sign_item_in_inventory is
            // false). Accept it only if the bot actually holds it; otherwise fall back to any sign the
            // bot genuinely owns, so a wrong-variant id still works when a different sign is on hand.
            Item byId = resolveSignItemById(params.signItemId());
            resolved = (byId instanceof SignItem && inventoryContainsItem(this.controller, byId))
                    ? byId
                    : findFirstSignItemInInventory(this.controller);
        } else {
            resolved = findFirstSignItemInInventory(this.controller);
        }
        // No sign held: do NOT give up — craft one via the SAME macro path "get sign 1" uses
        // (CraftMacroTasks.tryCreateMacroTask), so the species fundability pick runs at macro
        // creation with fresh post-chest/table inventory + vicinity state. Still best-effort: any
        // craft failure degrades to a skip in tickCraftingSign, never a FAILED run.
        if (resolved == Items.AIR || !(resolved instanceof SignItem)) {
            this.phase = Phase.CRAFTING_SIGN;
            updateProgress();
            return null;
        }
        this.signItem = resolved;
        this.phase = Phase.SELECT_ANCHOR;
        updateProgress();
        return null;
    }

    /**
     * Obtain a sign via the existing craft pipeline (the same {@link CraftMacroTasks} path the chat
     * command {@code get sign 1} uses), following the {@code ResolveStorageChestTask}
     * ENSURING_CHEST_ITEM precedent for "agentic step drives a craft macro as child task". One
     * bounded attempt; every exit stays inside the best-effort umbrella: a sign in inventory
     * advances to SELECT_ANCHOR, anything else degrades to a skip with a truthful
     * {@code sign_craft_failed: <underlying reason>} note for BOTH audiences (DESIGN.md §3) —
     * never plain "no_sign_item", which would hide that a craft was attempted.
     */
    private Task tickCraftingSign() {
        // A sign can appear mid-craft (or via pickup); take it the moment it exists.
        Item held = findFirstSignItemInInventory(this.controller);
        if (held instanceof SignItem) {
            this.signItem = held;
            stopCraftChild();
            this.phase = Phase.SELECT_ANCHOR;
            updateProgress();
            return null;
        }
        if (craftChild != null) {
            if (craftChild.isActive() && !craftChild.stopped() && !isSignCraftChildComplete()) {
                return craftChild;
            }
            // Craft child terminal WITHOUT a sign: the macro's bounded UNOBTAINABLE terminate
            // (no fundable species), a craft failure, or a self-stop. Surface its REAL recorded
            // reason, falling back to a generic-but-truthful note when none was recorded.
            String reason = craftChild instanceof CraftMacroResourceTask macro
                    && macro.getFailureReason() != null && !macro.getFailureReason().isBlank()
                    ? macro.getFailureReason()
                    : "craft did not produce a sign";
            finishWithWarning("sign_craft_failed: " + reason);
            return null;
        }
        // Generic "sign" request: CraftMacroSupport.pickSignSpecies resolves the species
        // deterministically from what is fundable RIGHT NOW. A null macro means fast-craft macros
        // are disabled in settings — a non-craft skip with its own truthful code.
        // WS4: thread the run's chain-scoped reservation ledger so the sign craft respects sibling
        // agentic steps' pre-seeded reservations (additive floor; only lowers perceived free stock).
        Task macro = CraftMacroTasks.tryCreateMacroTask(this.controller, new ItemTarget("sign", 1),
                context != null ? context.reservations() : null);
        if (macro == null) {
            finishWithWarning("sign_craft_unavailable");
            return null;
        }
        craftChild = macro;
        updateProgress();
        report("crafting a sign to label the chest", false);
        return craftChild;
    }

    /** Craft-macro children are terminal at phase DONE (sign-in-hand success is caught earlier in the tick). */
    private boolean isSignCraftChildComplete() {
        if (craftChild instanceof CraftMacroResourceTask macro) {
            return macro.getPhase() == CraftMacroPhase.DONE;
        }
        return craftChild.isFinished();
    }

    /**
     * Mirror of {@code ResolveStorageChestTask.detectAndRefreshMacroProgress} (ISSUE 1): refresh the
     * progress-deadline clock whenever the sign-craft {@link CraftMacroResourceTask} is demonstrably
     * advancing — a macro phase advance, or a rise in held sign-craft materials (logs/planks/sticks/
     * tables/signs entering inventory during a long single-phase COLLECT). Returns true when the most
     * recent progress was within {@code params.timeoutSeconds()}, suppressing the timeout this tick.
     * A genuinely stalled macro stops resetting the clock, so the deadline stays bounded.
     */
    private boolean detectAndRefreshMacroProgress(CraftMacroResourceTask macro) {
        long now = System.currentTimeMillis();
        boolean advanced = false;
        CraftMacroPhase macroPhase = macro.getPhase();
        if (macroPhase != lastObservedMacroPhase) {
            lastObservedMacroPhase = macroPhase;
            advanced = true;
        }
        int held = signCraftMaterialHeld();
        if (held > lastObservedHeld) {
            advanced = true;
        }
        if (held != lastObservedHeld) {
            lastObservedHeld = held;
        }
        if (advanced) {
            this.lastProgressMs = now;
        }
        return (now - lastProgressMs) / 1000.0 < params.timeoutSeconds();
    }

    /**
     * Total held count of the materials a from-scratch sign craft passes through (logs -> planks ->
     * sticks / crafting table; output sign). A rise here is real craft progress regardless of which
     * CraftMacro phase reports it. Read-only; no model call, no world scan.
     */
    private int signCraftMaterialHeld() {
        var storage = this.controller.getItemStorage();
        return storage.getItemCount(ItemHelper.LOG)
                + storage.getItemCount(ItemHelper.PLANKS)
                + storage.getItemCount(Items.STICK)
                + storage.getItemCount(Items.CRAFTING_TABLE)
                + storage.getItemCount(ItemHelper.WOOD_SIGN);
    }

    private Task tickSelectAnchor() {
        BlockPos chest = target.pos();
        ServerLevel level = this.controller.getWorld();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;

        // Preferred: a WALL sign on the chest itself. The sign occupies an adjacent horizontal air
        // cell; shouldSneakToPlaceAgainst(CHEST)=true so placing against the chest auto-sneaks and
        // does not open it. Anchor = chest pos.
        BlockState chestState = level.getBlockState(chest);
        if (!chestState.isAir()) {
            for (Direction face : HORIZONTAL) {
                BlockPos signCell = SignScanSupport.expectedSignBlockPos(chest, face);
                if (signCell.getY() < minY || signCell.getY() > maxY) {
                    continue;
                }
                if (isPlaceableSignCell(signCell)) {
                    this.anchor = chest;
                    this.faceTowardAir = face;
                    this.phase = Phase.PLACING;
                    updateProgress();
                    return null;
                }
            }
        }

        // Fallback: a STANDING sign atop a solid block adjacent to the chest. faceTowardAir = UP,
        // expected sign cell = neighbor.above() must be air/reachable/placeable.
        for (Direction face : HORIZONTAL) {
            BlockPos neighbor = chest.relative(face);
            if (!WorldHelper.isSolidBlock(this.controller, neighbor)) {
                continue;
            }
            BlockPos signCell = neighbor.above();
            if (signCell.getY() < minY || signCell.getY() > maxY) {
                continue;
            }
            if (isPlaceableSignCell(signCell)) {
                this.anchor = neighbor;
                this.faceTowardAir = Direction.UP;
                this.phase = Phase.PLACING;
                updateProgress();
                return null;
            }
        }

        finishWithWarning("no_label_anchor");
        return null;
    }

    /**
     * Validate a candidate sign cell with the same air/reach/place checks
     * {@link com.player2.playerengine.agentic.storage.ChestPlacementSelector} uses for chest sites:
     * the cell must be air and both reachable and placeable by the bot.
     */
    private boolean isPlaceableSignCell(BlockPos signCell) {
        return WorldHelper.isAir(this.controller, signCell)
                && WorldHelper.canReach(this.controller, signCell)
                && WorldHelper.canPlace(this.controller, signCell);
    }

    private Task tickPlacing() {
        if (placeChild != null) {
            // Hold the single child across ticks until it finishes; its callbacks record the outcome.
            if (placeChild.isActive() && !placeChild.stopped() && !placeChild.isFinished()) {
                return placeChild;
            }
            // Child finished. If a callback already recorded a terminal outcome we are done; the
            // callback called finishWithSuccess/finishWithWarning. Defensive fallback below.
            if (!placeOutcomeRecorded) {
                finishWithWarning("label_skipped: place_no_callback");
            }
            return null;
        }

        Component[] front = SignLabelText.frontLines(this.controller, buildLabelLines());
        Component[] back = SignLabelText.emptyBack();
        final BlockPos placedAt = SignScanSupport.expectedSignBlockPos(anchor, faceTowardAir);
        placeChild = new PlaceSignTask(
                anchor,
                faceTowardAir,
                signItem,
                front,
                back,
                () -> finishWithSuccess("labeled at " + formatPos(placedAt)),
                code -> finishWithWarning("label_skipped: " + code));
        updateProgress();
        return placeChild;
    }

    /**
     * Build the deterministic default label lines. The optional model-generated label routes through
     * {@link AiTaskClass#SUMMARIZATION} only when {@code agenticLabelUseModelText} is enabled, and
     * ALWAYS falls back to this deterministic template on budget block / exception.
     */
    private String[] buildLabelLines() {
        // Explicit planner-supplied lines win.
        List<String> explicit = params.lines();
        if (explicit != null && !explicit.isEmpty()) {
            String[] out = new String[4];
            for (int i = 0; i < 4; i++) {
                out[i] = i < explicit.size() && explicit.get(i) != null ? explicit.get(i) : "";
            }
            return out;
        }

        String[] template = deterministicTemplate();
        if (params.autoLabel() && context.settings().isAgenticLabelUseModelText()) {
            String[] model = tryModelLabel(template);
            if (model != null) {
                return model;
            }
        }
        return template;
    }

    /** Deterministic, no-model label derived from the storage target and a content category. */
    private String[] deterministicTemplate() {
        BlockPos pos = target.pos();
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return new String[] {
            "Storage",
            contentCategorySummary(),
            coords,
            ""
        };
    }

    /**
     * A short deterministic content-category summary. Today the deposited item ids are not threaded
     * into execution memory (see Workstream 2 note), so this degrades to the target block kind,
     * never inventing certainty about contents.
     */
    private String contentCategorySummary() {
        String blockId = target.blockId();
        if (blockId == null || blockId.isBlank()) {
            return "Items";
        }
        int colon = blockId.indexOf(':');
        String name = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        return name.replace('_', ' ');
    }

    /**
     * Optional model-generated label. Mirrors {@code AgenticPlannerService}'s budget handling: any
     * exception (including BUDGET_HARD_LIMIT surfaced as a message) is swallowed and we fall back to
     * the deterministic template by returning null. Never PLANNING, never raw HTTP, never fails the run.
     */
    private String[] tryModelLabel(String[] fallbackTemplate) {
        try {
            Player2APIService api = this.controller.getPlayer2APIService();
            ConversationHistory history = new ConversationHistory(
                    "You write very short Minecraft storage-chest sign labels. "
                            + "Reply with at most four short lines (max "
                            + SignScanSupport.MAX_LINE_LEN
                            + " chars each), no extra commentary.");
            history.addUserMessage(
                    "Label a storage chest holding " + contentCategorySummary()
                            + " at " + formatPos(target.pos())
                            + ". Keep it concise; first line is a heading.",
                    api);
            String reply = api.completeConversationToString(history, AiTaskClass.SUMMARIZATION);
            if (reply == null || reply.isBlank()) {
                return null;
            }
            String[] lines = reply.split("\\r?\\n");
            String[] out = new String[4];
            for (int i = 0; i < 4; i++) {
                String line = i < lines.length && lines[i] != null ? lines[i].trim() : "";
                out[i] = SignScanSupport.truncate(line, SignScanSupport.MAX_LINE_LEN);
            }
            return out;
        } catch (Exception e) {
            // Budget block or any failure -> deterministic template. Never fail the run.
            this.controller.log("[Agentic] label_chest: model label unavailable, using template ("
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
            return null;
        }
    }

    private void finishWithSuccess(String message) {
        if (finished) {
            return;
        }
        this.placeOutcomeRecorded = true;
        this.phase = Phase.DONE;
        this.finished = true;
        this.lastMessage = message;
        stopChild();
        updateProgress();
        report("labeled chest", true);
        this.controller.log("[Agentic] label_chest: " + message);
    }

    private void finishWithWarning(String reason) {
        if (finished) {
            return;
        }
        this.placeOutcomeRecorded = true;
        this.phase = Phase.DONE;
        // Best-effort: SUCCEEDED even on warning. finished=true and never self-stop to FAILED.
        this.finished = true;
        this.lastMessage = reason;
        stopChild();
        updateProgress();
        // Set structured degraded signal for the model-summary path.
        if (context != null && context.runState() != null) {
            context.runState().setLabelDegraded(
                    com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel.SKIPPED,
                    reason);
        }
        // Visible degradation note (milestone) — the label was skipped but the plan still succeeds, so
        // this is NOT a failure line. The plan's overall success is unaffected (finished stays true).
        report("skipped chest label: " + describeLabelDegradation(reason), true);
        this.controller.log("[Agentic] label_chest (best-effort, still ok): " + reason);
    }

    /** Maps a label degradation code to a concise player-readable cause. Reuses, never redefines. */
    private static String describeLabelDegradation(String reason) {
        // sign_craft_failed carries a dynamic underlying reason -> match by prefix, keep the cause.
        if (reason.startsWith("sign_craft_failed: ")) {
            return "tried to craft a sign but couldn't ("
                    + reason.substring("sign_craft_failed: ".length()) + ")";
        }
        return switch (reason) {
            case "label_disabled" -> "labeling disabled";
            case "label_timeout" -> "timed out";
            // Legacy/defensive: the no-sign path now crafts (see sign_craft_* codes).
            case "no_sign_item" -> "no sign item";
            case "sign_craft_unavailable" -> "no sign item and sign crafting is disabled";
            case "no_label_anchor" -> "no spot for a sign";
            case "no_storage_target_for_label" -> "no chest to label";
            case "interrupted" -> "interrupted";
            default -> reason;
        };
    }

    private void stopChild() {
        if (placeChild != null && !placeChild.stopped()) {
            placeChild.stop(this);
        }
        placeChild = null;
        stopCraftChild();
    }

    private void stopCraftChild() {
        if (craftChild != null && !craftChild.stopped()) {
            craftChild.stop(this);
        }
        craftChild = null;
    }

    private void updateProgress() {
        if (context != null && context.runState() != null) {
            context.runState().setLabelProgress(describeProgress());
        }
        this.setDebugState(phase.name());
    }

    /**
     * Player-facing progress note via the controller seam (WS1/WS2). Guarded by the run-state
     * terminal flag so a late callback cannot overwrite a failure line. Uses {@code context.controller()}.
     * Label is best-effort: degradations are reported as visible notes, NEVER as plan failures.
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

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Item findFirstSignItemInInventory(PlayerEngineController mod) {
        for (int i = 0; i < mod.getInventory().getContainerSize(); i++) {
            Item it = mod.getInventory().getItem(i).getItem();
            if (it instanceof SignItem) {
                return it;
            }
        }
        return Items.AIR;
    }

    /** True if the bot actually holds the given item anywhere in its inventory (ownership check). */
    private static boolean inventoryContainsItem(PlayerEngineController mod, Item target) {
        if (target == null || target == Items.AIR) {
            return false;
        }
        for (int i = 0; i < mod.getInventory().getContainerSize(); i++) {
            if (mod.getInventory().getItem(i).getItem() == target) {
                return true;
            }
        }
        return false;
    }

    private static Item resolveSignItemById(String idStr) {
        String trimmed = idStr.trim().toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null) {
            return Items.AIR;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (!(item instanceof SignItem)) {
            return Items.AIR;
        }
        return item;
    }

    @Override
    protected void onStop(Task interruptTask) {
        // Best-effort: never convert an interruption into a FAILED step. If we were interrupted
        // before reaching a terminal state, mark SUCCEEDED with a degradation note (never self-stop).
        if (!finished) {
            this.phase = Phase.DONE;
            this.finished = true;
            this.lastMessage = "interrupted";
            if (placeChild != null && !placeChild.stopped()) {
                placeChild.stop(interruptTask);
            }
            placeChild = null;
            // The sign-craft child gets the same clean best-effort stop as the place child.
            if (craftChild != null && !craftChild.stopped()) {
                craftChild.stop(interruptTask);
            }
            craftChild = null;
            updateProgress();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LabelChestTask task && task.params.equals(this.params);
    }

    @Override
    protected String toDebugString() {
        return "LabelChest";
    }
}
