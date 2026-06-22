package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.Item;

/**
 * Chain-scoped, item-keyed material reservation ledger.
 *
 * <p>One instance lives on {@link AgenticExecutionMemory} per agentic run and is the conservative
 * additive floor that every material-consuming step (craft / smelt / smith) consults so that an
 * earlier-or-pending step cannot cannibalize a later step's reserved input. The ledger is
 * {@code Map<Item, Integer>} (slot- and recipe-agnostic) and can only ever <em>lower</em> the
 * perceived free stock — it never replaces the crafting resolver's per-pass reservation map.
 *
 * <p>Invariants (see the generic-material-reservation-plan, WS1):
 * <ul>
 *   <li>All access is on the server tick thread; the run already runs there, so no synchronization
 *       is required (plain {@link HashMap}).</li>
 *   <li>Every consult reconciles {@code reserved} down to the relevant live held count
 *       ({@code reserved' = min(reserved, held)}), so a dropped / consumed-elsewhere item can never
 *       strand inventory.</li>
 *   <li>In-memory and non-persisted: it is dropped at every run terminal (cleared via WS5) and on
 *       reload, matching {@code AgenticRunRegistry}.</li>
 * </ul>
 *
 * <p>Consumers take a {@code @Nullable MaterialReservationService} and treat {@code null} as a
 * no-op, which keeps crafts that run outside any agentic run identical to today.
 */
public final class MaterialReservationService {

    private final Map<Item, Integer> reserved = new HashMap<>(); // server-thread only; no sync needed

    /**
     * BROAD free (held incl. container/chest stock) minus reserved, reconciled to live held.
     * Use for craft / smelt-input / smith paths whose underlying read is the broad
     * {@code getItemStorage().getItemCount}.
     */
    public int free(PlayerEngineController controller, Item item) {
        return freeAgainst(controller.getItemStorage().getItemCount(item), item);
    }

    /**
     * INVENTORY-ONLY free (carried inventory only, excludes chests) minus reserved, reconciled to
     * live inventory-only held. Use for the FuelPlanner fuel read, which is deliberately
     * inventory-only (B3) — do NOT collapse this with {@link #free}.
     */
    public int freeInventoryOnly(PlayerEngineController controller, Item item) {
        return freeAgainst(controller.getItemStorage().getItemCountInventoryOnly(item), item);
    }

    private int freeAgainst(int held, Item item) {
        int r = Math.min(reserved.getOrDefault(item, 0), held); // reconcile down to the relevant held
        return Math.max(0, held - r);
    }

    /**
     * Reserve up to {@code amount} against BROAD free; clamps so a step never reserves more than is
     * free. Returns the amount actually granted (can only lower future free).
     */
    public int reserve(PlayerEngineController controller, Item item, int amount) {
        int grant = Math.min(Math.max(0, amount), free(controller, item));
        if (grant > 0) {
            reserved.merge(item, grant, Integer::sum);
        }
        return grant;
    }

    /** Release on consume/abort. Floors at 0. */
    public void release(Item item, int amount) {
        if (amount <= 0) {
            return;
        }
        reserved.computeIfPresent(item, (k, v) -> Math.max(0, v - amount));
    }

    /** Drop all reservations (called at every run terminal; WS5). */
    public void clear() {
        reserved.clear();
    }
}
