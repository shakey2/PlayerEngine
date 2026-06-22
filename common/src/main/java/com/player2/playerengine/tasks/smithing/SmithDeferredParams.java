package com.player2.playerengine.tasks.smithing;

import net.minecraft.world.item.Item;

/**
 * Immutable parameter bundle for {@code SmithDeferredTask}.
 *
 * <p>Mirrors {@link com.player2.playerengine.tasks.cooking.SmeltDeferredParams} — decouples
 * task construction from call sites (command surface and agentic step factory) so the task
 * signature stays stable as parameters evolve.
 *
 * <p>Build via {@link Builder}:
 * <pre>{@code
 * SmithDeferredParams params = new SmithDeferredParams.Builder(Items.NETHERITE_PICKAXE, 1)
 *         .gatherBudget(400)
 *         .build();
 * }</pre>
 *
 * <h3>Parameter notes</h3>
 * <ul>
 *   <li>{@code outputItem} is the DESIRED OUTPUT item (e.g. {@code Items.NETHERITE_PICKAXE}),
 *       not the base/template/addition input. The recipe resolver converts it to the full
 *       {@link com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess.SmithingResolution}
 *       at task start.</li>
 *   <li>{@code count} is clamped to {@code [1, smithMaxBatch]} by the caller before
 *       constructing this record (config key {@code smithMaxBatch}, default 64, mirrors
 *       {@code deferredSmeltMaxBatch}).</li>
 *   <li>{@code gatherBudget} is the maximum number of consecutive ticks that the underlying
 *       {@code UpgradeInSmithingTableTask} may remain in gather-phase (i.e. still requesting
 *       inputs) before {@code SmithDeferredTask} classifies the stall as a role-specific
 *       {@code MISSING_TEMPLATE} / {@code MISSING_BASE} / {@code MISSING_ADDITION} outcome
 *       (attributed by inventory inspection at stall point). One tick per server tick = 20/s;
 *       default 400 ≈ 20 seconds. Clamped to {@code [20, 4000]}.</li>
 *   <li>{@code pollCap} is the absolute tick ceiling for the entire job before a hard
 *       {@code SETUP_FAILED} give-up. Prevents an infinite gather loop on an unobtainable
 *       item. Clamped to {@code [200, 72000]} (= 1 minute to 1 hour). Default 12000 ≈ 10 min.</li>
 * </ul>
 */
public final class SmithDeferredParams {

    /**
     * The desired output item of the smithing upgrade
     * (e.g. {@code Items.NETHERITE_PICKAXE}).
     * The resolver maps this to the matching {@code SmithingTransformRecipe}.
     */
    public final Item outputItem;

    /**
     * How many upgrades to perform in this job. Clamped to {@code [1, smithMaxBatch]}
     * by the call site before construction.
     */
    public final int count;

    /**
     * Consecutive gather-phase ticks before the stall is attributed as a role-specific
     * {@code MISSING_*} outcome. One tick per server tick = 20/s; default 400 ≈ 20 seconds.
     * Clamped to {@code [20, 4000]}.
     */
    public final int gatherBudget;

    /**
     * Absolute tick ceiling for the entire job. Prevents infinite gather loops on
     * unobtainable items. Default 12000 ≈ 10 minutes. Clamped to {@code [200, 72000]}.
     */
    public final int pollCap;

    private SmithDeferredParams(Builder b) {
        this.outputItem   = b.outputItem;
        this.count        = b.count;
        this.gatherBudget = b.gatherBudget;
        this.pollCap      = b.pollCap;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        // Required
        private final Item outputItem;
        private final int count;

        // Optional — defaults match the plan's config table
        private int gatherBudget = 400;
        private int pollCap      = 12000;

        /**
         * @param outputItem the desired output item of the smithing upgrade (not null).
         * @param count      number of upgrades; must be {@code >= 1} (caller clamps to max batch).
         * @throws IllegalArgumentException if count < 1 or outputItem is null.
         */
        public Builder(Item outputItem, int count) {
            if (outputItem == null) throw new IllegalArgumentException("outputItem must not be null");
            if (count < 1)         throw new IllegalArgumentException("count must be >= 1, got " + count);
            this.outputItem = outputItem;
            this.count      = count;
        }

        /**
         * Consecutive gather-phase ticks before the stall is classified as a role-specific
         * {@code MISSING_*} outcome. Clamped to {@code [20, 4000]}.
         */
        public Builder gatherBudget(int ticks) {
            this.gatherBudget = Math.max(20, Math.min(4000, ticks));
            return this;
        }

        /**
         * Absolute tick ceiling for the entire job. Clamped to {@code [200, 72000]}.
         */
        public Builder pollCap(int ticks) {
            this.pollCap = Math.max(200, Math.min(72000, ticks));
            return this;
        }

        /** Builds the immutable {@link SmithDeferredParams}. */
        public SmithDeferredParams build() {
            return new SmithDeferredParams(this);
        }
    }
}
