package com.player2.playerengine.tasks.cooking;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.cooking.FurnaceStateReader.FurnaceState;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookKind;
import com.player2.playerengine.tasks.deferred.DeferredDegradation;
import com.player2.playerengine.tasks.deferred.DeferredProcess;
import com.player2.playerengine.tasks.deferred.DeferredProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Furnace-family adapter for the generic {@link DeferredProcess} abstraction.
 *
 * <p>Ties together the four cooking seams:
 * <ul>
 *   <li>recipe resolution (WS1, {@code CookingRecipeAccess}) — already resolved by the caller;
 *       this adapter holds the resolved output item + expected count + per-item cook ticks;</li>
 *   <li>fuel planning (WS2, {@code FuelPlanner}) — already planned by the caller; this adapter
 *       holds the chosen fuel item + count for record-keeping;</li>
 *   <li>chunk-ticking check (WS3, {@code SimpleChunkTracker.isChunkSimulated}) — read indirectly
 *       through {@link FurnaceStateReader} on every {@link #poll};</li>
 *   <li>BE-as-source-of-truth (WS4, {@code FurnaceStateReader}) — the only completion signal.</li>
 * </ul>
 *
 * <h3>ETA is a planning hint only</h3>
 * {@link #etaGameTime()} = {@code startGameTime + inputCount * cookTicksPerItem}. It is NEVER the
 * completion gate — completion is decided exclusively from the live BE (output count present +
 * input drained). A non-simulated chunk pauses real progress while the ETA marches on, so callers
 * must tolerate a stale/past ETA without treating it as completion.
 *
 * <h3>Degradation classification ({@link #degradationOf})</h3>
 * <ul>
 *   <li>{@link DeferredDegradation#STATION_GONE} — the furnace BE no longer exists at
 *       {@link #stationPos()} ({@code !present}).</li>
 *   <li>{@link DeferredDegradation#OUT_OF_FUEL} — the furnace is present and simulated, not lit,
 *       the fuel slot is empty, input still remains, and the job is not yet complete (more cooking
 *       needed but no fuel to do it). Partial vs. nothing-produced is distinguished downstream by
 *       the observed output count.</li>
 *   <li>{@link DeferredDegradation#STALLED_RETRYING} — the chunk has been non-simulated for at
 *       least {@code stallPolls} consecutive polls while incomplete (SOFT — bot intends to return
 *       and re-tick the chunk).</li>
 *   <li>{@link DeferredDegradation#STALLED_TIMEOUT} — the hard real-time bound
 *       ({@code timeoutSeconds}) has elapsed while the job remained incomplete (terminal give-up).
 *       Takes precedence over the soft stall.</li>
 *   <li>{@code null} — healthy (cooking in progress, or complete without anomaly).</li>
 * </ul>
 *
 * <p>{@code TAMPERED_OUTPUT} is not classified here from a single poll — it is detected at COLLECT
 * time by the owning task comparing the actually-collected count against {@code expectedOutputCount}
 * (the furnace contents can legitimately fluctuate mid-cook, so it would be a false positive to
 * flag it during the wait). See {@code SmeltDeferredTask}.
 *
 * <p>Furnace specifics stay in this adapter; the {@link DeferredProcess} interface carries no
 * furnace-only fields, so brewing/campfire/breeding/growth can plug in later without redesign.
 */
public final class DeferredCookProcess implements DeferredProcess {

    private final BlockPos stationPos;
    private final CookKind cookKind;
    private final Item inputItem;
    private final int inputCount;
    private final Item outputItem;
    private final int expectedOutputCount;
    private final int cookTicksPerItem;
    private final Item fuelItem;
    private final int fuelCount;
    private final long startGameTime;

    /** Soft-stall threshold: consecutive non-simulated polls while incomplete. */
    private final int stallPolls;
    /** Hard real-time give-up bound in seconds (game time / 20). */
    private final int timeoutSeconds;

    /** The furnace state reader (BE source of truth). Stateless; bound to the controller. */
    private final FurnaceStateReader reader;

    /** Running count of consecutive polls where the chunk was not simulated while incomplete. */
    private int consecutiveNotSimulatedPolls = 0;

    /**
     * @param stationPos          furnace block position (authoritative on resume).
     * @param cookKind            which furnace family this job uses (drives {@link #kind()}).
     * @param inputItem           the raw-material item being cooked.
     * @param inputCount          how many input items were loaded.
     * @param outputItem          the resolved output item (from {@code CookingRecipeAccess}).
     * @param expectedOutputCount how many output items a clean completion yields.
     * @param cookTicksPerItem    per-item cook duration in ticks (200 smelt / 100 blast+smoke).
     * @param fuelItem            the chosen fuel item (from {@code FuelPlanner}).
     * @param fuelCount           how many fuel items were loaded.
     * @param startGameTime       {@code Level.getGameTime()} when the job started.
     * @param stallPolls          soft-stall threshold in consecutive non-simulated polls.
     * @param timeoutSeconds      hard real-time give-up bound in seconds.
     * @param controller          the active controller (provides the world / chunk source / BE).
     */
    public DeferredCookProcess(
            BlockPos stationPos,
            CookKind cookKind,
            Item inputItem,
            int inputCount,
            Item outputItem,
            int expectedOutputCount,
            int cookTicksPerItem,
            Item fuelItem,
            int fuelCount,
            long startGameTime,
            int stallPolls,
            int timeoutSeconds,
            PlayerEngineController controller) {
        this.stationPos = stationPos.immutable();
        this.cookKind = cookKind;
        this.inputItem = inputItem;
        this.inputCount = inputCount;
        this.outputItem = outputItem;
        this.expectedOutputCount = expectedOutputCount;
        this.cookTicksPerItem = cookTicksPerItem;
        this.fuelItem = fuelItem;
        this.fuelCount = fuelCount;
        this.startGameTime = startGameTime;
        this.stallPolls = stallPolls;
        this.timeoutSeconds = timeoutSeconds;
        this.reader = new FurnaceStateReader(controller);
    }

    // -------------------------------------------------------------------------
    // DeferredProcess
    // -------------------------------------------------------------------------

    @Override
    public String kind() {
        return switch (cookKind) {
            case SMELTING -> "smelt";
            case BLASTING -> "blast";
            case SMOKING -> "smoke";
        };
    }

    @Override
    public BlockPos stationPos() {
        return stationPos;
    }

    @Override
    public long startGameTime() {
        return startGameTime;
    }

    @Override
    public long etaGameTime() {
        // Planning hint only — NOT the completion gate. Real progress pauses in a non-simulated
        // chunk, so this value can be stale/past without the job being complete.
        return startGameTime + (long) inputCount * cookTicksPerItem;
    }

    @Override
    public DeferredProgress poll(PlayerEngineController controller) {
        long gameTime = controller.getWorld() != null ? controller.getWorld().getGameTime() : 0L;
        FurnaceState s = reader.read(stationPos);

        if (!s.present()) {
            // Confirmed gone (or chunk inaccessible). Reset the stall counter — a missing furnace
            // is its own terminal degradation, not a stall.
            consecutiveNotSimulatedPolls = 0;
            return new DeferredProgress(false, s.simulated(), 0, 0, 0, 0, 0, 0, gameTime);
        }

        DeferredProgress progress = new DeferredProgress(
                true,
                s.simulated(),
                s.output().getCount(),
                s.input().getCount(),
                s.fuel().getCount(),
                s.cookingProgress(),
                s.cookingTotalTime(),
                s.litTime(),
                gameTime);

        // Maintain the soft-stall counter: advance only while incomplete AND not simulated.
        if (!progress.simulated() && !isComplete(progress)) {
            consecutiveNotSimulatedPolls++;
        } else {
            consecutiveNotSimulatedPolls = 0;
        }

        return progress;
    }

    @Override
    public boolean isComplete(DeferredProgress progress) {
        // BE is the source of truth: the batch is done when the expected output count is present
        // AND the input slot has drained. Never gate completion on elapsed time / cook ticks.
        return progress.present()
                && progress.outputCount() >= expectedOutputCount
                && progress.inputCount() <= 0;
    }

    @Override
    public DeferredDegradation degradationOf(DeferredProgress progress) {
        if (isComplete(progress)) {
            return null; // healthy: completed
        }

        // Furnace removed/replaced while away — terminal.
        if (!progress.present()) {
            return DeferredDegradation.STATION_GONE;
        }

        // Hard real-time timeout takes precedence over the soft stall (terminal give-up).
        long elapsedTicks = progress.pollGameTime() - startGameTime;
        if (elapsedTicks >= (long) timeoutSeconds * 20L) {
            return DeferredDegradation.STALLED_TIMEOUT;
        }

        // Out of fuel mid-batch: present + simulated (so progress WOULD advance if lit), not lit,
        // fuel slot empty, and input still remains. The furnace has stopped because there is no
        // fuel, not because the chunk is paused.
        if (progress.simulated()
                && !progress.isLit()
                && progress.fuelCount() <= 0
                && progress.inputCount() > 0) {
            return DeferredDegradation.OUT_OF_FUEL;
        }

        // Soft stall: chunk not simulated for too long while incomplete. The bot intends to return
        // and re-tick the chunk (degraded-success-with-retry).
        if (!progress.simulated() && consecutiveNotSimulatedPolls >= stallPolls) {
            return DeferredDegradation.STALLED_RETRYING;
        }

        return null; // healthy: cooking in progress (or briefly paused under the stall threshold)
    }

    // -------------------------------------------------------------------------
    // Adapter-specific accessors (furnace-only; not on the generic interface)
    // -------------------------------------------------------------------------

    public CookKind cookKind() {
        return cookKind;
    }

    public Item inputItem() {
        return inputItem;
    }

    public int inputCount() {
        return inputCount;
    }

    public Item outputItem() {
        return outputItem;
    }

    public int expectedOutputCount() {
        return expectedOutputCount;
    }

    public int cookTicksPerItem() {
        return cookTicksPerItem;
    }

    public Item fuelItem() {
        return fuelItem;
    }

    public int fuelCount() {
        return fuelCount;
    }
}
