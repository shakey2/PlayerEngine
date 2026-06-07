package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.entity.KillEntitiesTask;
import com.player2.playerengine.tasks.base.ITaskRequiresGrounded;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.helpers.EntityHelper;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import com.player2.playerengine.util.progresscheck.MovementProgressChecker;
import com.player2.playerengine.util.slots.Slot;
import com.player2.playerengine.util.time.TimerGame;
import com.player2.playerengine.automaton.api.utils.input.Input;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
   private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
   private final float distanceToWander;
   private final MovementProgressChecker progressChecker = new MovementProgressChecker();
   private final boolean increaseRange;
   private final TimerGame timer = new TimerGame(60.0);
   Block[] annoyingBlocks = new Block[]{
      Blocks.VINE,
      Blocks.NETHER_SPROUTS,
      Blocks.CAVE_VINES,
      Blocks.CAVE_VINES_PLANT,
      Blocks.TWISTING_VINES,
      Blocks.TWISTING_VINES_PLANT,
      Blocks.WEEPING_VINES_PLANT,
      Blocks.LADDER,
      Blocks.BIG_DRIPLEAF,
      Blocks.BIG_DRIPLEAF_STEM,
      Blocks.SMALL_DRIPLEAF,
      Blocks.TALL_GRASS,
      Blocks.GRASS,
      Blocks.SWEET_BERRY_BUSH
   };
   private Vec3 origin;
   private boolean forceExplore;
   private Task unstuckTask = null;
   private int failCounter;
   private double wanderDistanceExtension;

   // --- Bounded-wander state (WS4). All opt-in; null deadline == legacy infinite semantics. ---
   /**
    * Time budget in milliseconds measured from {@link #onStart()}. When non-null, {@link #isFinished()}
    * additionally returns true once this many ms have elapsed since the wander started. Null preserves
    * the legacy infinite/distance-only behaviour of the existing ctors.
    */
   @Nullable
   private final Long deadlineMs;
   /**
    * No-improvement give-up window in seconds. When bounded, the wander gives up if the player has made
    * no net distance progress away from {@link #origin} for this many seconds. Initialised to
    * {@link #DEFAULT_NO_IMPROVEMENT_SECONDS} and, for bounded wanders, refreshed in {@link #onStart()}
    * from the configured knob {@code PlayerEngineSettings.getWanderNoImprovementSeconds()} (WS5). A
    * configured value of 0 disables the no-improvement check (see {@link #boundExpired()}); the default
    * is used only when settings are unavailable.
    */
   private double noImprovementGiveUpSeconds = DEFAULT_NO_IMPROVEMENT_SECONDS;
   /** Default no-improvement give-up window (seconds); plan WS4/WS5 default = 30. */
   private static final double DEFAULT_NO_IMPROVEMENT_SECONDS = 30.0;
   /** Wall-clock start time captured in {@link #onStart()} for the deadline / telemetry checks. */
   private long startTimeMs;
   /** Best (largest) squared distance from origin observed so far, for the no-improvement check. */
   private double bestProgressSq;
   /** Wall-clock time (ms) at which bestProgressSq last improved meaningfully. */
   private long lastImprovementMs;
   /** Debug: elapsed ms of the current bounded wander, updated each tick (silent-hang detection). */
   private long debugElapsedMs;
   /** One-shot guard so the over-threshold telemetry log fires once per wander. */
   private boolean overThresholdLogged;
   /** Telemetry threshold: log when a bounded wander has run this long without finishing. */
   private static final long BOUNDED_OVER_THRESHOLD_MS = 60_000L;
   /** Minimum squared-distance gain to count as "progress" for the no-improvement check. */
   private static final double NO_IMPROVEMENT_EPSILON_SQ = 4.0;

   public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
      this(distanceToWander, increaseRange, null);
   }

   /**
    * Backward-compatible bounded ctor (WS4). With {@code deadlineMs == null} this behaves exactly like
    * {@link #TimeoutWanderTask(float, boolean)} (legacy distance-only / infinite semantics). With a
    * non-null {@code deadlineMs} the wander is additionally bounded by a wall-clock budget (measured
    * from {@link #onStart()}) and a no-improvement give-up, regardless of {@code distanceToWander}
    * (so {@code Float.POSITIVE_INFINITY} distance + a deadline yields a deadline-bounded wander).
    */
   public TimeoutWanderTask(float distanceToWander, boolean increaseRange, @Nullable Long deadlineMs) {
      this.distanceToWander = distanceToWander;
      this.increaseRange = increaseRange;
      this.forceExplore = false;
      this.deadlineMs = deadlineMs;
   }

   public TimeoutWanderTask(float distanceToWander) {
      this(distanceToWander, false);
   }

   public TimeoutWanderTask() {
      this(Float.POSITIVE_INFINITY, false);
   }

   public TimeoutWanderTask(boolean forceExplore) {
      this();
      this.forceExplore = forceExplore;
   }

   /**
    * Convenience factory (WS4): an infinite-distance wander bounded only by a wall-clock deadline
    * (measured from {@link #onStart()}) and a no-improvement give-up. Use this for the discrete
    * resource / agentic / recovery callers that must not wander forever; legitimately-unbounded
    * exploration callers keep using the existing infinite ctors.
    *
    * @param deadlineMs time budget in milliseconds from wander start before the task self-terminates.
    */
   public static TimeoutWanderTask bounded(long deadlineMs) {
      return new TimeoutWanderTask(Float.POSITIVE_INFINITY, false, deadlineMs);
   }

   private static BlockPos[] generateSides(BlockPos pos) {
      return new BlockPos[]{
         pos.offset(1, 0, 0),
         pos.offset(-1, 0, 0),
         pos.offset(0, 0, 1),
         pos.offset(0, 0, -1),
         pos.offset(1, 0, -1),
         pos.offset(1, 0, 1),
         pos.offset(-1, 0, -1),
         pos.offset(-1, 0, 1)
      };
   }

   private boolean isAnnoying(PlayerEngineController mod, BlockPos pos) {
      Block[] arrayOfBlock = this.annoyingBlocks;
      int i = arrayOfBlock.length;
      byte b = 0;
      if (b >= i) {
         return false;
      } else {
         Block AnnoyingBlocks = arrayOfBlock[b];
         return mod.getWorld().getBlockState(pos).getBlock() == AnnoyingBlocks
            || mod.getWorld().getBlockState(pos).getBlock() instanceof DoorBlock
            || mod.getWorld().getBlockState(pos).getBlock() instanceof FenceBlock
            || mod.getWorld().getBlockState(pos).getBlock() instanceof FenceGateBlock
            || mod.getWorld().getBlockState(pos).getBlock() instanceof FlowerBlock;
      }
   }

   public void resetWander() {
      this.wanderDistanceExtension = 0.0;
   }

   private BlockPos stuckInBlock(PlayerEngineController mod) {
      BlockPos p = mod.getPlayer().blockPosition();
      if (this.isAnnoying(mod, p)) {
         return p;
      } else if (this.isAnnoying(mod, p.above())) {
         return p.above();
      } else {
         BlockPos[] toCheck = generateSides(p);

         for (BlockPos check : toCheck) {
            if (this.isAnnoying(mod, check)) {
               return check;
            }
         }

         BlockPos[] toCheckHigh = generateSides(p.above());

         for (BlockPos checkx : toCheckHigh) {
            if (this.isAnnoying(mod, checkx)) {
               return checkx;
            }
         }

         return null;
      }
   }

   private Task getFenceUnstuckTask() {
      return new SafeRandomShimmyTask();
   }

   @Override
   protected void onStart() {
      PlayerEngineController mod = this.controller;
      this.timer.reset();
      mod.getBaritone().getPathingBehavior().forceCancel();
      this.origin = mod.getPlayer().position();
      this.progressChecker.reset();
      this.stuckCheck.reset();
      this.failCounter = 0;
      // Bounded-wander bookkeeping (WS4): only consulted when deadlineMs != null.
      // WS5 wiring: source the no-improvement give-up window from the configured knob
      // (wanderNoImprovementSeconds) now that the task has its controller. 0 = off (handled in
      // boundExpired). Fall back to the default only when settings are unavailable, never overriding an
      // explicit configured value (including an explicit 0). Only relevant for bounded wanders.
      if (this.deadlineMs != null) {
         PlayerEngineSettings settings = mod.getModSettings();
         if (settings != null) {
            this.noImprovementGiveUpSeconds = settings.getWanderNoImprovementSeconds();
         }
      }
      this.startTimeMs = System.currentTimeMillis();
      this.lastImprovementMs = this.startTimeMs;
      this.bestProgressSq = 0.0;
      this.debugElapsedMs = 0L;
      this.overThresholdLogged = false;
      ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot(this.controller);
      if (!cursorStack.isEmpty()) {
         Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
         moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
         if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
         }

         Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
         garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, ClickType.PICKUP));
         mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, ClickType.PICKUP);
      }
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      this.updateBoundedProgress(mod);
      if (mod.getBaritone().getPathingBehavior().isPathing()) {
         this.progressChecker.reset();
      }

      if (WorldHelper.isInNetherPortal(this.controller)) {
         if (!mod.getBaritone().getPathingBehavior().isPathing()) {
            this.setDebugState("Getting out from nether portal");
            mod.getInputControls().hold(Input.SNEAK);
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            return null;
         }

         mod.getInputControls().release(Input.SNEAK);
         mod.getInputControls().release(Input.MOVE_BACK);
         mod.getInputControls().release(Input.MOVE_FORWARD);
      } else if (mod.getBaritone().getPathingBehavior().isPathing()) {
         mod.getInputControls().release(Input.SNEAK);
         mod.getInputControls().release(Input.MOVE_BACK);
         mod.getInputControls().release(Input.MOVE_FORWARD);
      }

      if (this.unstuckTask != null && this.unstuckTask.isActive() && !this.unstuckTask.isFinished() && this.stuckInBlock(mod) != null) {
         this.setDebugState("Getting unstuck from block.");
         this.stuckCheck.reset();
         mod.getBaritone().getCustomGoalProcess().onLostControl();
         mod.getBaritone().getExploreProcess().onLostControl();
         return this.unstuckTask;
      } else {
         if (!this.progressChecker.check(mod) || !this.stuckCheck.check(mod)) {
            for (Entity CloseEntities : mod.getEntityTracker().getCloseEntities()) {
               if (CloseEntities instanceof Mob
                  && !EntityHelper.isZombifiedPiglinFamily(CloseEntities)
                  && CloseEntities.position().closerThan(mod.getPlayer().position(), 1.0)
                  && CloseEntities != mod.getEntity()) {
                  this.setDebugState("Killing annoying entity.");
                  return new KillEntitiesTask(CloseEntities.getClass());
               }
            }

            BlockPos blockStuck = this.stuckInBlock(mod);
            if (blockStuck != null) {
               this.failCounter++;
               this.unstuckTask = this.getFenceUnstuckTask();
               return this.unstuckTask;
            }

            this.stuckCheck.reset();
         }

         this.setDebugState("Exploring.");
         switch (WorldHelper.getCurrentDimension(this.controller)) {
            case END:
               if (this.timer.getDuration() >= 30.0) {
                  this.timer.reset();
               }
               break;
            case OVERWORLD:
            case NETHER:
               if (this.timer.getDuration() >= 30.0) {
               }

               if (this.timer.elapsed()) {
                  this.timer.reset();
               }
         }

         if (!mod.getBaritone().getExploreProcess().isActive()) {
            mod.getBaritone().getExploreProcess().explore((int)this.origin.x(), (int)this.origin.z());
         }

         if (!this.progressChecker.check(mod)) {
            this.progressChecker.reset();
            if (!this.forceExplore) {
               this.failCounter++;
               Debug.logMessage("Failed exploring.");
               if (this.progressChecker.lastBreakingBlock != null) {
               }
            }
         }

         return null;
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
      this.controller.getBaritone().getPathingBehavior().forceCancel();
      if (this.isFinished() && this.increaseRange) {
         this.wanderDistanceExtension = this.wanderDistanceExtension + this.distanceToWander;
         Debug.logMessage("Increased wander range");
      }
   }

   /**
    * Per-tick bounded-wander bookkeeping (WS4). Tracks elapsed wall-clock time and the best net
    * distance away from {@link #origin}, and emits a one-shot telemetry log when a bounded wander runs
    * past {@link #BOUNDED_OVER_THRESHOLD_MS} without finishing (silent-hang detection). No-ops entirely
    * when the wander is unbounded ({@code deadlineMs == null}).
    */
   private void updateBoundedProgress(PlayerEngineController mod) {
      if (this.deadlineMs == null) {
         return;
      }

      long now = System.currentTimeMillis();
      this.debugElapsedMs = now - this.startTimeMs;

      LivingEntity player = mod.getPlayer();
      if (player != null && player.position() != null && this.origin != null) {
         double sqDist = player.position().distanceToSqr(this.origin);
         if (sqDist > this.bestProgressSq + NO_IMPROVEMENT_EPSILON_SQ) {
            this.bestProgressSq = sqDist;
            this.lastImprovementMs = now;
         }
      }

      if (!this.overThresholdLogged && this.debugElapsedMs >= BOUNDED_OVER_THRESHOLD_MS) {
         this.overThresholdLogged = true;
         Debug.logMessage(
            "Bounded wander running long: elapsed="
               + this.debugElapsedMs
               + "ms (deadline="
               + this.deadlineMs
               + "ms, noImprovement="
               + this.noImprovementGiveUpSeconds
               + "s)"
         );
      }
   }

   /**
    * Bounded give-up check (WS4). Returns true when a non-null {@link #deadlineMs} has elapsed since
    * {@link #onStart()}, OR no net distance progress from {@link #origin} has occurred for
    * {@link #noImprovementGiveUpSeconds}. Always false when the wander is unbounded.
    */
   private boolean boundExpired() {
      if (this.deadlineMs == null) {
         return false;
      }
      long now = System.currentTimeMillis();
      if (now - this.startTimeMs >= this.deadlineMs) {
         return true;
      }
      double noImprovementMs = this.noImprovementGiveUpSeconds * 1000.0;
      return noImprovementMs > 0.0 && (now - this.lastImprovementMs) >= noImprovementMs;
   }

   @Override
   public boolean isFinished() {
      // WS4: an opt-in deadline / no-improvement give-up bounds even the infinite-distance wander.
      // Unbounded callers (deadlineMs == null) keep their exact legacy behaviour below.
      if (this.boundExpired()) {
         return true;
      } else if (Float.isInfinite(this.distanceToWander)) {
         return false;
      } else if (this.failCounter > 10) {
         return true;
      } else {
         LivingEntity player = this.controller.getPlayer();
         if (player != null && player.position() != null && (player.onGround() || player.isInWater())) {
            double sqDist = player.position().distanceToSqr(this.origin);
            double toWander = this.distanceToWander + this.wanderDistanceExtension;
            return sqDist > toWander * toWander;
         } else {
            return false;
         }
      }
   }

   @Override
   protected boolean isEqual(Task other) {
      if (other instanceof TimeoutWanderTask task) {
         return !Float.isInfinite(task.distanceToWander) && !Float.isInfinite(this.distanceToWander)
            ? Math.abs(task.distanceToWander - this.distanceToWander) < 0.5F
            : Float.isInfinite(task.distanceToWander) == Float.isInfinite(this.distanceToWander);
      } else {
         return false;
      }
   }

   @Override
   protected String toDebugString() {
      return "Wander for " + this.distanceToWander + this.wanderDistanceExtension + " blocks";
   }
}
