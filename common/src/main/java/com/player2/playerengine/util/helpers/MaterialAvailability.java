package com.player2.playerengine.util.helpers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.EllieGPSCountingService;
import com.player2.playerengine.agentic.elliegps.EllieGPSCountingServiceStub;
import com.player2.playerengine.agentic.elliegps.EllieGPSWaypointCountingService;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.ItemTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic, on-device, type-aware material-availability counter (WS1 of
 * {@code masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md}; C5 EllieGPS seam
 * wired in {@link #setWaypointSource}).
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
 *       {@link Block#byItem(Item)}) plus the EllieGPS waypoint term (keyword-matched, snapshot-bearing,
 *       same-dimension, non-stale waypoints within radius; 0 when EllieGPS is disabled or no world is
 *       loaded).</li>
 * </ul>
 *
 * <p>Matching is by <b>exact item identity</b> via {@link ItemTarget#matches(Item)} /
 * {@link ItemTarget#getMatches()} only ("oak_log" means oak_log, never any-log). Counting performs no
 * network/model call, spawns no threads, never blocks {@code onTick()}, and returns a number (or 0)
 * immediately.
 */
public final class MaterialAvailability {

   /**
    * EllieGPS counting seam. Starts as the no-op stub; swapped to the real
    * {@link EllieGPSWaypointCountingService} exactly once at {@code SERVER_STARTING} via
    * {@link #setWaypointSource}. The {@code ellieGpsEnabled} toggle is enforced per-call
    * inside {@link #count} — the swap itself is never reversed.
    */
   private static volatile EllieGPSCountingService waypointSource = EllieGPSCountingServiceStub.INSTANCE;

   /**
    * Swaps in the real EllieGPS counting service at {@code SERVER_STARTING}.
    * A null argument resets to the stub (used by {@code SERVER_STOPPING} cleanup if needed).
    * Called exactly once per server start from the lifecycle wiring (WS7).
    */
   public static void setWaypointSource(EllieGPSCountingService service) {
      waypointSource = service != null ? service : EllieGPSCountingServiceStub.INSTANCE;
   }

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
    * @param waypointTerm  EllieGPS waypoint term: keyword-matched snapshot-bearing same-dimension
    *                      non-stale waypoints within radius; 0 when disabled, no world, or no match
    *                      (source-routing only)
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
      int inventory     = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
      int nearbyDrops   = countNearbyDrops(mod, target, origin, dropRadius);
      int mineableYield = countMineableYield(mod, target, origin, localSourceBlockRadius);

      // EllieGPS waypoint term (Decision 11): gate on ellieGpsEnabled first; then call the
      // 4-arg dimension-aware overload if the real service is installed, else the 3-arg stub.
      int waypointTerm;
      EllieGPSCountingService ws = waypointSource;
      boolean ellieEnabled = mod.getModSettings().getEllieGpsEnabled();
      if (!ellieEnabled) {
         // Disabled — take stub path (term 0), skip the instanceof branch.
         waypointTerm = 0;
      } else if (ws instanceof EllieGPSWaypointCountingService real) {
         // Real service installed: compute dimension from the bot's world and call 4-arg overload.
         ServerLevel world = mod.getWorld();
         String dimensionId = world != null
               ? world.dimension().location().toString() : null;
         waypointTerm = real.estimateNearbyWaypointItems(target, origin, localSourceBlockRadius, dimensionId);
         // Debug Breakdown line (runtime test #11's observable): emitted only when the real
         // service is installed, so it cannot spam per-tick. count() is called only on
         // source-routing decisions (localSourceCanCoverRemainder / reachableLocalSourceCoversNetDemand).
         Breakdown bd = new Breakdown(inventory, nearbyDrops, 0, mineableYield, waypointTerm);
         Debug.logInternal("EllieGPS Breakdown [dim=%s]: inv=%d drops=%d mineable=%d waypoint=%d | sufficiency=%d sourceCapacity=%d",
               dimensionId, bd.inventory(), bd.nearbyDrops(), bd.mineableYield(), bd.waypointTerm(),
               bd.sufficiencyCount(), bd.localSourceCapacity());
         return bd;
      } else {
         // Stub path (service not yet swapped or disabled at field level).
         waypointTerm = ws.estimateNearbyWaypointItems(target, origin, localSourceBlockRadius);
      }

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
    * SOURCE-ROUTING axis, RESERVATION-AWARE variant &mdash; "is there a reachable local source (mineable
    * blocks or reachable ground drops) that can still cover this NET demand, WITHOUT crediting already-held
    * inventory toward it?". Unlike {@link #localSourceCanCoverRemainder}, this does NOT subtract
    * {@code sufficiencyCount()}'s inventory term, so it is correct for a caller whose {@code target} is the
    * resolver's NET {@code externalNeeded()} figure: that count is already net of held-but-reserved stock,
    * so re-crediting the held (reserved) inventory here would both (a) falsely zero a genuine remainder when
    * a reserved log is held and a second is reachable (the COLLECT-stall false-negative) and (b) falsely
    * report "reachable" purely off reserved inventory when NO source exists (an unbounded-mine false
    * positive). Progress is judged purely on reachable EXTERNAL capacity: reachable nearby ground drops plus
    * estimated mineable-block yield meeting the net demand. The genuine no-source case returns {@code false}
    * (capacity 0 &lt; positive net demand), so the caller's bounded-termination backstop still engages.
    */
   public static boolean reachableLocalSourceCoversNetDemand(PlayerEngineController mod, ItemTarget target,
                                                             Vec3 origin, double dropRadius,
                                                             double localSourceBlockRadius) {
      if (ItemTarget.nullOrEmpty(target) || target.getTargetCount() <= 0) {
         return false;
      }
      Breakdown breakdown = count(mod, target, origin, dropRadius, localSourceBlockRadius);
      int reachableExternalCapacity = breakdown.nearbyDrops()
            + breakdown.inFlightDrops()
            + breakdown.localSourceCapacity();
      return reachableExternalCapacity >= target.getTargetCount();
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
    *
    * <p>LIVE-STATE VALIDATION: the scanner's tracked sets are append-only (a mined-away position
    * persists until {@code reset()}), so a tracked position in a LOADED chunk counts only if the
    * world's CURRENT block there is still one of the resolved {@code blocks} &mdash; the same
    * read-time idiom the scanner's own consumers ({@code getNearestBlock}/{@code anyFound}) apply.
    * Positions in UNLOADED chunks are KEPT (trust the cache): {@code Level.getBlockState} on an
    * unloaded chunk would synchronously load it, and skipping far unloaded-but-real sources would
    * falsely flip them to unfundable and cause premature UNOBTAINABLE terminates &mdash; the stale
    * entries that matter (just-mined blocks near the bot) are always in loaded chunks.
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
         // Live-state validation, loaded chunks only (isChunkLoaded is the non-loading hasChunk
         // probe). getKnownLocations merges positions across ALL requested blocks, so compare the
         // live block against the whole resolved list, not any single block.
         if (mod.getChunkTracker().isChunkLoaded(pos)
               && !blocks.contains(mod.getWorld().getBlockState(pos).getBlock())) {
            continue;
         }
         reachableBlocks++;
      }
      return reachableBlocks * YIELD_PER_BLOCK;
   }
}
