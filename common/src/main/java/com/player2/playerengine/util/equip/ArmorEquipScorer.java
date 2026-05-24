package com.player2.playerengine.util.equip;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.multiversion.equip.ArmorStats;
import com.player2.playerengine.multiversion.equip.ArmorStatsVer;
import com.player2.playerengine.multiversion.equip.EquipVer;
import java.util.Optional;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class ArmorEquipScorer {
   private static final EquipmentSlot[] BODY_SLOTS = new EquipmentSlot[] {
         EquipmentSlot.HEAD,
         EquipmentSlot.CHEST,
         EquipmentSlot.LEGS,
         EquipmentSlot.FEET
   };

   private ArmorEquipScorer() {
   }

   public static EquipmentSlot[] bodySlots() {
      return BODY_SLOTS;
   }

   public static boolean isUpgrade(ItemStack candidate, ItemStack currentlyEquipped, EquipmentSlot slot) {
      if (candidate.isEmpty()) {
         return false;
      }
      if (currentlyEquipped.isEmpty()) {
         return EquipVer.isBodyArmor(candidate);
      }
      ArmorStats cand = ArmorStatsVer.read(candidate, slot);
      ArmorStats cur = ArmorStatsVer.read(currentlyEquipped, slot);
      return cand.isBetterThan(cur);
   }

   public static Optional<ItemStack> bestForSlot(PlayerEngineController controller, EquipmentSlot slot) {
      LivingEntityInventory inventory = ((IInventoryProvider) controller.getEntity()).getLivingInventory();
      ItemStack best = ItemStack.EMPTY;
      ArmorStats bestStats = ArmorStats.ZERO;
      for (int i = 0; i < inventory.getContainerSize(); i++) {
         ItemStack stack = inventory.getItem(i);
         if (stack.isEmpty()) {
            continue;
         }
         Optional<EquipmentSlot> stackSlot = EquipVer.getBodyArmorSlot(stack);
         if (stackSlot.isEmpty() || stackSlot.get() != slot) {
            continue;
         }
         ArmorStats stats = ArmorStatsVer.read(stack, slot);
         if (best.isEmpty() || stats.isBetterThan(bestStats)) {
            best = stack;
            bestStats = stats;
         }
      }
      return best.isEmpty() ? Optional.empty() : Optional.of(best);
   }
}
