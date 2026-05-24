package com.player2.playerengine.equip;

import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.multiversion.equip.EquipVer;
import com.player2.playerengine.multiversion.equip.WeaponVer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ExplicitEquipPolicy {
   private final Map<EquipmentSlot, ItemTarget> pinnedSlots = new EnumMap<>(EquipmentSlot.class);

   public void pin(ItemTarget... targets) {
      if (targets == null) {
         return;
      }
      for (ItemTarget target : targets) {
         if (target == null || target.isEmpty()) {
            continue;
         }
         for (Item item : target.getMatches()) {
            ItemStack probe = new ItemStack(item);
            if (WeaponVer.isMeleeWeapon(probe)) {
               this.pinnedSlots.put(EquipmentSlot.MAINHAND, target);
               continue;
            }
            Optional<EquipmentSlot> slot = EquipVer.getBodyArmorSlot(probe);
            slot.ifPresent(s -> this.pinnedSlots.put(s, target));
         }
      }
   }

   public void unpin(EquipmentSlot slot) {
      this.pinnedSlots.remove(slot);
   }

   public boolean isPinned(EquipmentSlot slot) {
      return this.pinnedSlots.containsKey(slot);
   }

   public boolean allowsAutoEquip(EquipmentSlot slot) {
      return !this.isPinned(slot);
   }

   public void clearIfPinnedItemGone(ItemStack[] inventoryStacks, EquipmentSlot slot) {
      ItemTarget pin = this.pinnedSlots.get(slot);
      if (pin == null) {
         return;
      }
      boolean found = false;
      for (ItemStack stack : inventoryStacks) {
         if (!stack.isEmpty() && pin.matches(stack.getItem())) {
            found = true;
            break;
         }
      }
      ItemStack equipped = ItemStack.EMPTY;
      if (!found) {
         this.pinnedSlots.remove(slot);
      }
   }

   public void clearAll() {
      this.pinnedSlots.clear();
   }
}
