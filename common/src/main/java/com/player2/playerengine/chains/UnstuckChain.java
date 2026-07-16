package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.construction.DestroyBlockTask;
import com.player2.playerengine.tasks.movement.GetOutOfWaterTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.tasks.movement.SafeRandomShimmyTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.util.time.TimerGame;
import com.player2.playerengine.automaton.api.utils.input.Input;
import java.util.LinkedList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class UnstuckChain extends SingleTaskChain {
   private static final int RECOVERY_LEASE_TICKS = 200;
   private static final int RECOVERY_COOLDOWN_TICKS = 100;

   private final LinkedList<Vec3> posHistory = new LinkedList<>();
   private final TimerGame shimmyTimer = new TimerGame(5.0);
   private final TimerGame placeBlockGoToBlockTimeout = new TimerGame(5.0);
   private final RecoveryLease recoveryLease = new RecoveryLease(RECOVERY_LEASE_TICKS, RECOVERY_COOLDOWN_TICKS);
   private boolean isProbablyStuck = false;
   private int eatingTicks = 0;
   private boolean interruptedEating = false;
   private boolean startedShimmying = false;
   private BlockPos placeBlockGoToBlock = null;

   public UnstuckChain(TaskRunner runner) {
      super(runner);
   }

   @Override
   public float getPriority() {
      if (this.controller != null && this.controller.getTaskRunner().isActive()) {
         LivingEntity player = this.controller.getEntity();
         if (this.mainTask != null) {
            // An entity can disappear between controller ticks. Do not tick a task whose
            // isFinished implementation expects a live player; seal and detach it directly.
            if (player == null) {
               Task detached = this.mainTask;
               detached.stop();
               this.recoveryLease.release(detached);
               this.mainTask = null;
               return Float.NEGATIVE_INFINITY;
            }
            return this.getRecoveryPriority(player);
         }
         if (player == null || this.recoveryLease.consumeCooldownTick()) {
            return Float.NEGATIVE_INFINITY;
         }

         this.isProbablyStuck = false;
         this.posHistory.addFirst(player.position());
         if (this.posHistory.size() > 500) {
            this.posHistory.removeLast();
         }

         this.checkStuckInWater();
         this.checkStuckInPowderSnow();
         this.checkEatingGlitch();
         this.checkStuckOnEndPortalFrame();
         if (this.isProbablyStuck) {
            return 65.0F;
         } else if (this.startedShimmying && !this.shimmyTimer.elapsed()) {
            this.selectRecovery(new SafeRandomShimmyTask(), false);
            return 65.0F;
         } else {
            this.startedShimmying = false;
            if (this.placeBlockGoToBlockTimeout.elapsed()) {
               this.placeBlockGoToBlock = null;
            }

            if (this.placeBlockGoToBlock != null) {
               this.selectRecovery(new GetToBlockTask(this.placeBlockGoToBlock, false), false);
               return 65.0F;
            } else {
               return Float.NEGATIVE_INFINITY;
            }
         }
      } else {
         return Float.NEGATIVE_INFINITY;
      }
   }

   private float getRecoveryPriority(LivingEntity player) {
      Task recovery = this.mainTask;
      this.recoveryLease.begin(recovery, false);

      if (this.recoveryLease.isPowderSnowShimmy(recovery)
            && powderSnowShimmyShouldStop(true, player.isInPowderSnow)) {
         recovery.stop();
      }

      RecoveryPriorityDecision decision = this.recoveryLease.decision(recovery);
      if (decision == RecoveryPriorityDecision.EXPIRE) {
         // A recovery that never terminates must yield to the interrupted user task. The stopped
         // task keeps priority for this one scheduler turn so SingleTaskChain can reap it; the
         // cooldown then prevents an immediate select/interrupt loop.
         this.recoveryLease.markExpired(recovery);
         this.posHistory.clear();
         recovery.stop();
         return 65.0F;
      }
      return decision == RecoveryPriorityDecision.RELEASE ? Float.NEGATIVE_INFINITY : 65.0F;
   }

   private void selectRecovery(Task candidate, boolean powderSnowShimmy) {
      this.setTask(candidate);
      // setTask intentionally preserves an equal in-flight task. begin() keys on identity, so
      // re-evaluating the same recovery cannot silently renew its lease.
      this.recoveryLease.begin(this.mainTask, powderSnowShimmy);
   }

   /** Package-visible pure exit policy exercised by {@link UnstuckChainSelfTest}. */
   static boolean powderSnowShimmyShouldStop(boolean powderSnowShimmy, boolean inPowderSnow) {
      return powderSnowShimmy && !inPowderSnow;
   }

   private void checkStuckInWater() {
      if (this.posHistory.size() >= 100) {
         LivingEntity player = this.controller.getEntity();
         Level world = this.controller.getWorld();
         if (world == null) {
            return;
         }
         if (world.getBlockState(player.blockPosition()).is(Blocks.WATER)) {
            if (!player.onGround() && player.getAirSupply() >= player.getMaxAirSupply()) {
               Vec3 firstPos = this.posHistory.get(0);

               for (int i = 1; i < 100; i++) {
                  Vec3 nextPos = this.posHistory.get(i);
                  if (Math.abs(firstPos.x() - nextPos.x()) > 0.75 || Math.abs(firstPos.z() - nextPos.z()) > 0.75) {
                     return;
                  }
               }

               this.posHistory.clear();
               this.selectRecovery(new GetOutOfWaterTask(), false);
               this.isProbablyStuck = true;
            } else {
               this.posHistory.clear();
            }
         }
      }
   }

   private void checkStuckInPowderSnow() {
      LivingEntity player = this.controller.getEntity();
      if (player.isInPowderSnow) {
         this.isProbablyStuck = true;
         BlockPos playerPos = player.blockPosition();
         BlockPos toBreak = null;
         if (player.level().getBlockState(playerPos).is(Blocks.POWDER_SNOW)) {
            toBreak = playerPos;
         } else if (player.level().getBlockState(playerPos.above()).is(Blocks.POWDER_SNOW)) {
            toBreak = playerPos.above();
         }

         if (toBreak != null) {
            this.selectRecovery(new DestroyBlockTask(toBreak), false);
         } else {
            this.selectRecovery(new SafeRandomShimmyTask(), true);
         }
      }
   }

   private void checkStuckOnEndPortalFrame() {
      Level world = this.controller.getWorld();
      if (world == null) {
         return;
      }
      BlockState standingOn = world.getBlockState(this.controller.getEntity().getOnPos());
      if (standingOn.is(Blocks.END_PORTAL_FRAME)
         && !(Boolean)standingOn.getValue(EndPortalFrameBlock.HAS_EYE)
         && !this.controller.getFoodChain().isTryingToEat()) {
         this.isProbablyStuck = true;
         this.controller.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
      }
   }

   private void checkEatingGlitch() {
      FoodChain foodChain = this.controller.getFoodChain();
      if (this.interruptedEating) {
         foodChain.shouldStop(false);
         this.interruptedEating = false;
      }

      if (foodChain.isTryingToEat()) {
         this.eatingTicks++;
      } else {
         this.eatingTicks = 0;
      }

      if (this.eatingTicks > 140) {
         Debug.logMessage("Bot is probably stuck trying to eat. Resetting action.");
         foodChain.shouldStop(true);
         this.eatingTicks = 0;
         this.interruptedEating = true;
         this.isProbablyStuck = true;
      }
   }

   @Override
   public boolean isActive() {
      return true;
   }

   @Override
   protected void onTick() {
      Task recovery = this.mainTask;
      this.recoveryLease.consumeExecutionTick(recovery);
      super.onTick();
   }

   @Override
   protected void onStop() {
      super.onStop();
      this.recoveryLease.reset();
   }

   @Override
   protected void onTaskFinish(PlayerEngineController controller) {
      // SingleTaskChain delegates ownership cleanup to subclasses. Clearing here releases the
      // retained recovery priority immediately after the terminal reap tick.
      this.recoveryLease.release(this.mainTask);
      this.mainTask = null;
   }

   @Override
   public String getName() {
      return "Unstuck Chain";
   }

   enum RecoveryPriorityDecision {
      RELEASE,
      RETAIN,
      REAP,
      EXPIRE
   }

   /** Detached, tick-counted scheduler state. It deliberately has no wall-clock dependency. */
   static final class RecoveryLease {
      private final int leaseTicks;
      private final int cooldownTicks;
      private Task recovery;
      private int executionTicksRemaining;
      private int cooldownTicksRemaining;
      private boolean powderSnowShimmy;

      RecoveryLease(int leaseTicks, int cooldownTicks) {
         if (leaseTicks <= 0 || cooldownTicks <= 0) {
            throw new IllegalArgumentException("recovery lease and cooldown must be positive");
         }
         this.leaseTicks = leaseTicks;
         this.cooldownTicks = cooldownTicks;
      }

      void begin(Task candidate, boolean isPowderSnowShimmy) {
         if (candidate == null) {
            return;
         }
         if (candidate == this.recovery) {
            this.powderSnowShimmy |= isPowderSnowShimmy;
            return;
         }
         this.recovery = candidate;
         this.executionTicksRemaining = this.leaseTicks;
         this.powderSnowShimmy = isPowderSnowShimmy;
      }

      RecoveryPriorityDecision decision(Task current) {
         if (current == null) {
            return RecoveryPriorityDecision.RELEASE;
         }
         this.begin(current, false);
         if (current.stopped()) {
            return RecoveryPriorityDecision.REAP;
         }
         if (current.isAssigned()) {
            return RecoveryPriorityDecision.RETAIN;
         }
         if (current.isFinished()) {
            return RecoveryPriorityDecision.REAP;
         }
         if (current.isActive() && this.executionTicksRemaining <= 0) {
            return RecoveryPriorityDecision.EXPIRE;
         }
         // setTask always marks a task assigned, but retaining an unexpected nonterminal state is
         // safer than leaving an owned task at negative infinity where it can never be reaped.
         return RecoveryPriorityDecision.RETAIN;
      }

      void consumeExecutionTick(Task current) {
         if (current == this.recovery
               && this.executionTicksRemaining > 0
               && !current.stopped()
               && (current.isAssigned() || (current.isActive() && !current.isFinished()))) {
            this.executionTicksRemaining--;
         }
      }

      void markExpired(Task current) {
         if (current == this.recovery) {
            this.executionTicksRemaining = 0;
            this.cooldownTicksRemaining = Math.max(this.cooldownTicksRemaining, this.cooldownTicks);
         }
      }

      boolean consumeCooldownTick() {
         if (this.cooldownTicksRemaining <= 0) {
            return false;
         }
         this.cooldownTicksRemaining--;
         return true;
      }

      boolean isPowderSnowShimmy(Task current) {
         return current != null && current == this.recovery && this.powderSnowShimmy;
      }

      int executionTicksRemaining() {
         return this.executionTicksRemaining;
      }

      void release(Task current) {
         if (current == this.recovery) {
            this.recovery = null;
            this.executionTicksRemaining = 0;
            this.powderSnowShimmy = false;
         }
      }

      void reset() {
         this.recovery = null;
         this.executionTicksRemaining = 0;
         this.cooldownTicksRemaining = 0;
         this.powderSnowShimmy = false;
      }
   }
}
