package com.player2.playerengine.util.helpers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSCountingService;
import com.player2.playerengine.agentic.elliegps.EllieGPSCountingServiceStub;
import com.player2.playerengine.util.ItemTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic, on-device, type-aware material-availability counter (WS1 of
 * {@code masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md}).
 *
 * <p>Composes existing trackers and owns no state. It deliberately keeps <b>two structurally
 * separate axes</b> so a count gate can never treat "a local source exists" as "I am done":
 *
 * <ul>
 *   <li><b>Sufficiency axis</b> ({@link Breakdown#sufficiencyCount()}) &mdash; answers
 *       <i>"do I have enough NOW / am I done?"</i>. Counts only what the bot already possesses or is
 *       about to pick up: inventory + reachable nearby ground drops + in-flight just-mined drops.
 *       Mineable blocks and the EllieGPS term are <b>never</b> included here &mdash; counting un-mined
 *       blocks toward "done" would make a task report finished before it ever mines.</li>
 *   <li><b>Source-routing axis</b> ({@link Breakdown#localSourceCapacity()} /
 *       {@link #localSourceCanCoverRemainder}) &mdash; answers <i>"mine the local source vs wander for
 *       a NEW one?"</i>, and only matters once the sufficiency axis says "still short". Counts the
 *       estimated yield of reachable nearby mineable blocks (resolved via the generic
 *       {@link Block#byItem(Item)}) plus the EllieGPS waypoint term (0 this round, C5 later).</li>
 * </ul>
 *
 * <p>Matching is by <b>exact item identity</b> via {@link ItemTarget#matches(Item)} /
 * {@link ItemTarget#getMatches()} only ("oak_log" means oak_log, never any-log). Counting performs no
 * network/model call, spawns no threads, never blocks {@code onTick()}, and returns a number (or 0)
 * immediately.
 */
public final class MaterialAvailability {

   /**
    * EllieGPS C5 counting seam, held directly here as a stateless no-op constant.
    *
    * <p>No controller/context accessor is wired this round (plan decision 5); C5 swaps in the real
    * MinHash-backed impl at startup. See {@link EllieGPSCountingService}.
    */
   // TODO(C5): wire EllieGPS source here
   private static final EllieGPSCountingService waypointStub = EllieGPSCountingServiceStub.INSTANCE;

   /** Conservative estimate of items yielded per reachable mineable block (1/block). */
   private static final int YIELD_PER_BLOCK = 1;

   private MaterialAvailability() {
   }

   /**
    * Term-by-term breakdown for one {@link ItemTarget}. The sufficiency terms and the source-routing
    * terms are kept visibly distinct so callers (and logs) cannot conflate the two axes.
    *
    * @param inventory     bot inventory + cursor (sufficiency)
    * @param nearbyDrops   reachable settled ground drops within drop radius (sufficiency)
    * @param inFlightDrops just-mined drops not yet settled, supplied by the caller (sufficiency)
    * @param mineableYield estimated yield of reachable nearby mineable blocks (source-routing only)
    * @param waypointTerm  EllieGPS waypoint term, 0 this round (source-routing only)
    */
   public record Breakdown(int inventory, int nearbyDrops, int inFlightDrops,
                           int mineableYield, int waypointTerm) {
      /** Sufficiency axis: only what the bot already has / is about to pick up. */
      public int sufficiencyCount() {
         return this.inventory + this.nearbyDrops + this.inFlightDrops;
      }

      /** Source-routing capacity: reachable local sources (mineable blocks + EllieGPS term). */
      public int localSourceCapacity() {
         return this.mineableYield + this.waypointTerm;
      }
   }

   /**
    * Type-aware breakdown for one {@link ItemTarget} at {@code origin}, using exact-identity matching.
    * The in-flight-drops term is always 0 here (it is supplied by the mine/collect flow that knows it
    * just broke a block &mdash; WS3); callers may add it onto {@link Breakdown#sufficiencyCount()}.
    */
   public static Breakdown count(PlayerEngineController mod, ItemTarget target, Vec3 origin,
                                 double dropRadius, double localSourceBlockRadius) {
      int inventory = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
      int nearbyDrops = countNearbyDrops(mod, target, origin, dropRadius);
      int mineableYield = countMineableYield(mod, target, origin, localSourceBlockRadius);
      int waypointTerm = waypointStub.estimateNearbyWaypointItems(target, origin, localSourceBlockRadius);
      return new Breakdown(inventory, nearbyDrops, 0, mineableYield, waypointTerm);
   }

   /**
    * SUFFICIENCY axis &mdash; "am I done?" (summed across targets, type-aware). Mineable blocks and the
    * EllieGPS term are <b>never</b> included. This is what the scoped count gates call. A target is met
    * when inventory + reachable nearby ground drops &ge; its required count.
    */
   public static boolean targetsMetSufficiency(PlayerEngineController mod, Vec3 origin,
                                               double dropRadius, ItemTarget... targets) {
      for (ItemTarget target : targets) {
         if (ItemTarget.nullOrEmpty(target)) {
            continue;
         }
         int inventory = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
         int nearbyDrops = countNearbyDrops(mod, target, origin, dropRadius);
         if (inventory + nearbyDrops < target.getTargetCount()) {
            return false;
         }
      }
      return true;
   }

   /**
    * SOURCE-ROUTING axis &mdash; "can a reachable local source cover the remainder?". Used by
    * {@code MineOrCollectTask.getWanderTask} before returning the bounded wander.
    * {@code remainder = required - sufficiencyCount}; returns {@code true} when
    * {@link Breakdown#localSourceCapacity()} &ge; remainder. If the sufficiency axis already covers the
    * target (remainder &le; 0) this returns {@code true} (no wander needed).
    */
   public static boolean localSourceCanCoverRemainder(PlayerEngineController mod, ItemTarget target,
                                                      Vec3 origin, double dropRadius,
                                                      double localSourceBlockRadius) {
      if (ItemTarget.nullOrEmpty(target)) {
         return true;
      }
      Breakdown breakdown = count(mod, target, origin, dropRadius, localSourceBlockRadius);
      int remainder = target.getTargetCount() - breakdown.sufficiencyCount();
      if (remainder <= 0) {
         return true;
      }
      return breakdown.localSourceCapacity() >= remainder;
   }

   /**
    * Reachable settled ground drops within {@code dropRadius} matching {@code target} by exact item
    * identity. Reuses the shared {@link com.player2.playerengine.trackers.EntityTracker#getItemDropsWithin}
    * <b>enumeration</b> (the {@code !isRemoved} structural filter lives there); the aggregate supplies
    * its OWN exact-identity {@link Predicate} (NOT shared with {@code GatherLooseItemsTask.matchesFilter},
    * which keeps substring semantics &mdash; plan decision 1).
    */
   private static int countNearbyDrops(PlayerEngineController mod, ItemTarget target, Vec3 origin,
                                       double dropRadius) {
      Predicate<ItemEntity> pred =
         entity -> !entity.isRemoved() && target.matches(entity.getItem().getItem());
      List<ItemEntity> drops = mod.getEntityTracker().getItemDropsWithin(origin, dropRadius, pred);
      int total = 0;
      for (ItemEntity entity : drops) {
         total += entity.getItem().getCount();
      }
      return total;
   }

   /**
    * SOURCE-ROUTING term only &mdash; estimated yield of reachable nearby mineable blocks. For each
    * matched {@link Item} the source {@link Block} is resolved with the generic vanilla
    * {@link Block#byItem(Item)} (plan decision 13: {@code BlockItem -> getBlock()}, else
    * {@link Blocks#AIR}); {@code AIR} resolutions (loot-table drops such as {@code diamond}) are skipped
    * and contribute 0 (visible degradation, covered later by a future loot-table resolver). Tracked
    * locations come from {@link com.player2.playerengine.commands.BlockScanner#getKnownLocations},
    * post-filtered by {@code localSourceBlockRadius} and {@link WorldHelper#canReach} (the fast path:
    * blacklist + ocean-avoidance, NO {@code CalculationContext}; {@code canBreak} is reserved for the
    * actual mine decision). Yield is conservative: reachable block count &times; {@link #YIELD_PER_BLOCK}.
    */
   private static int countMineableYield(PlayerEngineController mod, ItemTarget target, Vec3 origin,
                                         double localSourceBlockRadius) {
      List<Block> blocks = new ArrayList<>();
      for (Item item : target.getMatches()) {
         Block block = Block.byItem(item);
         if (block != Blocks.AIR && !blocks.contains(block)) {
            blocks.add(block);
         }
      }
      if (blocks.isEmpty()) {
         return 0;
      }
      double radiusSq = localSourceBlockRadius * localSourceBlockRadius;
      int reachableBlocks = 0;
      for (BlockPos pos : mod.getBlockScanner().getKnownLocations(blocks.toArray(new Block[0]))) {
         if (Vec3.atCenterOf(pos).distanceToSqr(origin) > radiusSq) {
            continue;
         }
         if (!WorldHelper.canReach(mod, pos)) {
            continue;
         }
         reachableBlocks++;
      }
      return reachableBlocks * YIELD_PER_BLOCK;
   }
}
