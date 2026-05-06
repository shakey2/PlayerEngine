/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.player2.playerengine.automaton.cache;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.automaton.api.BaritoneAPI;
import com.player2.playerengine.automaton.api.cache.IContainerMemory;
import com.player2.playerengine.automaton.api.cache.IRememberedInventory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;

public class ContainerMemory implements IContainerMemory {
   private final Map<BlockPos, ContainerMemory.RememberedInventory> inventories = new HashMap<>();

   public void read(HolderLookup.Provider levelRegistryAccess, CompoundTag tag) {
      try {
         ListTag nbtInventories = tag.getList("inventories", 10);

         for (int i = 0; i < nbtInventories.size(); i++) {
            CompoundTag nbtEntry = nbtInventories.getCompound(i);
            BlockPos pos = NbtUtils.readBlockPos(nbtEntry,"pos").get();
            ContainerMemory.RememberedInventory rem = new ContainerMemory.RememberedInventory();
            rem.fromNbt(levelRegistryAccess, nbtEntry.getList("content", 9));
            if (!rem.items.isEmpty()) {
               this.inventories.put(pos, rem);
            }
         }
      } catch (Exception var7) {
         PlayerEngine.LOGGER.error(var7);
         this.inventories.clear();
      }
   }

   public CompoundTag toNbt(HolderLookup.Provider levelRegistryAccess) {
      CompoundTag tag = new CompoundTag();
      if (BaritoneAPI.getGlobalSettings().containerMemory.get()) {
         ListTag list = new ListTag();

         for (Entry<BlockPos, ContainerMemory.RememberedInventory> entry : this.inventories.entrySet()) {
            CompoundTag nbtEntry = new CompoundTag();
            nbtEntry.put("pos", NbtUtils.writeBlockPos(entry.getKey()));
            nbtEntry.put("content", entry.getValue().toNbt(levelRegistryAccess));
            list.add(nbtEntry);
         }

         tag.put("inventories", list);
      }

      return tag;
   }

   public synchronized void setup(BlockPos pos, int windowId, int slotCount) {
      ContainerMemory.RememberedInventory inventory = this.inventories.computeIfAbsent(pos, x -> new ContainerMemory.RememberedInventory());
      inventory.windowId = windowId;
      inventory.size = slotCount;
   }

   public synchronized Optional<ContainerMemory.RememberedInventory> getInventoryFromWindow(int windowId) {
      return this.inventories.values().stream().filter(i -> i.windowId == windowId).findFirst();
   }

   public final synchronized ContainerMemory.RememberedInventory getInventoryByPos(BlockPos pos) {
      return this.inventories.get(pos);
   }

   @Override
   public final synchronized Map<BlockPos, IRememberedInventory> getRememberedInventories() {
      return new HashMap<>(this.inventories);
   }

   public static class RememberedInventory implements IRememberedInventory {
      private final List<ItemStack> items = new ArrayList<>();
      private int windowId;
      private int size;

      private RememberedInventory() {
      }

      @Override
      public final List<ItemStack> getContents() {
         return Collections.unmodifiableList(this.items);
      }

      @Override
      public final int getSize() {
         return this.size;
      }

      public ListTag toNbt(HolderLookup.Provider levelRegistryAccess) {
         ListTag inv = new ListTag();

         for (ItemStack item : this.items) {
            inv.add(item.save(levelRegistryAccess, new CompoundTag()));
         }

         return inv;
      }

      public void fromNbt(HolderLookup.Provider levelRegistryAccess, ListTag content) {
         for (int i = 0; i < content.size(); i++) {
            this.items.add(ItemStack.parseOptional(levelRegistryAccess, content.getCompound(i)));
         }

         this.size = this.items.size();
         this.windowId = -1;
      }
   }
}
