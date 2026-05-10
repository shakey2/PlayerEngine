package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;

public class EnterBoatWithOwnerTask extends Task {
   private final Player owner;
   private final double followDistance;
   private final double mountDistance;

   public EnterBoatWithOwnerTask(Player owner, double followDistance, double mountDistance) {
      this.owner = owner;
      this.followDistance = followDistance;
      this.mountDistance = mountDistance;
   }

   public EnterBoatWithOwnerTask(Player owner, double followDistance) {
      this(owner, followDistance, 1.8);
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      if (this.owner == null || !this.owner.isAlive()) {
         return null;
      }

      Entity vehicle = this.owner.getVehicle();
      if (!(vehicle instanceof Boat boat) || !boat.isAlive()) {
         return null;
      }

      // Already on the same boat
      if (mod.getPlayer().getVehicle() == boat) {
         return null;
      }

      // No seat: just stay nearby.
      if (boat.getPassengers().size() >= 2) {
         return new GetToEntityTask(boat, this.followDistance);
      }

      // Get close enough to mount.
      if (!mod.getPlayer().closerThan(boat, this.mountDistance)) {
         return new GetToEntityTask(boat, this.mountDistance);
      }

      boolean mounted = mod.getPlayer().startRiding(boat, true);
      if (mounted) {
         mod.getBaritone().getPathingBehavior().forceCancel();
      }
      return null;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof EnterBoatWithOwnerTask task
         && task.owner == this.owner
         && Math.abs(task.followDistance - this.followDistance) < 0.1
         && Math.abs(task.mountDistance - this.mountDistance) < 0.1;
   }

   @Override
   protected String toDebugString() {
      return "Entering boat with owner";
   }
}

