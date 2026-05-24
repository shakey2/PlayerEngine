package com.player2.playerengine.equip;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.multiversion.equip.WeaponStats;
import com.player2.playerengine.multiversion.equip.WeaponStatsVer;
import com.player2.playerengine.multiversion.equip.WeaponVer;
import com.player2.playerengine.player2api.Player2NpcOwnerSettingsReader;
import com.player2.playerengine.util.equip.WeaponEquipScorer;
import java.util.ArrayDeque;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class PickupWeaponEvalQueue {
   private final PlayerEngineController controller;
   private final ArrayDeque<PendingWeaponPickup> pending = new ArrayDeque<>();
   private long nextPickupId;

   public PickupWeaponEvalQueue(PlayerEngineController controller) {
      this.controller = controller;
   }

   public void enqueueFromPickup(ItemStack acquired) {
      if (acquired == null || acquired.isEmpty() || !WeaponVer.isMeleeWeapon(acquired)) {
         return;
      }
      if (!this.isAutoEquipEnabled()) {
         return;
      }
      this.pending.addLast(new PendingWeaponPickup(++this.nextPickupId));
   }

   public Optional<PendingWeaponPickup> pollNext() {
      return Optional.ofNullable(this.pending.pollFirst());
   }

   public boolean isEmpty() {
      return this.pending.isEmpty();
   }

   public int size() {
      return this.pending.size();
   }

   public static int resolveMainInventorySlot(PlayerEngineController controller) {
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();
      ItemStack held = inventory.getMainHandStack();
      int bestSlot = -1;
      WeaponStats bestStats = WeaponStats.ZERO;
      for (int i = 0; i < LivingEntityInventory.MAIN_SIZE; i++) {
         ItemStack stack = inventory.main.get(i);
         if (stack.isEmpty() || !WeaponVer.isMeleeWeapon(stack)) {
            continue;
         }
         if (!WeaponEquipScorer.isUpgrade(stack, held)) {
            continue;
         }
         WeaponStats stats = WeaponStatsVer.read(stack);
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
