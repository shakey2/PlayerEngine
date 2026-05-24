package com.player2.playerengine.control;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.slots.PlayerSlot;
import com.player2.playerengine.util.slots.Slot;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.multiversion.equip.EquipVer;
import com.player2.playerengine.multiversion.equip.WeaponVer;
import com.player2.playerengine.util.equip.ArmorEquipScorer;
import com.player2.playerengine.util.equip.WeaponEquipScorer;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EmptyMapItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.entity.LivingEntity;

public class SlotHandler {
   private final PlayerEngineController controller;
   private ItemStack cursorStack = ItemStack.EMPTY;

   public SlotHandler(PlayerEngineController controller) {
      this.controller = controller;
   }

   public ItemStack getCursorStack() {
      return this.cursorStack;
   }

   public void setCursorStack(ItemStack stack) {
      this.cursorStack = stack != null && !stack.isEmpty() ? stack : ItemStack.EMPTY;
   }

   public boolean canDoSlotAction() {
      return true;
   }

   public void registerSlotAction() {
      this.controller.getItemStorage().registerSlotAction();
   }

   public void clickSlot(Slot slot, int mouseButton, ClickType type) {
      if (slot != null && !slot.equals(Slot.UNDEFINED)) {
         NonNullList<ItemStack> inventory = slot.getInventory();
         int index = slot.getIndex();
         if (inventory == null) {
            Debug.logWarning("Attempt to click a slot without an inventory: " + slot);
         } else {
            ItemStack slotStack = (ItemStack) inventory.get(index);
            switch (type) {
               case PICKUP:
                  ItemStack temp = this.cursorStack;
                  this.setCursorStack(slotStack);
                  inventory.set(index, temp);
                  break;
               case QUICK_MOVE:
                  Debug.logError("QUICK_MOVE is NYI.");
                  break;
               default:
                  Debug.logWarning("Unsupported SlotActionType: " + type);
            }

            this.registerSlotAction();
         }
      } else {
         if (!this.cursorStack.isEmpty()) {
            this.controller.getEntity().spawnAtLocation(this.cursorStack.copy());
            this.setCursorStack(ItemStack.EMPTY);
            this.registerSlotAction();
         }
      }
   }

   public void forceEquipItemToOffhand(Item toEquip) {
      LivingEntity entity = this.controller.getEntity();
      ItemStack offhand = entity.getItemBySlot(EquipmentSlot.OFFHAND);

      LivingEntityInventory inv = ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();
      for (int i = 0; i < inv.main.size(); i++) {
         ItemStack potential = inv.main.get(i);
         if (!potential.isEmpty() && potential.is(toEquip)) {
            inv.main.set(i, offhand);
            entity.setItemSlot(EquipmentSlot.OFFHAND, potential);

            this.registerSlotAction();
            return;
         }
      }
   }

   public boolean forceEquipItem(Item[] toEquip) {
      LivingEntityInventory inventory = ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();
      if (Arrays.stream(toEquip).allMatch(ix -> ix == inventory.getMainHandStack().getItem())) {
         return true;
      } else {
         for (int i = 0; i < 9; i++) {
            int finalI = i;
            if (Arrays.stream(toEquip).allMatch(it -> it == inventory.getItem(finalI).getItem())) {
               inventory.selectedSlot = i;
               this.registerSlotAction();
               return true;
            }
         }

         for (int ix = 9; ix < inventory.main.size(); ix++) {
            int finalI = ix;
            if (Arrays.stream(toEquip).allMatch(it -> it == inventory.getItem(finalI).getItem())) {
               ItemStack handStack = inventory.getMainHandStack();
               inventory.setItem(inventory.selectedSlot, inventory.getItem(ix));
               inventory.setItem(ix, handStack);
               this.registerSlotAction();
               return true;
            }
         }

         return false;
      }
   }

