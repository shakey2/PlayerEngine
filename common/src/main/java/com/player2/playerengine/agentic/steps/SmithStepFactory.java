package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticExecutionContext;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;
import com.player2.playerengine.agentic.AgenticRunRegistry.DegradationLevel;
import com.player2.playerengine.agentic.AgenticStepFactory;
import com.player2.playerengine.agentic.AgenticStepSpec;
import com.player2.playerengine.commands.base.DescriptionIdIndex;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.smithing.SmithDeferredParams;
import com.player2.playerengine.tasks.smithing.SmithDeferredTask;
import com.player2.playerengine.tasks.smithing.SmithDeferredTask.Outcome;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Builds the deferred-smith task from a validated {@code smith_items} step spec — the agentic
 * trigger surface for smithing upgrades (WS4). Reuses the SAME {@link SmithDeferredTask} the
 * standalone {@code smith} command uses; only the model-facing reporting sink differs: the command
 * path uses {@code finishWith*}, the agentic path routes the SAME terminal/degradation outcome
 * through the run-state smith slot ({@link AgenticRunState#setSmithDegraded}) so
 * {@code AgenticDegradationSummary.forModel} surfaces them — including a factual clean-success
 * clause — into the model's plan-completion feedback (DESIGN.md §3).
 *
 * <h3>Critical fix vs. the smelt precedent (m3)</h3>
 * {@code SmeltStepFactory.resolveItem} parses the {@code item} token with
 * {@code BuiltInRegistries.ITEM.get(id)} directly and therefore bypasses
 * {@link DescriptionIdIndex}. A model that emits a lang key ({@code block.<mod>.<thing>}) or a
 * bare modded path in a {@code smith_items} step would dead-end on exactly the bug this feature
 * plan exists to fix. {@code SmithStepFactory.resolveItem} MUST first run the token through
 * {@link DescriptionIdIndex#resolveToRegistryId(String)} (deterministic reverse map, no model
 * calls) and only then call {@code BuiltInRegistries.ITEM.get} on the resulting registry id. Do
 * NOT copy {@code SmeltStepFactory}'s {@code resolveItem} verbatim here.
 *
 * <p>Args: {@code item} (required, registry id, lang key, or bare modded path),
 * {@code count} (optional, default 1, clamped to max-batch config).
 */
public final class SmithStepFactory implements AgenticStepFactory {

    /** Default max batch (mirrors {@code deferredSmeltMaxBatch = 64}). Used until
     *  {@code PlayerEngineSettings.getSmithMaxBatch()} is wired during serial integration. */
    private static final int DEFAULT_SMITH_MAX_BATCH = 64;

    @Override
    public Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context) {
        PlayerEngineSettings settings = context.settings();
        Map<String, String> args = step.args() != null ? step.args() : Map.of();

        // Config gate: honour isEnableSmithing() on the agentic path just as SmithCommand does.
        // Without this check, a smith_items step would launch SmithDeferredTask even when the
        // operator has disabled smithing via config — breaking the config invariant.
        if (settings != null && !settings.isEnableSmithing()) {
            AgenticRunState run = context.runState();
            if (run != null) {
                run.setSmithDegraded(DegradationLevel.SKIPPED, "smithing_disabled");
            }
            return Optional.empty();
        }

        // m3 fix: resolve via DescriptionIdIndex BEFORE BuiltInRegistries.ITEM.get so
        // lang keys and bare modded paths resolve to the correct registry id deterministically.
        Item output = resolveItem(lookup(args, "item"));
        if (output == null || output == Items.AIR) {
            // No resolvable item arg — surface as a skipped smith so the model learns the cause.
            AgenticRunState run = context.runState();
            if (run != null) {
                run.setSmithDegraded(DegradationLevel.SKIPPED, "no_input");
            }
            return Optional.empty();
        }

        // Use smith-specific max-batch once settings field exists; fall back to the smelt value
        // (same default) during the transition period.
        int maxBatch = getSmithMaxBatch(settings);
        int count = Math.max(1, Math.min(maxBatch, parseInt(args, "count", 1)));

        SmithDeferredParams params = new SmithDeferredParams.Builder(output, count)
                .gatherBudget(400)
                .pollCap(12000)
                .build();
        // WS3: thread the run's chain-scoped reservation ledger so the smith step consults it
        // before gathering and reserves its three role species (template/base/addition) before
        // removal. context.reservations() is the lazily-created, never-null run ledger.
        return Optional.of(new AgenticSmithTask(
                new SmithDeferredTask(params, context.reservations()), context));
    }

    // -----------------------------------------------------------------------------------------
    // Agentic wrapper: delegates to the shared SmithDeferredTask, then records its single Outcome
    // onto the run-state smith slot (model channel) and fires the shared player line (player
    // channel). Hard failures self-stop with finished=false so SingleTaskChain records FAILED;
    // degraded successes finish=true so the plan advances with the degradation noted.
    // -----------------------------------------------------------------------------------------

    static final class AgenticSmithTask extends Task {

        private final SmithDeferredTask inner;
        private final AgenticExecutionContext context;
        private boolean finished;
        private boolean recorded;

        AgenticSmithTask(SmithDeferredTask inner, AgenticExecutionContext context) {
            this.inner = inner;
            this.context = context;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        protected void onStart() {
            setDebugState("deferred smith (agentic)");
        }

        @Override
        protected Task onTick() {
            if (finished || recorded) {
                return null;
            }
            if (!inner.isFinished()) {
                // Delegate every tick to the shared task until it reaches a terminal outcome.
                return inner;
            }
            recordOutcome(inner.outcome());
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
            // If the wrapper is interrupted before the inner task recorded a terminal outcome, leave
            // the run-state slot untouched (a lifecycle stop is not a smith degradation). The inner
            // task's own onStop is invoked by the chain via its child reference.
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof AgenticSmithTask t && t.inner.equals(this.inner);
        }

        @Override
        protected String toDebugString() {
            return "AgenticSmith";
        }

        /** Maps the shared {@link Outcome} to the run-state smith slot + the shared player line. */
        private void recordOutcome(Outcome o) {
            recorded = true;
            AgenticRunState run = context.runState();
            if (o == null) {
                // No outcome recorded (should not happen once inner.isFinished()): treat as a
                // skipped smith and finish so the plan does not hang.
                if (run != null) {
                    run.setSmithDegraded(DegradationLevel.SKIPPED, "no_outcome");
                }
                finished = true;
                return;
            }

            // Player channel: fire the concise human line as a milestone, guarded by the run-state
            // terminal flag so a late callback cannot overwrite a terminal failure line.
            if (run == null || !run.isTerminal()) {
                context.controller().reportAgenticProgress(playerLine(o), true);
            }

            switch (o.kind()) {
                case CLEAN_SUCCESS -> {
                    // Clean success: record the factual progress so AgenticDegradationSummary
                    // emits "upgraded N <output>" (never confabulate a generic "finished running").
                    if (run != null) {
                        run.setSmithProgress("upgraded=" + o.collected() + " item=" + o.outputName());
                    }
                    finished = true;
                }
                case PARTIAL -> {
                    degraded(run, DegradationLevel.PARTIAL,
                            "partial collected=" + o.collected() + " of " + o.expected(), o, true);
                }
                case TAMPERED -> {
                    degraded(run, DegradationLevel.PARTIAL,
                            "tampered collected=" + o.collected() + " of " + o.expected(), o, true);
                }
                case NO_TABLE -> degraded(run, DegradationLevel.SKIPPED, "no_table", o, false);
                case NO_RECIPE -> degraded(run, DegradationLevel.SKIPPED, "no_recipe", o, false);
                case MISSING_TEMPLATE -> degraded(run, DegradationLevel.SKIPPED,
                        "missing_template:" + o.reasonLabel(), o, false);
                case MISSING_BASE -> degraded(run, DegradationLevel.SKIPPED,
                        "missing_base:" + o.reasonLabel(), o, false);
                case MISSING_ADDITION -> degraded(run, DegradationLevel.SKIPPED,
                        "missing_addition:" + o.reasonLabel(), o, false);
                case SETUP_FAILED -> degraded(run, DegradationLevel.SKIPPED,
                        "setup_failed:" + o.reasonLabel(), o, false);
            }
        }

        /**
         * Records a degradation onto the run-state slot. {@code softSuccess=true} finishes the step
         * (the plan advances with the degradation noted); {@code false} is a hard failure that
         * self-stops with {@code finished=false} so the chain records FAILED.
         */
        private void degraded(AgenticRunState run, DegradationLevel level, String reason,
                              Outcome o, boolean softSuccess) {
            if (run != null) {
                run.setSmithDegraded(level, reason);
                // Keep a factual progress note too (count) so progressForKind has the detail.
                run.setSmithProgress("upgraded=" + o.collected() + " item=" + o.outputName()
                        + " reason=" + reason);
            }
            if (softSuccess) {
                finished = true;
            } else {
                // Hard failure: self-stop so SingleTaskChain takes the forced-stop path and the
                // adapter records FAILED (mirrors DepositItemsTask.terminateFailed).
                finished = false;
                if (!this.stopped()) {
                    this.stop(this);
                }
            }
        }

        private static String playerLine(Outcome o) {
            String out = o.outputItem() != null
                    ? BuiltInRegistries.ITEM.getKey(o.outputItem()).getPath().replace('_', ' ')
                    : "that item";
            return switch (o.kind()) {
                case CLEAN_SUCCESS -> "Upgraded " + o.collected() + " x " + out + " at the smithing table.";
                case PARTIAL -> "Upgraded " + o.collected() + " of " + o.expected() + " x " + out
                        + " — couldn't finish the batch.";
                case TAMPERED -> "The smithing table or my inventory changed while I was working — upgraded "
                        + o.collected() + " of " + o.expected() + " x " + out + ".";
                case NO_TABLE -> "Couldn't upgrade " + out + " — no smithing table reachable or placeable.";
                case NO_RECIPE -> "Can't upgrade to " + out + " — nothing in the smithing registry produces it.";
                case MISSING_TEMPLATE -> "Couldn't get the smithing template needed to upgrade to "
                        + out + " (" + o.reasonLabel() + ").";
                case MISSING_BASE -> "Couldn't get the base item needed to upgrade to "
                        + out + " (" + o.reasonLabel() + ").";
                case MISSING_ADDITION -> "Couldn't get the upgrade material needed for "
                        + out + " (" + o.reasonLabel() + ").";
                case SETUP_FAILED -> "Couldn't start the smithing upgrade (" + o.reasonLabel() + ").";
            };
        }
    }

    // -----------------------------------------------------------------------------------------
    // Arg parsing
    // -----------------------------------------------------------------------------------------

    /**
     * Resolves the model/user-supplied item token to an {@link Item}.
     *
     * <p><b>m3 fix:</b> unlike {@code SmeltStepFactory.resolveItem}, this method runs the token
     * through {@link DescriptionIdIndex#resolveToRegistryId(String)} FIRST so that lang keys
     * (e.g. {@code block.iceandfire.foo}) and unique bare modded paths map to the canonical
     * registry id before the {@code BuiltInRegistries.ITEM.get} call. This prevents the known
     * dead-end where a model emitting a translation key from its context finds no item in the
     * registry and the step fails before resolution.
     */
    private static Item resolveItem(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim().toLowerCase(Locale.ROOT);

        // Step 1: try DescriptionIdIndex for lang-key / bare-modded-path forms.
        String resolved = DescriptionIdIndex.resolveToRegistryId(trimmed);
        if (resolved != null) {
            ResourceLocation resolvedId = ResourceLocation.tryParse(resolved);
            if (resolvedId != null) {
                return BuiltInRegistries.ITEM.get(resolvedId);
            }
        }

        // Step 2: fall through to direct registry lookup (handles plain registry ids like
        // "minecraft:netherite_pickaxe" or "netherite_pickaxe" via the minecraft: prefix).
        ResourceLocation id = ResourceLocation.tryParse(
                trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private static int parseInt(Map<String, String> args, String key, int def) {
        String raw = lookup(args, key);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String lookup(Map<String, String> args, String key) {
        if (args == null) {
            return null;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        for (Map.Entry<String, String> e : args.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            if (k.equals(normalized)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Returns the configured max-batch size for smithing upgrades.
     */
    private static int getSmithMaxBatch(PlayerEngineSettings settings) {
        return settings != null ? settings.getSmithMaxBatch() : DEFAULT_SMITH_MAX_BATCH;
    }
}
