package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.tasks.base.Task;
import net.minecraft.core.BlockPos;

public class GetCloseToBlockTask extends Task {
   private final BlockPos toApproach;
   private int currentRange;

   public GetCloseToBlockTask(BlockPos toApproach) {
      this.toApproach = toApproach;
   }

   @Override
   protected void onStart() {
      // OVERFLOW FIX: seed currentRange to the ACTUAL integer distance to the target (clamped >= 1),
      // not Integer.MAX_VALUE. The old MAX_VALUE seed combined with the int*int comparison in inRange()
      // overflowed to 1 (Integer.MAX_VALUE^2 mod 2^32 == 1), so inRange() was true only within 1 block,
      // currentRange never shrank, and GetWithinRangeOfBlockTask was dispatched with range=MAX_VALUE ->
      // GoalNear(pos, MAX_VALUE) whose rangeSq ALSO overflows to 1 -> a degenerate goal satisfied
      // everywhere -> baritone "Failed to make progress on goal, wandering". A finite seed yields a real
      // GoalNear baritone can path toward, and the shrink loop below tightens it as the bot approaches.
      this.currentRange = Math.max(1, this.getCurrentDistance());
   }

   @Override
   protected Task onTick() {
      if (this.inRange()) {
         this.currentRange = Math.max(1, this.getCurrentDistance() - 1);
      }

      return new GetWithinRangeOfBlockTask(this.toApproach, this.currentRange);
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   private int getCurrentDistance() {
      return (int)Math.sqrt(this.controller.getPlayer().blockPosition().distSqr(this.toApproach));
   }

   private boolean inRange() {
      // OVERFLOW FIX: distSqr returns a double; compare against currentRange squared in LONG arithmetic so
      // a large currentRange never wraps to a tiny int (the old `currentRange * currentRange` was an int
      // multiply that overflowed for large ranges, defeating the shrink logic).
      long rangeSq = (long)this.currentRange * (long)this.currentRange;
      return this.controller.getPlayer().blockPosition().distSqr(this.toApproach) <= (double)rangeSq;
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof GetCloseToBlockTask task ? task.toApproach.equals(this.toApproach) : false;
   }

   @Override
   protected String toDebugString() {
      return "Approaching " + this.toApproach.toShortString();
   }
}
