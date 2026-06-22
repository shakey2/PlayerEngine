package com.player2.playerengine.tasks.cooking;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.mixins.MixinAbstractFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Reads live furnace block-entity state for the deferred smelting system.
 *
 * <p>The Furnace BE is the <em>source of truth</em> for job completion and progress.
 * This reader is always polled against the live BE; no estimate or self-counted timer is trusted.
 *
 * <h3>Slot layout (constants match {@code AbstractFurnaceBlockEntity})</h3>
 * <pre>
 *   SLOT_INPUT  = 0   — raw material being cooked
 *   SLOT_FUEL   = 1   — current fuel item
 *   SLOT_RESULT = 2   — finished output
 * </pre>
 *
 * <h3>ContainerData indices (via MixinAbstractFurnaceBlockEntity.getPropertyDelegate())</h3>
 * <pre>
 *   DATA_LIT_TIME          = 0  — remaining burn ticks of current fuel piece
 *   DATA_LIT_DURATION      = 1  — total burn ticks of last/current fuel piece
 *   DATA_COOKING_PROGRESS  = 2  — cook-ticks elapsed on the current item
 *   DATA_COOKING_TOTAL_TIME = 3 — cook-ticks required to finish one item
 * </pre>
 *
 * <h3>Version note (1.20.1 vs 1.21.1)</h3>
 * In vanilla 1.21.1, {@code DATA_LIT_TIME} (index 0) is scaled to the 0..32767 range when
 * {@code litDuration > 32767}. All v1 vanilla fuels are under that threshold (lava=20000 < 32767,
 * and lava is excluded from the fuel planner anyway), so the scaling is irrelevant in practice.
 * Completion checks use indices 2/3 ({@code cookingProgress} / {@code cookingTotalTime}) which
 * are unscaled in both versions. "Is it still lit?" is tested as {@code litTime > 0} which
 * correctly preserves the zero/non-zero boundary even if scaling is applied.
 *
 * <h3>Completion truth</h3>
 * A job is complete when the expected output count is present in slot 2 AND the input slot has
 * drained (count == 0). Do NOT use elapsed cook time as the completion gate.
 *
 * <p>This class is stateless and produces a new {@link FurnaceState} snapshot on every call to
 * {@link #read(BlockPos)}. Callers should not cache the returned record across ticks.
 *
 * <p>Depends on: {@link SimpleChunkTracker#isChunkSimulated(BlockPos)} (WS3) and
 * {@link MixinAbstractFurnaceBlockEntity#getPropertyDelegate()} (existing mixin — no new mixin).
 */
public final class FurnaceStateReader {

    /**
     * Slot index for the raw material input (constant matches
     * {@code AbstractFurnaceBlockEntity.SLOT_INPUT}).
     */
    public static final int SLOT_INPUT = 0;

    /**
     * Slot index for fuel (constant matches {@code AbstractFurnaceBlockEntity.SLOT_FUEL}).
     */
    public static final int SLOT_FUEL = 1;

    /**
     * Slot index for the cooked output (constant matches
     * {@code AbstractFurnaceBlockEntity.SLOT_RESULT}).
     */
    public static final int SLOT_RESULT = 2;

    // ContainerData indices — match AbstractFurnaceBlockEntity.DATA_*
    private static final int IDX_LIT_TIME          = 0;
    private static final int IDX_LIT_DURATION      = 1;
    private static final int IDX_COOKING_PROGRESS  = 2;
    private static final int IDX_COOKING_TOTAL_TIME = 3;

    private final PlayerEngineController mod;

    /**
     * Constructs a reader bound to the given controller. The controller provides access to the
     * current world ({@code mod.getWorld()}) and the chunk-ticking tracker
     * ({@code mod.getChunkTracker().isChunkSimulated(pos)}).
     *
     * @param mod the live {@link PlayerEngineController}; must not be null.
     */
    public FurnaceStateReader(PlayerEngineController mod) {
        this.mod = mod;
    }

    /**
     * Reads the current state of the furnace (or blast furnace / smoker) at {@code pos}.
     *
     * <p>If no {@link AbstractFurnaceBlockEntity} exists at that position — either because the
     * block was removed/replaced or the chunk is not loaded — the returned {@link FurnaceState}
     * has {@code present = false} and all slot / progress fields set to empty / zero. This is the
     * primary signal for the "furnace gone" degradation case.
     *
     * <p>The {@code simulated} field is set independently of {@code present}: a chunk can be
     * ticking even if no furnace is there (e.g. the block was broken). The caller should check
     * {@code present} first, then {@code simulated} to decide whether progress is possible.
     *
     * @param pos the world position of the furnace block (stored in {@link
     *            com.player2.playerengine.tasks.deferred.DeferredJobRecord}).
     * @return a snapshot of furnace state; never null.
     */
    public FurnaceState read(BlockPos pos) {
        Level world = mod.getWorld();
        if (world == null) {
            return FurnaceState.absent(pos, false);
        }

        boolean simulated = mod.getChunkTracker().isChunkSimulated(pos);

        if (!(world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            // No furnace BE at this position (block removed, wrong type, or chunk not loaded).
            return FurnaceState.absent(pos, simulated);
        }

        // Read slots.
        ItemStack input  = furnace.getItem(SLOT_INPUT).copy();
        ItemStack fuel   = furnace.getItem(SLOT_FUEL).copy();
        ItemStack output = furnace.getItem(SLOT_RESULT).copy();

        // Read progress via the existing dataAccess mixin — no new mixin needed.
        ContainerData data = ((MixinAbstractFurnaceBlockEntity) furnace).getPropertyDelegate();
        int litTime          = data.get(IDX_LIT_TIME);
        int litDuration      = data.get(IDX_LIT_DURATION);
        int cookingProgress  = data.get(IDX_COOKING_PROGRESS);
        int cookingTotalTime = data.get(IDX_COOKING_TOTAL_TIME);

        return new FurnaceState(
                true,
                simulated,
                input, fuel, output,
                litTime, litDuration,
                cookingProgress, cookingTotalTime
        );
    }

    // -------------------------------------------------------------------------
    // FurnaceState record
    // -------------------------------------------------------------------------

    /**
     * A snapshot of a single furnace (or blast furnace / smoker) block entity at a given instant.
     *
     * <p>All {@link ItemStack} fields are copies (defensive copies from {@code getItem(...).copy()});
     * mutations to the returned stacks do not affect the live furnace inventory.
     *
     * <h3>Fields</h3>
     * <ul>
     *   <li>{@link #present} — {@code true} iff an {@link AbstractFurnaceBlockEntity} exists at the
     *       queried position. {@code false} means the furnace was broken or replaced — the primary
     *       signal for the "furnace gone" degradation.</li>
     *   <li>{@link #simulated} — {@code true} iff the chunk is at block-ticking level (via
     *       {@link SimpleChunkTracker#isChunkSimulated(BlockPos)}). A furnace can exist ({@code
     *       present=true}) in a non-ticking chunk; it will not advance until the chunk ticks.</li>
     *   <li>{@link #input} — slot 0 contents (raw material). Empty when the batch is fully
     *       consumed.</li>
     *   <li>{@link #fuel} — slot 1 contents (current fuel item). May be empty if the current fuel
     *       piece has been consumed but {@code litTime} is still positive (the furnace can remain
     *       lit briefly after the fuel item is consumed).</li>
     *   <li>{@link #output} — slot 2 contents (cooked result). Check count here for completion.</li>
     *   <li>{@link #litTime} — remaining burn ticks for the current fuel piece ({@code
     *       DATA_LIT_TIME = 0}). Use only as a boolean "is it lit" ({@code litTime > 0}). On
     *       1.21.1 this value may be scaled when {@code litDuration > 32767}; the zero/non-zero
     *       boundary is preserved in both versions. See class-level version note.</li>
     *   <li>{@link #litDuration} — total burn ticks of the current/last fuel ({@code
     *       DATA_LIT_DURATION = 1}). Informational only; prefer {@code litTime > 0} for "lit".</li>
     *   <li>{@link #cookingProgress} — cook-ticks elapsed on the in-progress item ({@code
     *       DATA_COOKING_PROGRESS = 2}). Unscaled in both 1.20.1 and 1.21.1.</li>
     *   <li>{@link #cookingTotalTime} — cook-ticks required to finish one item ({@code
     *       DATA_COOKING_TOTAL_TIME = 3}). Unscaled in both 1.20.1 and 1.21.1.</li>
     * </ul>
     *
     * <h3>Completion truth</h3>
     * A job is done when {@code output.getCount() >= expectedOutputCount && input.isEmpty()}.
     * Do NOT gate completion on {@code cookingProgress == cookingTotalTime} or elapsed wall-clock
     * time — use output-slot count + input drain.
     *
     * <h3>Out-of-fuel detection</h3>
     * {@code litTime == 0 && fuel.isEmpty() && !input.isEmpty() && cookingProgress > 0}
     * indicates the furnace ran out of fuel mid-batch (partial yield case).
     */
    public record FurnaceState(
            boolean present,
            boolean simulated,
            ItemStack input,
            ItemStack fuel,
            ItemStack output,
            int litTime,
            int litDuration,
            int cookingProgress,
            int cookingTotalTime
    ) {

        /**
         * Returns true when the furnace is actively burning ({@code litTime > 0}).
         *
         * <p>Safe across both 1.20.1 and 1.21.1: the scaling applied to {@code litTime} in
         * 1.21.1 preserves the zero/non-zero boundary (a fuel with 0 remaining burn ticks is
         * always 0 after any affine scaling; a positive value stays positive).
         */
        public boolean isLit() {
            return litTime > 0;
        }

        /**
         * Returns true when there is a cook in progress (progress has advanced but is not yet
         * complete). Note: if the furnace ran out of fuel mid-item, progress may be stuck at a
         * non-zero value indefinitely.
         */
        public boolean isCookInProgress() {
            return cookingProgress > 0 && cookingTotalTime > 0 && cookingProgress < cookingTotalTime;
        }

        /**
         * Returns {@code true} if the furnace is present but the chunk is not ticking, meaning
         * the furnace will not make progress this tick. The bot must navigate back to the furnace
         * location to make the chunk tick again.
         */
        public boolean isStuckNotSimulated() {
            return present && !simulated;
        }

        // --- Factory methods for the absent case ---

        /**
         * Creates a "not present" {@link FurnaceState} for positions where no furnace BE exists.
         * All slot stacks are {@link ItemStack#EMPTY}; all progress values are 0.
         *
         * @param pos       the queried position (unused by this record but useful for callers
         *                  that log the position on the degradation path).
         * @param simulated whether the chunk is ticking (independent of furnace presence).
         */
        public static FurnaceState absent(BlockPos pos, boolean simulated) {
            return new FurnaceState(
                    false,
                    simulated,
                    ItemStack.EMPTY,
                    ItemStack.EMPTY,
                    ItemStack.EMPTY,
                    0, 0, 0, 0
            );
        }
    }
}
