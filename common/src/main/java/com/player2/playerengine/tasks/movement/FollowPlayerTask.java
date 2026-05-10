package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

public class FollowPlayerTask extends Task {
   private final String playerName;
   private final double followDistance;

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
