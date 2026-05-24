package com.player2.playerengine.tasks.misc;

import com.player2.playerengine.equip.PendingWeaponPickup;
import com.player2.playerengine.equip.PickupWeaponEvalQueue;
import com.player2.playerengine.tasks.base.Task;
import net.minecraft.world.entity.EquipmentSlot;

public class ProcessPickupWeaponTask extends Task {
   private final PendingWeaponPickup pickup;
   private boolean processed;

   public ProcessPickupWeaponTask(PendingWeaponPickup pickup) {
      this.pickup = pickup;
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      if (!this.processed) {
         if (this.controller.getExplicitEquipPolicy().allowsAutoEquip(EquipmentSlot.MAINHAND)) {
            int mainSlot = PickupWeaponEvalQueue.resolveMainInventorySlot(this.controller);
            if (mainSlot >= 0) {
               this.controller.getSlotHandler().equipWeaponToMainHand(this.controller, mainSlot);
            }
         }
         this.processed = true;
      }
      return null;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   public boolean isFinished() {
      return this.processed;
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof ProcessPickupWeaponTask task && this.pickup.equals(task.pickup);
   }

   @Override
   protected String toDebugString() {
      return "Process pickup weapon: " + this.pickup.pickupId();
   }
}
