package com.player2.playerengine.tasks.misc;

import com.player2.playerengine.tasks.squashed.CataloguedResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import java.util.Arrays;
import net.minecraft.world.item.Item;

public class EquipWeaponTask extends Task {
   private final ItemTarget[] toEquip;
   private final boolean pinExplicitSlots;

   public EquipWeaponTask(ItemTarget... toEquip) {
      this(false, toEquip);
   }

   public EquipWeaponTask(boolean pinExplicitSlots, ItemTarget... toEquip) {
      this.pinExplicitSlots = pinExplicitSlots;
      this.toEquip = toEquip;
   }

   public EquipWeaponTask(Item... toEquip) {
      this(false, Arrays.stream(toEquip).map(ItemTarget::new).toArray(ItemTarget[]::new));
   }

   @Override
   protected void onStart() {
      if (this.pinExplicitSlots) {
         this.controller.getExplicitEquipPolicy().pin(this.toEquip);
      }
   }

   @Override
   protected Task onTick() {
      ItemTarget[] weaponsNotPresent = Arrays.stream(this.toEquip)
            .filter(target -> !this.controller.getItemStorage().hasItem(target.getMatches()) && !this.isWeaponEquipped(target))
            .toArray(ItemTarget[]::new);
      if (weaponsNotPresent.length > 0) {
         this.setDebugState("Obtaining weapon to equip.");
         return new CataloguedResourceTask(weaponsNotPresent);
      }
      this.setDebugState("Equipping weapon.");
      for (ItemTarget target : this.toEquip) {
         if (!this.isWeaponEquipped(target)) {
            for (Item item : target.getMatches()) {
               if (this.controller.getSlotHandler().forceEquipItem(item)) {
                  break;
               }
            }
         }
      }
      return null;
   }

   private boolean isWeaponEquipped(ItemTarget target) {
      for (Item item : target.getMatches()) {
         if (this.controller.getInventory().getMainHandStack().is(item)) {
            return true;
         }
      }
      return false;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   public boolean isFinished() {
      return Arrays.stream(this.toEquip).allMatch(this::isWeaponEquipped);
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof EquipWeaponTask task ? Arrays.equals(task.toEquip, this.toEquip) : false;
   }

   @Override
   protected String toDebugString() {
      return "Equipping weapon: " + Arrays.toString(this.toEquip);
   }
}
