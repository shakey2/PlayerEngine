package com.player2.playerengine.equip;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.multiversion.equip.ArmorStats;
import com.player2.playerengine.multiversion.equip.ArmorStatsVer;
import com.player2.playerengine.multiversion.equip.EquipVer;
import com.player2.playerengine.player2api.Player2NpcOwnerSettingsReader;
import com.player2.playerengine.util.equip.ArmorEquipScorer;
import java.util.ArrayDeque;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class PickupArmorEvalQueue {
   private final PlayerEngineController controller;
   private final ArrayDeque<PendingArmorPickup> pending = new ArrayDeque<>();

   public PickupArmorEvalQueue(PlayerEngineController controller) {
      this.controller = controller;
   }

   public void enqueueFromPickup(ItemStack acquired) {
      if (acquired == null || acquired.isEmpty() || !EquipVer.isBodyArmor(acquired)) {
         return;
      }
      if (!this.isAutoEquipEnabled()) {
         return;
      }
      Optional<EquipmentSlot> armorSlot = EquipVer.getBodyArmorSlot(acquired);
      if (armorSlot.isEmpty()) {
         return;
      }
      EquipmentSlot slot = armorSlot.get();
      this.pending.removeIf(p -> p.armorSlot() == slot);
      this.pending.addLast(new PendingArmorPickup(slot));
   }

   public Optional<PendingArmorPickup> pollNext() {
      return Optional.ofNullable(this.pending.pollFirst());
   }

   public boolean isEmpty() {
      return this.pending.isEmpty();
   }

   public int size() {
      return this.pending.size();
   }

   public static int resolveMainInventorySlot(PlayerEngineController controller, EquipmentSlot armorSlot) {
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();
      LivingEntity entity = controller.getEntity();
      ItemStack equipped = entity.getItemBySlot(armorSlot);
      int bestSlot = -1;
      ArmorStats bestStats = ArmorStats.ZERO;
      for (int i = 0; i < LivingEntityInventory.MAIN_SIZE; i++) {
         ItemStack stack = inventory.main.get(i);
         if (stack.isEmpty() || !EquipVer.isBodyArmor(stack)) {
            continue;
         }
         Optional<EquipmentSlot> stackSlot = EquipVer.getBodyArmorSlot(stack);
         if (stackSlot.isEmpty() || stackSlot.get() != armorSlot) {
            continue;
         }
         if (!ArmorEquipScorer.isUpgrade(stack, equipped, armorSlot)) {
            continue;
         }
         ArmorStats stats = ArmorStatsVer.read(stack, armorSlot);
         if (bestSlot < 0 || stats.isBetterThan(bestStats)) {
            bestSlot = i;
            bestStats = stats;
         }
      }
      return bestSlot;
   }

   private boolean isAutoEquipEnabled() {
      Player owner = this.controller.getOwner();
      if (owner == null) {
         return true;
      }
      MinecraftServer server = this.controller.getWorld().getServer();
      if (server == null) {
         return true;
      }
      return Player2NpcOwnerSettingsReader.isAutoEquipEnabled(server, owner.getUUID());
   }
}
