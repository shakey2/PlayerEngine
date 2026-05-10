package com.player2.playerengine.player2api.status;

import com.player2.playerengine.PlayerEngineController;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;

public class AgentStatus extends ObjectStatus {
   public static AgentStatus fromMod(PlayerEngineController mod) {
      LivingEntity player = mod.getPlayer();
      Entity vehicle = player.getVehicle();
      boolean isOnBoat = vehicle instanceof Boat;
      String vehicleType = vehicle == null ? "none" : String.valueOf(EntityType.getKey(vehicle.getType()));
      String vehicleId = vehicle == null ? "none" : vehicle.getStringUUID();
      return (AgentStatus) new AgentStatus()
            .add("position", StatusUtils.getCurrentPosition(mod))
            .add("health", String.format("%.2f/20", player.getHealth()))
            .add("food",
                  String.format("%.2f/20", (float) mod.getBaritone().getEntityContext().hungerManager().getFoodLevel()))
            .add("saturation",
                  String.format("%.2f/20", mod.getBaritone().getEntityContext().hungerManager().getSaturationLevel()))
            .add("inventory", StatusUtils.getInventoryString(mod))
            .add("taskStatus", StatusUtils.getTaskStatusString(mod))
            .add("oxygenLevel", StatusUtils.getOxygenString(mod))
            .add("armor", StatusUtils.getEquippedArmorStatusString(mod))
            .add("gamemode", StatusUtils.getGamemodeString(mod))
            .add("isOnBoat", String.valueOf(isOnBoat))
            .add("vehicleType", vehicleType)
            .add("vehicleId", vehicleId);
      // .add("taskTree", StatusUtils.getTaskTree(mod));
   }
}
