package com.player2.playerengine.tasks.cooking;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.agentic.MaterialReservationService;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Registry-driven fuel planner for deferred smelting jobs.
 *
 * <p>Fuel burn times come from {@link AbstractFurnaceBlockEntity#getFuel()} — the same static map
 * {@link ItemHelper} uses internally — which is common-safe in both 1.20.1 and 1.21.1.
 * {@code ForgeHooks}, NeoForge {@code ItemStack.getBurnTime}, and any loader-specific
 * fuel APIs are explicitly excluded from this class.
 *
 * <h3>Preference order</h3>
 * <ol>
 *   <li>Charcoal ({@link Items#CHARCOAL})</li>
 *   <li>Coal ({@link Items#COAL})</li>
 *   <li>Planks — first species the bot has enough of ({@link ItemHelper#PLANKS})</li>
 *   <li>Logs — first species the bot has enough of ({@link ItemHelper#LOG})</li>
 * </ol>
 *
 * <h3>Quantity formula</h3>
 * <pre>
 *   totalCookTicks = inputCount * cookTicksPerItem
 *   fuelNeeded     = ceil(totalCookTicks / burnTimeTicks(chosenFuel))
 * </pre>
 *
 * <p><b>WARNING — do NOT substitute {@link ItemHelper#getFuelAmount} or
 * {@code StorageHelper.calculateInventoryFuelCount} here.</b>
 * Both helpers divide burn-time ticks by {@code 200}, hardcoding the assumption that one
 * "fuel unit" cooks exactly one furnace item. Blast furnace and smoker items cook in 100
 * ticks (half the denominator), which would halve the required fuel count and leave the
 * job short. This planner always divides by the ACTUAL burn-time ticks of the chosen fuel,
 * never the {@code /200} shortcut.
 *
 * <h3>Lava bucket exclusion</h3>
 * {@link Items#LAVA_BUCKET} is present in the registry burn-time map (20 000 ticks) but is
 * excluded explicitly. Bucket mechanics (container-item return, creative-mode edge cases,
 * fluid interactions) require special handling deferred to a later release. A comment at the
 * exclusion point in code calls this out so a future agent does not add it back silently.
 *
 * <h3>Inventory-only selection</h3>
 * v1 selects from the bot's current inventory only. Fuel fetching ({@link
 * com.player2.playerengine.tasks.resources.CollectFuelTask} or equivalent) is the
 * caller's responsibility; if no candidate has sufficient inventory, {@link #plan}
 * returns {@link Optional#empty()}, which the caller must treat as an "insufficient fuel"
 * degradation.
 */
public final class FuelPlanner {

    /**
     * The result of a successful fuel-planning pass.
     *
     * @param fuelItem      the chosen fuel item (a concrete {@link Item}, never a tag).
     * @param fuelCount     how many units of {@code fuelItem} are needed to cook the batch.
     * @param totalCookTicks total cook-ticks for the job ({@code inputCount * cookTicksPerItem}).
     */
    public record FuelPlan(Item fuelItem, int fuelCount, int totalCookTicks) {}

    /**
     * Plans fuel for a batch smelting job.
     *
     * <p>Iterates the preference order (charcoal → coal → planks → logs) and returns the
     * first candidate for which the bot's current inventory holds at least the required
     * number of units. Returns {@link Optional#empty()} when no candidate is satisfiable
     * from inventory — the caller should surface this as an "insufficient fuel" degradation.
     *
     * @param inputCount      number of items in the batch (≥ 1).
     * @param cookTicksPerItem per-item cook duration in game ticks from the recipe
     *                        (200 for smelting, 100 for blasting/smoking in vanilla).
     *                        <b>Must be the raw recipe value — NOT a {@code /200} ratio.</b>
     * @param controller      the active {@link PlayerEngineController} used to query the
     *                        bot's inventory.
     * @param ledger          the per-run material reservation ledger (may be {@code null} outside an
     *                        agentic run). When present, fuel candidates are read as
     *                        {@code freeInventoryOnly} (held minus reserved, inventory-only scope)
     *                        so an item reserved by/for another step is never burned as fuel, and the
     *                        chosen fuel is reserved on success. Null = behaves exactly as today.
     * @return a {@link FuelPlan} for the first satisfiable candidate, or
     *         {@link Optional#empty()} if no candidate can be satisfied from inventory.
     */
    public Optional<FuelPlan> plan(int inputCount, int cookTicksPerItem,
                                   PlayerEngineController controller,
                                   @Nullable MaterialReservationService ledger) {
        if (inputCount < 1 || cookTicksPerItem < 1) {
            return Optional.empty();
        }

        int totalCookTicks = inputCount * cookTicksPerItem;
        Map<Item, Integer> burnMap = getFuelBurnMap();

        // ---- 1. Charcoal ----
        Optional<FuelPlan> result = tryCandidate(Items.CHARCOAL, totalCookTicks, burnMap, controller, ledger);
        if (result.isPresent()) return result;

        // ---- 2. Coal ----
        result = tryCandidate(Items.COAL, totalCookTicks, burnMap, controller, ledger);
        if (result.isPresent()) return result;

        // ---- 3. Planks (first species the bot has enough of) ----
        for (Item plank : ItemHelper.PLANKS) {
            result = tryCandidate(plank, totalCookTicks, burnMap, controller, ledger);
            if (result.isPresent()) return result;
        }

        // ---- 4. Logs (first species the bot has enough of) ----
        for (Item log : ItemHelper.LOG) {
            result = tryCandidate(log, totalCookTicks, burnMap, controller, ledger);
            if (result.isPresent()) return result;
        }

        // No candidate satisfiable from current inventory.
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Fuel-deficit detection (agentic pre-gather, WS — fuel-deficit gather)
    // -------------------------------------------------------------------------

    /**
     * A single acquirable fuel candidate and the inventory-unit deficit needed to cover the batch.
     *
     * @param item      the concrete acquirable fuel {@link Item} (never charcoal, never lava bucket).
     * @param needed    units of {@code item} the full batch requires ({@code ceil(totalCookTicks / burnTicks)}).
     * @param held      units of {@code item} currently held inventory-only (free, when a ledger is present).
     * @param deficit   {@code needed - held} (always {@code > 0} for a returned candidate).
     * @param burnTicks the registry burn-time of {@code item} in ticks.
     */
    public record DeficitCandidate(Item item, int needed, int held, int deficit, int burnTicks) {}

    /**
     * Finds the single best acquirable fuel item and the item-unit deficit to cover the batch, for the
     * agentic pre-gather (a PRE step run by the {@code AgenticSmeltTask} wrapper before delegating to
     * {@link SmeltDeferredTask}). This method <b>reserves nothing</b> — {@link #plan} performs the single
     * reservation on the subsequent re-run, after the gather has landed the fuel in inventory.
     *
     * <p>Walks ONLY the acquirable set, in acquisition preference order, and <b>never</b> targets
     * charcoal (circular — making charcoal needs a fueled furnace, the very dependency being solved) nor
     * the lava bucket (excluded, see {@link #tryCandidate}):
     * <ol>
     *   <li><b>Coal</b> — treated as acquirable ONLY when a wood-tier pickaxe is held inventory-only
     *       ({@link StorageHelper#miningRequirementMetInventory} with {@link MiningRequirement#WOOD}).
     *       Without a held pickaxe the coal rung is skipped entirely, severing
     *       smelt&rarr;coal&rarr;pickaxe-craft&rarr;iron&rarr;smelt by construction.</li>
     *   <li><b>Planks</b> — each species (chop-and-craft via {@code CollectPlanksTask}).</li>
     *   <li><b>Logs</b> — each species.</li>
     * </ol>
     *
     * <p>Of the candidates with a positive deficit, the one with the SMALLEST deficit (least gather
     * work) is returned. Returns {@link Optional#empty()} only when no acquirable fuel exists at all —
     * rare, because the wood (planks/logs) rung is always offered and is attempted by the gather even
     * from zero held, so an empty result here effectively means "every preferred fuel is already
     * satisfied or the registry burn-map lacks them" (the caller treats empty as "no gather needed").
     *
     * @param inputCount       number of items in the batch (&ge; 1).
     * @param cookTicksPerItem per-item cook duration in game ticks (raw recipe value, NOT a /200 ratio).
     * @param controller       the active controller used to read inventory + the held-pickaxe gate.
     * @param ledger           the per-run reservation ledger, or {@code null}; when present, held counts
     *                         are read as {@code freeInventoryOnly} (held minus reserved, inventory-only).
     * @return the smallest-deficit acquirable candidate, or {@link Optional#empty()} if none applies.
     */
    public Optional<DeficitCandidate> deficitFor(int inputCount, int cookTicksPerItem,
                                                 PlayerEngineController controller,
                                                 @Nullable MaterialReservationService ledger) {
        if (inputCount < 1 || cookTicksPerItem < 1 || controller == null) {
            return Optional.empty();
        }

        int totalCookTicks = inputCount * cookTicksPerItem;
        Map<Item, Integer> burnMap = getFuelBurnMap();

        DeficitCandidate best = null;

        // ---- Coal — ONLY when a wood-tier pickaxe is held inventory-only (spiral guard). ----
        if (StorageHelper.miningRequirementMetInventory(controller, MiningRequirement.WOOD)) {
            best = pickSmaller(best, candidateFor(Items.COAL, totalCookTicks, burnMap, controller, ledger));
        }

        // ---- Planks (each species) ----
        for (Item plank : ItemHelper.PLANKS) {
            best = pickSmaller(best, candidateFor(plank, totalCookTicks, burnMap, controller, ledger));
        }

        // ---- Logs (each species) ----
        for (Item log : ItemHelper.LOG) {
            best = pickSmaller(best, candidateFor(log, totalCookTicks, burnMap, controller, ledger));
        }

        return Optional.ofNullable(best);
    }

    /**
     * Builds a {@link DeficitCandidate} for one item, or {@code null} when the item is not a valid
     * acquirable fuel (not in the burn-map, lava bucket, or already fully covered — deficit &le; 0).
     * Reserves nothing.
     */
    @Nullable
    private static DeficitCandidate candidateFor(Item item, int totalCookTicks, Map<Item, Integer> burnMap,
                                                 PlayerEngineController controller,
                                                 @Nullable MaterialReservationService ledger) {
        if (item == Items.CHARCOAL || item == Items.LAVA_BUCKET) {
            return null; // circular / excluded — never a gather target
        }
        Integer burnTicks = burnMap.get(item);
        if (burnTicks == null || burnTicks <= 0) {
            return null;
        }
        int needed = (totalCookTicks + burnTicks - 1) / burnTicks;
        int held = ledger != null
                ? ledger.freeInventoryOnly(controller, item)
                : controller.getItemStorage().getItemCountInventoryOnly(item);
        int deficit = needed - held;
        if (deficit <= 0) {
            return null; // already fully covered from inventory — no gather work for this item
        }
        return new DeficitCandidate(item, needed, held, deficit, burnTicks);
    }

    /** Returns whichever candidate has the smaller positive deficit (least gather work); null-safe. */
    @Nullable
    private static DeficitCandidate pickSmaller(@Nullable DeficitCandidate a, @Nullable DeficitCandidate b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.deficit() < a.deficit() ? b : a;
    }

    /**
     * Resolves a {@link DeficitCandidate} into the actual gather {@link ResourceTask} to spawn, gathering
     * {@code cand.needed()} units.
     *
     * <p><b>Why this exists (silent-gather-skip guard):</b> {@link #deficitFor} walks {@link ItemHelper#LOG}
     * and {@link ItemHelper#PLANKS}, but {@link TaskCatalogue} does NOT register an {@code Item}-keyed task
     * for every member of those arrays. {@code ItemHelper.LOG} includes {@code *_wood}, all {@code stripped_*}
     * variants and {@code *_hyphae}, which the catalogue registers only under the generic {@code "log"}
     * string key (a multi-species MineAndCollect over natural logs), never under their concrete {@code Item}
     * key. Calling {@link TaskCatalogue#getItemTask(Item, int)} for such an item hits the not-registered
     * branch and returns {@code null}; the caller would then latch {@code fuelGatherAttempted} and fall
     * straight through to the terminal shortfall WITHOUT ever spawning a gather — a silent skip that wrongly
     * reports "no logs/planks found" while logs exist in the world.
     *
     * <p>This resolver therefore prefers the concrete {@code Item}-keyed task when one is registered, and
     * otherwise falls back to the generic {@code "log"} / {@code "planks"} string catalogue entry (both of
     * which always exist). Coal always resolves by item key. Returns {@code null} only when nothing resolves
     * — the caller treats that the same as a no-op gather (and the terminal shortfall path still fires).
     *
     * @param cand the chosen acquirable fuel candidate (never charcoal / lava bucket — see {@link #deficitFor}).
     * @return the gather task for {@code cand.needed()} units, or {@code null} if no catalogue entry resolves.
     */
    @Nullable
    public static ResourceTask gatherTaskFor(DeficitCandidate cand) {
        if (cand == null) {
            return null;
        }
        Item item = cand.item();
        // count = needed() (full batch requirement), NOT deficit(): the spawned gather tasks
        // (CollectPlanksTask and the generic "log"/"planks" MineAndCollect) check current inventory
        // against the target and self-terminate early once it is reached, so passing the total never
        // over-gathers. Do NOT switch to deficit() — these tasks target an absolute inventory count,
        // not an increment, and a deficit target would under-gather when the bot already holds some.
        int count = cand.needed();
        // Zero-held: no species preference to honor. Route to the nearest-available generic
        // multi-species task instead of the alphabetically-first concrete species. deficitFor()
        // walks PLANKS[]/LOG[] in array order and pickSmaller() keeps the first on a deficit tie,
        // so a fully-zero inventory always selects PLANKS[0] == acacia_planks. Spawning the concrete
        // acacia task in (e.g.) a desert with no acacia bounded-wanders forever; the generic
        // "planks"/"log" catalogue task targets NATURAL_LOG and chops the nearest available species
        // (the adjacent oak). When held > 0 we fall through to the concrete task to finish the
        // species already in inventory (top-up case).
        //
        // Known latent case (held > 0, that species unavailable in the biome): the concrete gather's
        // MineAndCollectTask falls back to an unbounded wander, so give-up is slow (minutes) rather
        // than immediate. This is an accepted PRE-EXISTING constraint, not a regression — it does NOT
        // spin at ~10 Hz, and Fix B's failure propagation still unwinds the chain truthfully once the
        // wander exhausts. We deliberately do NOT divert a held>0 species to the generic task: that
        // would abandon partial inventory the bot already gathered. Narrowing the unbounded wander is
        // tracked separately as a MineAndCollectTask concern, not a FuelPlanner one.
        if (cand.held() == 0) {
            if (contains(ItemHelper.PLANKS, item) && TaskCatalogue.taskExists("planks")) {
                return TaskCatalogue.getItemTask("planks", count);
            }
            if (contains(ItemHelper.LOG, item) && TaskCatalogue.taskExists("log")) {
                return TaskCatalogue.getItemTask("log", count);
            }
            // Coal or unexpected species: not in PLANKS[]/LOG[], fall through to the concrete key.
        }
        // Concrete item key first (coal, plank species, natural-log species are all registered this way).
        if (TaskCatalogue.taskExists(item)) {
            return TaskCatalogue.getItemTask(item, count);
        }
        // Item key absent (e.g. *_wood / stripped_* / *_hyphae logs) -> generic string catalogue.
        if (contains(ItemHelper.LOG, item) && TaskCatalogue.taskExists("log")) {
            return TaskCatalogue.getItemTask("log", count);
        }
        if (contains(ItemHelper.PLANKS, item) && TaskCatalogue.taskExists("planks")) {
            return TaskCatalogue.getItemTask("planks", count);
        }
        // Last resort: let the item-keyed call log its own diagnostic and return null.
        return TaskCatalogue.getItemTask(item, count);
    }

    private static boolean contains(Item[] arr, Item item) {
        // Identity comparison is correct: registered Item instances are singletons. Avoids the
        // per-call ArrayList wrapper that Arrays.asList(arr).contains(item) allocates.
        for (Item candidate : arr) {
            if (candidate == item) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Attempts to satisfy the fuel requirement from a single candidate item.
     *
     * <p>Returns {@link Optional#empty()} if:
     * <ul>
     *   <li>the candidate is not in the registry burn-time map; or</li>
     *   <li>the candidate is {@link Items#LAVA_BUCKET} (explicitly excluded — bucket
     *       mechanics deferred, see class Javadoc); or</li>
     *   <li>the bot does not hold enough of this item in inventory.</li>
     * </ul>
     */
    private static Optional<FuelPlan> tryCandidate(Item candidate, int totalCookTicks,
                                                    Map<Item, Integer> burnMap,
                                                    PlayerEngineController controller,
                                                    @Nullable MaterialReservationService ledger) {
        // LAVA_BUCKET: excluded from v1 fuel candidates.
        // Bucket mechanics (container-item return, creative mode, fluid edge cases) need
        // dedicated handling; do NOT remove this guard silently — see FuelPlanner class doc.
        if (candidate == Items.LAVA_BUCKET) {
            return Optional.empty();
        }

        Integer burnTicks = burnMap.get(candidate);
        if (burnTicks == null || burnTicks <= 0) {
            // Not a valid fuel in the registry (e.g. not registered, or zero burn time).
            return Optional.empty();
        }

        // Quantity = ceil(totalCookTicks / burnTimeTicks).
        // Integer ceiling division: (a + b - 1) / b (avoids floating-point rounding drift).
        int needed = (totalCookTicks + burnTicks - 1) / burnTicks;

        // Inventory-only fuel scope is DELIBERATE (B3): chest stock does NOT count as available fuel.
        // With a ledger, subtract reservations from the inventory-only held count via the dedicated
        // freeInventoryOnly variant — do NOT switch to the broad ledger.free, which would widen fuel
        // selection to chest stock. Null ledger falls back to the original inventory-only read.
        int held = ledger != null
                ? ledger.freeInventoryOnly(controller, candidate)
                : controller.getItemStorage().getItemCountInventoryOnly(candidate);
        if (held >= needed) {
            // Reserve the chosen fuel so a later step cannot also draw it. Commit happens here, in the
            // success branch of tryCandidate (not plan), so only the actually-selected fuel is reserved.
            if (ledger != null) {
                ledger.reserve(controller, candidate, needed);
            }
            return Optional.of(new FuelPlan(candidate, needed, totalCookTicks));
        }

        return Optional.empty();
    }

    /**
     * Returns the registry-driven burn-time map (item → ticks).
     *
     * <p>Delegates to {@link AbstractFurnaceBlockEntity#getFuel()}, the same common-safe
     * static call {@link ItemHelper} uses internally. The returned map is cached by the
     * vanilla implementation once loaded; subsequent calls are cheap.
     *
     * <p><b>Common-safe:</b> {@code AbstractFurnaceBlockEntity} is a vanilla class available
     * in the {@code common} module without any loader-specific imports.
     */
    private static Map<Item, Integer> getFuelBurnMap() {
        return AbstractFurnaceBlockEntity.getFuel();
    }
}
