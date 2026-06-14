package com.player2.playerengine.containeraccess;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Fresh-read container scans (Part C4.5, WS1).
 *
 * <p><b>Fresh read only (Decision 1):</b> every scan reads the live container through the
 * vanilla {@code Container} interface of a {@link ContainerResolver} resolution. Scans are
 * NEVER answered from {@code ContainerCache}/{@code ItemStorageTracker} (stale-by-design); the
 * only contact with the legacy cache is the {@link #refreshLegacyCache} side effect that keeps
 * it warm for legacy consumers (Decision 12).
 *
 * <p><b>Loot-table side effect:</b> the first {@code getItem} on a never-opened naturally
 * generated chest auto-unpacks its pending loot table
 * ({@code RandomizableContainerBlockEntity.unpackLootTable}) — required for a truthful scan,
 * but it irreversibly consumes the worldgen-origin marker. C5's create pipeline must
 * origin-check BEFORE scanning (see the {@link ContainerSnapshot} contract notes).
 */
public final class ContainerScanService {

    private ContainerScanService() {
    }

    /**
     * Typed scan result: either a {@link ContainerSnapshot} or a {@link StorageAccessCode} +
     * human detail. Never throws to the caller.
     */
    public record ScanOutcome(ContainerSnapshot snapshot, StorageAccessCode code, String detail) {

        public boolean ok() {
            return this.code == StorageAccessCode.OK;
        }

        public static ScanOutcome success(ContainerSnapshot snapshot) {
            return new ScanOutcome(snapshot, StorageAccessCode.OK, "");
        }

        public static ScanOutcome failure(StorageAccessCode code, String detail) {
            return new ScanOutcome(null, code, detail);
        }
    }

    /**
     * Resolve-and-scan convenience: resolves {@code pos} in the bot's current level, reads the
     * snapshot, and refreshes the legacy cache for both halves (Decision 12).
     *
     * @param targets used by {@link ScanMode#TARGETED} only (the merged queries from
     *                {@link StorageItemArgs#parse}); ignored for light/deep
     */
    public static ScanOutcome scan(
            PlayerEngineController controller, BlockPos pos, ScanMode mode, List<StorageItemArgs.ItemQuery> targets) {
        if (mode == ScanMode.TARGETED && (targets == null || targets.isEmpty())) {
            return ScanOutcome.failure(StorageAccessCode.INVALID_ARGUMENT, "targeted mode requires item names");
        }
        ServerLevel level = controller.getWorld();
        ContainerResolver.Resolution resolution = ContainerResolver.resolve(level, pos);
        if (!resolution.ok()) {
            return ScanOutcome.failure(resolution.code(), resolution.detail());
        }
        ContainerSnapshot snapshot = snapshot(level, resolution.resolved(), mode, targets);
        refreshLegacyCache(controller, resolution.resolved().canonicalPos(), resolution.resolved().secondaryPos());
        return ScanOutcome.success(snapshot);
    }

    /**
     * Pure fresh read of an already-resolved container (the session tasks resolve once for
     * animation and read here on the post-open tick). One pass over
     * {@code 0..getContainerSize()-1}; aggregate map + empty count always; per-slot list only
     * for {@link ScanMode#DEEP}; for {@link ScanMode#TARGETED} the aggregate carries one entry
     * per requested item with its live count (0 when absent — a successful result, not an
     * error). Does NOT refresh the legacy cache — callers do that on their session close path.
     */
    public static ContainerSnapshot snapshot(
            ServerLevel level, ResolvedContainer resolved, ScanMode mode, List<StorageItemArgs.ItemQuery> targets) {
        Container container = resolved.container();
        int totalSlots = container.getContainerSize();
        // TreeMap keys = full registry ids -> aggregate sorted ascending, deterministic.
        TreeMap<String, Integer> counts = new TreeMap<>();
        List<IndexedSlot> slots = mode == ScanMode.DEEP ? new ArrayList<>() : null;
        int emptySlots = 0;

        for (int i = 0; i < totalSlots; i++) {
            // Auto-unpacks a pending loot table on first read (see class doc).
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            String registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(registryId, stack.getCount(), Integer::sum);
            if (slots != null) {
                // hasExtraData ships always-false in C4.5 (version-divergent accessor; the
                // deep-scan "*" marker is reserved) — see IndexedSlot.
                slots.add(new IndexedSlot(i, registryId, stack.getCount(), false));
            }
        }

        List<ItemCount> aggregate;
        if (mode == ScanMode.TARGETED) {
            TreeMap<String, Integer> filtered = new TreeMap<>();
            if (targets != null) {
                for (StorageItemArgs.ItemQuery query : targets) {
                    String fullId = BuiltInRegistries.ITEM.getKey(query.item()).toString();
                    filtered.put(fullId, counts.getOrDefault(fullId, 0));
                }
            }
            aggregate = toItemCounts(filtered);
        } else {
            aggregate = toItemCounts(counts);
        }

        return new ContainerSnapshot(
                level.dimension().location().toString(),
                resolved.canonicalPos(),
                resolved.secondaryPos(),
                resolved.kind(),
                totalSlots,
                emptySlots,
                aggregate,
                slots == null ? null : List.copyOf(slots),
                level.getGameTime());
    }

    /**
     * Refreshes the legacy aggregate cache for the canonical pos AND the secondary half
     * (Decision 12) so the legacy cache-only getters on the item-storage tracker never report
     * withdrawn items as still present. Every scan
     * and every transaction session (both directions) calls this on its close path. The
     * {@code StorageAccess: refreshed container cache @ <pos>} log line is the validation-
     * matrix observable. Side benefit only — never a source of truth for scans.
     */
    public static void refreshLegacyCache(
            PlayerEngineController controller, BlockPos canonicalPos, BlockPos secondaryPos) {
        controller.getItemStorage().containers.WritableCache(controller, canonicalPos);
        Debug.logInternal("StorageAccess: refreshed container cache @ " + ContainerResolver.formatPos(canonicalPos));
        if (secondaryPos != null) {
            controller.getItemStorage().containers.WritableCache(controller, secondaryPos);
            Debug.logInternal("StorageAccess: refreshed container cache @ " + ContainerResolver.formatPos(secondaryPos));
        }
    }

    private static List<ItemCount> toItemCounts(Map<String, Integer> sortedCounts) {
        List<ItemCount> result = new ArrayList<>(sortedCounts.size());
        for (Map.Entry<String, Integer> entry : sortedCounts.entrySet()) {
            result.add(new ItemCount(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }
}
