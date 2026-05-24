package com.player2.playerengine.tasks.misc;

import com.player2.playerengine.equip.PendingArmorPickup;
import com.player2.playerengine.equip.PickupArmorEvalQueue;
import com.player2.playerengine.tasks.base.Task;

public class ProcessPickupArmorTask extends Task {
   private final PendingArmorPickup pickup;
   private boolean processed;

   public ProcessPickupArmorTask(PendingArmorPickup pickup) {
      this.pickup = pickup;
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      if (!this.processed) {
         if (this.controller.getExplicitEquipPolicy().allowsAutoEquip(this.pickup.armorSlot())) {
            int mainSlot = PickupArmorEvalQueue.resolveMainInventorySlot(this.controller, this.pickup.armorSlot());
            if (mainSlot >= 0) {
               this.controller
                     .getSlotHandler()
                     .equipArmorFromMainSlot(this.controller, mainSlot, this.pickup.armorSlot());
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
      return other instanceof ProcessPickupArmorTask task && this.pickup.equals(task.pickup);
   }

   @Override
   protected String toDebugString() {
      return "Process pickup armor: " + this.pickup.armorSlot();
   }
}
