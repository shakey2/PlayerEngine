package com.player2.playerengine.tasks.cooking;

import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookKind;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable parameter bundle for {@code SmeltDeferredTask}.
 *
 * <p>Decouples task construction from call sites (command surface and agentic step factory)
 * so the task signature stays stable as parameters evolve.
 *
 * <p>Build via {@link Builder}:
 * <pre>{@code
 * SmeltDeferredParams params = new SmeltDeferredParams.Builder(Items.RAW_IRON, 16)
 *         .preferredKind(CookKind.BLASTING)
 *         .stallPolls(200)
 *         .timeoutSeconds(600)
 *         .build();
 * }</pre>
 *
 * <h3>Parameter notes</h3>
 * <ul>
 *   <li>{@code count} is clamped to {@code [1, deferredSmeltMaxBatch]} by the caller before
 *       constructing this record (default max 64 per the config table in the plan).</li>
 *   <li>{@code preferredKind} is optional; {@code null} means "resolve any" — the resolver
 *       tries SMELTING → BLASTING → SMOKING and uses the first match. A non-null value
 *       restricts resolution to that kind and fails gracefully if no recipe exists.</li>
 *   <li>{@code allowYield} is reserved for the v2 "front-load + overlap" follow-up (see
 *       plan Decision 7). It MUST default to {@code false} and is NOT acted on in v1. Callers
 *       must not pass {@code true}; the field is present only so the class shape is stable
 *       when the follow-up lands. Any v1 code path that reads it must treat it as always
 *       {@code false}.</li>
 *   <li>{@code stallPolls} drives the SOFT stall signal ({@link
 *       com.player2.playerengine.tasks.deferred.DeferredDegradation#STALLED_RETRYING}) —
 *       consecutive not-simulated polls before the bot attempts to return and re-tick the chunk.
 *       One poll per server tick = 20 polls/second; default 200 ≈ 10 seconds.</li>
 *   <li>{@code timeoutSeconds} is the HARD real-time give-up bound ({@link
 *       com.player2.playerengine.tasks.deferred.DeferredDegradation#STALLED_TIMEOUT}).
 *       Must be well above {@code stallPolls / 20} so the bot can stall-warn and recover
 *       before the hard timeout fires; default 600 s.</li>
 * </ul>
 */
public final class SmeltDeferredParams {

    /** The item to smelt (raw material, e.g. {@code Items.RAW_IRON}). */
    public final Item inputItem;

    /**
     * How many items to smelt in this job. Clamped to {@code [1, deferredSmeltMaxBatch]}
     * by the call site before construction.
     */
    public final int count;

    /**
     * The preferred furnace family to use for recipe resolution, or {@code null} for "any"
     * (SMELTING → BLASTING → SMOKING priority). If the preferred kind has no recipe for
     * {@code inputItem}, the task fails gracefully — it does NOT fall back to another kind
     * when an explicit preference is given.
     */
    @Nullable
    public final CookKind preferredKind;

    /**
     * Consecutive not-simulated polls before the SOFT stall warning fires
     * ({@link com.player2.playerengine.tasks.deferred.DeferredDegradation#STALLED_RETRYING}).
     * One poll per server tick = 20/sec; default 200 ≈ 10 seconds.
     *
     * <p>Clamped to {@code [20, 2000]} (config key {@code deferredSmeltStallPolls}).
     */
    public final int stallPolls;

    /**
     * HARD real-time timeout in seconds for the entire job
     * ({@link com.player2.playerengine.tasks.deferred.DeferredDegradation#STALLED_TIMEOUT}).
     * Measured in wall-clock seconds ({@code gameTime / 20}), not poll count.
     *
     * <p>Clamped to {@code [60, 3600]} (config key {@code deferredSmeltTimeoutSeconds}).
     */
    public final int timeoutSeconds;

    /**
     * Reserved for the v2 "front-load + overlap" follow-up (plan Decision 7 — deferred).
     *
     * <p><b>MUST be {@code false} in v1.</b> When {@code true}, the task would yield after
     * loading the furnace so the executor can advance to the next plan step while the cook
     * runs, then re-enter later to collect. This behavior is NOT implemented in v1; any
     * code path reading this field must treat it as always {@code false}.
     */
    public final boolean allowYield;

    private SmeltDeferredParams(Builder b) {
        this.inputItem      = b.inputItem;
        this.count          = b.count;
        this.preferredKind  = b.preferredKind;
        this.stallPolls     = b.stallPolls;
        this.timeoutSeconds = b.timeoutSeconds;
        this.allowYield     = false; // v1: always off — see field Javadoc
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        // Required
        private final Item inputItem;
        private final int count;

        // Optional — defaults match config defaults in the plan
        @Nullable private CookKind preferredKind  = null;
        private int stallPolls     = 200;
        private int timeoutSeconds = 600;

        /**
         * @param inputItem the raw-material item to smelt.
         * @param count     number of items; must be {@code >= 1} (caller clamps to max batch).
         * @throws IllegalArgumentException if count < 1 or inputItem is null.
         */
        public Builder(Item inputItem, int count) {
            if (inputItem == null) throw new IllegalArgumentException("inputItem must not be null");
            if (count < 1)        throw new IllegalArgumentException("count must be >= 1, got " + count);
            this.inputItem = inputItem;
            this.count     = count;
        }

        /**
         * Restricts recipe resolution to the given furnace family. {@code null} (the default)
         * means "any" (tries SMELTING → BLASTING → SMOKING).
         */
        public Builder preferredKind(@Nullable CookKind kind) {
            this.preferredKind = kind;
            return this;
        }

        /**
         * Consecutive not-simulated polls before the soft stall signal fires.
         * Clamped to {@code [20, 2000]}.
         */
        public Builder stallPolls(int polls) {
            this.stallPolls = Math.max(20, Math.min(2000, polls));
            return this;
        }

        /**
         * Hard real-time give-up timeout in seconds. Clamped to {@code [60, 3600]}.
         */
        public Builder timeoutSeconds(int seconds) {
            this.timeoutSeconds = Math.max(60, Math.min(3600, seconds));
            return this;
        }

        /** Builds the immutable {@link SmeltDeferredParams}. */
        public SmeltDeferredParams build() {
            return new SmeltDeferredParams(this);
        }
    }
}