   public boolean forceEquipItem(Item toEquip) {
      LivingEntityInventory inventory = ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();
      if (inventory.getMainHandStack().is(toEquip)) {
         return true;
      } else {
         for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(toEquip)) {
               inventory.selectedSlot = i;
               this.registerSlotAction();
               return true;
            }
         }

         for (int ix = 9; ix < inventory.main.size(); ix++) {
            if (inventory.getItem(ix).is(toEquip)) {
               ItemStack handStack = inventory.getMainHandStack();
               inventory.setItem(inventory.selectedSlot, inventory.getItem(ix));
               inventory.setItem(ix, handStack);
               this.registerSlotAction();
               return true;
            }
         }

         return false;
      }
   }

   public boolean forceDeequip(Predicate<ItemStack> isBad) {
      LivingEntityInventory inventory = ((IInventoryProvider) this.controller.getEntity()).getLivingInventory();
      ItemStack equip = inventory.getMainHandStack();
      if (isBad.test(equip)) {
         int emptySlot = inventory.getEmptySlot();
         if (emptySlot != -1) {
            if (LivingEntityInventory.isValidHotbarIndex(emptySlot)) {
               inventory.selectedSlot = emptySlot;
            } else {
               inventory.setItem(emptySlot, equip);
               inventory.setItem(inventory.selectedSlot, ItemStack.EMPTY);
            }

            this.registerSlotAction();
            return true;
         } else {
            return false;
         }
      } else {
         return true;
      }
   }

   public boolean forceDeequipHitTool() {
      return this.forceDeequip(stack -> stack.getItem() instanceof TieredItem);
   }

   public boolean forceEquipItem(ItemTarget toEquip, boolean unInterruptable) {
      if (toEquip != null && !toEquip.isEmpty()) {
         if (this.controller.getFoodChain().needsToEat() && !unInterruptable) {
            return false;
         } else {
            for (Item item : toEquip.getMatches()) {
               if (this.forceEquipItem(item)) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return this.forceDeequip(stack -> !stack.isEmpty());
      }
   }

   public void refreshInventory() {
   }

   public void forceDeequipRightClickableItem() {
      this.forceDeequip(
            stack -> {
               Item item = stack.getItem();
               return item instanceof BucketItem
                     || item instanceof EnderEyeItem
                     || item == Items.BOW
                     || item == Items.CROSSBOW
                     || item == Items.FLINT_AND_STEEL
                     || item == Items.FIRE_CHARGE
                     || item == Items.ENDER_PEARL
                     || item instanceof FireworkRocketItem
                     || item instanceof SpawnEggItem
                     || item == Items.END_CRYSTAL
                     || item == Items.EXPERIENCE_BOTTLE
                     || item instanceof PotionItem
                     || item == Items.TRIDENT
                     || item == Items.WRITABLE_BOOK
                     || item == Items.WRITTEN_BOOK
                     || item instanceof FishingRodItem
                     || item instanceof FoodOnAStickItem
                     || item == Items.COMPASS
                     || item instanceof EmptyMapItem
                     || item instanceof ArmorItem
                     || item == Items.LEAD
                     || item == Items.SHIELD;
            });
   }

   private void swapSlots(Slot slot, Slot target) {
      ItemStack stack = slot.getStack();
      ItemStack targetStack = target.getStack();
      target.getInventory().set(target.getIndex(), stack);
      slot.getInventory().set(slot.getIndex(), targetStack);
   }

   public void forceEquipSlot(PlayerEngineController controller, Slot slot) {
      Slot target = PlayerSlot.getEquipSlot(controller.getInventory());
      this.swapSlots(slot, target);
   }

   public boolean equipArmorFromMainSlot(PlayerEngineController controller, int mainSlot, EquipmentSlot armorSlot) {
      if (mainSlot < 0 || mainSlot >= LivingEntityInventory.MAIN_SIZE) {
         return false;
      }
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();
      ItemStack candidate = inventory.main.get(mainSlot);
      if (candidate.isEmpty()) {
         return false;
      }
      Optional<EquipmentSlot> slotType = EquipVer.getBodyArmorSlot(candidate);
      if (slotType.isEmpty() || slotType.get() != armorSlot) {
         return false;
      }
      ItemStack equipped = controller.getEntity().getItemBySlot(armorSlot);
      if (!ArmorEquipScorer.isUpgrade(candidate, equipped, armorSlot)) {
         return false;
      }
      ItemStack currentlyEquipped = equipped.copy();
      ItemStack candidateCopy = candidate.copy();
      inventory.main.set(mainSlot, ItemStack.EMPTY);
      if (!currentlyEquipped.isEmpty()) {
         int displacedDest = findMainSlotForDisplacedArmor(inventory, mainSlot, inventory.selectedSlot);
         if (displacedDest >= 0) {
            inventory.main.set(displacedDest, currentlyEquipped);
         } else {
            controller.getEntity().spawnAtLocation(currentlyEquipped, 0.5F);
         }
      }
      controller.getEntity().setItemSlot(armorSlot, candidateCopy);
      this.registerSlotAction();
      return true;
   }

   private static int findMainSlotForDisplacedArmor(LivingEntityInventory inventory, int sourceSlot, int selectedSlot) {
      for (int i = 0; i < LivingEntityInventory.MAIN_SIZE; i++) {
         if (i != sourceSlot && i != selectedSlot && inventory.main.get(i).isEmpty()) {
            return i;
         }
      }
      for (int i = 0; i < LivingEntityInventory.MAIN_SIZE; i++) {
         if (i != sourceSlot && inventory.main.get(i).isEmpty()) {
            return i;
         }
      }
      return -1;
   }

   public boolean equipWeaponToMainHand(PlayerEngineController controller, int mainSlot) {
      if (mainSlot < 0 || mainSlot >= LivingEntityInventory.MAIN_SIZE) {
         return false;
      }
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();
      ItemStack candidate = inventory.main.get(mainSlot);
      if (!WeaponVer.isMeleeWeapon(candidate)) {
         return false;
      }
      ItemStack held = inventory.getMainHandStack();
      if (!WeaponEquipScorer.isUpgrade(candidate, held)) {
         return false;
      }
      if (mainSlot == inventory.selectedSlot) {
         controller.getEntity().setItemSlot(EquipmentSlot.MAINHAND, candidate.copy());
         this.registerSlotAction();
         return true;
      }
      if (LivingEntityInventory.isValidHotbarIndex(mainSlot)) {
         inventory.selectedSlot = mainSlot;
         controller.getEntity().setItemSlot(EquipmentSlot.MAINHAND, inventory.getMainHandStack());
         this.registerSlotAction();
         return true;
      }
      ItemStack handStack = inventory.getMainHandStack();
      inventory.main.set(inventory.selectedSlot, inventory.main.get(mainSlot));
      inventory.main.set(mainSlot, handStack);
      controller.getEntity().setItemSlot(EquipmentSlot.MAINHAND, inventory.getMainHandStack());
      this.registerSlotAction();
      return true;
   }

   public void forceEquipArmor(PlayerEngineController controller, ItemTarget target) {
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();

      for (Item item : target.getMatches()) {
         for (int i = 0; i < LivingEntityInventory.MAIN_SIZE; i++) {
            ItemStack stackInSlot = inventory.getItem(i);
            if (stackInSlot.isEmpty() || !stackInSlot.is(item)) {
               continue;
            }
            Optional<EquipmentSlot> slotType = EquipVer.getBodyArmorSlot(stackInSlot);
            if (slotType.isEmpty()) {
               continue;
            }
            EquipmentSlot slot = slotType.get();
            if (!controller.getEntity().getItemBySlot(slot).is(item)) {
               ItemStack currentlyEquipped = controller.getEntity().getItemBySlot(slot).copy();
               ItemStack toEquip = stackInSlot.copy();
               inventory.setItem(i, currentlyEquipped);
               controller.getEntity().setItemSlot(slot, toEquip);
               this.registerSlotAction();
            }
            break;
         }
      }
   }
}
