package com.player2.playerengine.tasks;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;

/**
 * Keeps the bot oriented toward a block until interrupted (Part C0 table look).
 */
public class LookAtBlockTask extends Task {
   private final BlockPos pos;
   private final boolean requireBlockPresent;

   public LookAtBlockTask(BlockPos pos, boolean requireBlockPresent) {
      this.pos = pos;
      this.requireBlockPresent = requireBlockPresent;
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      if (this.pos == null) {
         return null;
      }
      if (this.requireBlockPresent && this.controller.getWorld().getBlockState(this.pos).isAir()) {
         return null;
      }
      LookHelper.lookAt(this.controller, this.pos.getCenter());
      return null;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   public boolean isFinished() {
      return false;
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof LookAtBlockTask task
            && this.pos.equals(task.pos)
            && this.requireBlockPresent == task.requireBlockPresent;
   }

   @Override
   protected String toDebugString() {
      return "LookAtBlockTask(" + this.pos.toShortString() + ")";
   }
}
