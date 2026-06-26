package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.tasks.base.Task;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

public class FollowPlayerTask extends Task {
   private static final Logger LOGGER = LogManager.getLogger();

   private final String playerName;
   private final double followDistance;
   /**
    * Set when we reached the followed player's last-known position but they are gone (no longer in
    * the tracker / not merely out of render distance) — the death/disconnect/dimension-change signal.
    * Consumed in {@link #onStop} to arm a graceful {@link StopReason#FOLLOWED_TARGET_GONE} on the
    * step executor instead of letting it classify the stop as FATAL:task_stopped_without_finish.
    */
   private boolean targetGone = false;

   public FollowPlayerTask(String playerName, double followDistance) {
      this.playerName = playerName;
      this.followDistance = followDistance;
   }

   public FollowPlayerTask(String playerName) {
      this(playerName, 2.0);
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      Optional<Vec3> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(this.playerName);
      if (lastPos.isEmpty()) {
         this.setDebugState("No player found/detected. Doing nothing until player loads into render distance.");
         return null;
      } else {
         Vec3 target = lastPos.get();
         if (target.closerThan(mod.getPlayer().position(), 1.0) && !mod.getEntityTracker().isPlayerLoaded(this.playerName)) {
            mod.logWarning("Failed to get to player \"" + this.playerName + "\". We moved to where we last saw them but now have no idea where they are.");
            // Reached their last-known position and they are gone (died / disconnected / changed
            // dimension) rather than merely out of render distance — graceful termination, not FATAL.
            this.targetGone = true;
            this.stop();
            return null;
         } else {
            Optional<Player> player = mod.getEntityTracker().getPlayerEntity(this.playerName);
            if (player.isEmpty()) {
               // If we're currently on a boat but lost track of the owner, don't auto-dismount.
               return new GetToBlockTask(new BlockPos((int)target.x, (int)target.y, (int)target.z), false);
            }

            Player targetPlayer = (Player)player.get();
            Entity ownerVehicle = targetPlayer.getVehicle();
            Entity myVehicle = mod.getPlayer().getVehicle();

            // If we're on a boat but the owner isn't (or is on a different vehicle), leave the boat.
            if (myVehicle instanceof Boat && myVehicle != ownerVehicle) {
               mod.getPlayer().stopRiding();
            }

            // If owner is on a boat and there's a seat, try to join.
            if (ownerVehicle instanceof Boat && myVehicle != ownerVehicle) {
               return new EnterBoatWithOwnerTask(targetPlayer, this.followDistance);
            }

            return new GetToEntityTask((Entity)targetPlayer, this.followDistance);
         }
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
      LOGGER.info("[FollowDiag] FOLLOW-TASK-STOP: targetGone={} interruptTask={} isFinished={}",
            this.targetGone,
            (interruptTask != null) ? interruptTask.getClass().getSimpleName() : "(none)",
            this.isFinished());
      // If we stopped because the followed player vanished, arm a graceful FOLLOWED_TARGET_GONE on
      // the step executor before the user task chain fires its terminal onFinish callback. Without
      // this, the chain sees a task that stopped without finishing and labels it FATAL.
      if (this.targetGone && this.controller != null
            && this.controller.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
         adapter.armPendingChainCancel(StopReason.FOLLOWED_TARGET_GONE);
      }
   }

   @Override
   protected boolean isEqual(Task other) {
      return !(other instanceof FollowPlayerTask task)
         ? false
         : task.playerName.equals(this.playerName) && Math.abs(this.followDistance - task.followDistance) < 0.1;
   }

   @Override
   protected String toDebugString() {
      return "Going to player " + this.playerName;
   }
}
