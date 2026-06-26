package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.movement.DefaultGoToDimensionTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.Dimension;
import com.player2.playerengine.util.helpers.WorldHelper;
import net.minecraft.world.item.Items;

/**
 * <b>RETIRED from the legacy smelt path.</b> The furnace/smoker smelt tasks no longer use this task:
 * fuel unit math and fuel-type selection (charcoal/coal/planks/logs, with the held-pickaxe-gated coal
 * spiral guard) now go through {@link com.player2.playerengine.tasks.cooking.FuelPlanner}'s
 * {@code plan}/{@code deficitFor}, which speaks raw cook-ticks and never re-divides by 8. This class is
 * kept only for any OTHER callers; do not re-wire the smelt tasks back to it (it only ever gathers coal
 * and conflated coal-count with smelt-operations, which was the root cause of the "smelts only 8" bug).
 */
public class CollectFuelTask extends Task {
   private final double targetFuel;

   public CollectFuelTask(double targetFuel) {
      this.targetFuel = targetFuel;
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      switch (WorldHelper.getCurrentDimension(this.controller)) {
         case OVERWORLD:
            this.setDebugState("Collecting coal.");
            return TaskCatalogue.getItemTask(Items.COAL, (int)Math.ceil(this.targetFuel / 8.0));
         case END:
            this.setDebugState("Going to overworld, since, well, no more fuel can be found here.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
         case NETHER:
            this.setDebugState("Going to overworld, since we COULD use wood but wood confuses the bot. A bug at the moment.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
         default:
            this.setDebugState("INVALID DIMENSION: " + WorldHelper.getCurrentDimension(this.controller));
            return null;
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof CollectFuelTask task ? Math.abs(task.targetFuel - this.targetFuel) < 0.01 : false;
   }

   @Override
   public boolean isFinished() {
      return this.controller.getItemStorage().getItemCountInventoryOnly(Items.COAL) >= this.targetFuel;
   }

   @Override
   protected String toDebugString() {
      return "Collect Fuel: x" + this.targetFuel;
   }
}
