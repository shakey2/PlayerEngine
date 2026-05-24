package com.player2.playerengine.equip;

import net.minecraft.world.entity.EquipmentSlot;

/** One pickup event for a body armor slot; main inventory index is resolved when processed. */
public record PendingArmorPickup(EquipmentSlot armorSlot) {
}
