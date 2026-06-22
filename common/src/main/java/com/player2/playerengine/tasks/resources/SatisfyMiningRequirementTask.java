package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.StorageHelper;
import java.util.Optional;
import net.minecraft.world.item.Items;

public class SatisfyMiningRequirementTask extends Task {
   // Bounded acquisition-failure guard (plan WS3): the catalogue craft chain
   // (getItemTask(WOODEN_PICKAXE, ...)) is the correct, already-working pickaxe acquisition path, but it
   // has no truthful terminal of its own -- if it genuinely cannot complete (no source, no crafting
   // access) the legacy gather would re-issue it every tick and the bot would spin forever, never telling
   // the model it lacks a tool. We add a minimal no-progress guard: each time the dispatched catalogue
   // child task terminates (finished or self-stopped) WITHOUT the inventory requirement being met counts
   // as one failed acquisition cycle; after a small bounded number of failed cycles we latch a terminal
   // failure and force-stop, so the parent can surface a truthful reason instead of looping. A solvable
   // acquisition meets the requirement and isFinished() returns before this guard ever trips. We replicate
   // this minimal guard rather than importing ToolAcquisitionTask (which needs MineBlockParams/AgenticRunState
   // plumbing the legacy ResourceTask stack lacks).
   //
   // CRITICAL FIX (2026-06-21): the previous implementation created a FRESH getItemTask(...) instance every
   // tick and stored it as `childTask`. The Task framework keeps the ORIGINAL running sub via isEqual()
   // (Task.tick lines 35-42) and never adopts a later fresh instance, so `childTask` pointed at a
   // never-started Task whose controller==null; the guard's childTask.isFinished() then NPE'd inside
   // StorageHelper.miningRequirementMetInventory (controller.getItemStorage() on null) and crashed the tick
   // chain. We now build the catalogue child ONCE, cache it, and keep returning the SAME instance -- so the
   // framework adopts it as this.sub, starts it (assigning controller), and we can safely observe its
   // terminal state. This mirrors ToolAcquisitionTask.climbChain()'s cached-climbChild pattern.
   private static final int MAX_FAILED_CYCLES = 3;

   private final MiningRequirement requirement;
   private Task childTask;
   private int failedCycles;
   private boolean acquisitionFailed;

   public SatisfyMiningRequirementTask(MiningRequirement requirement) {
      this.requirement = requirement;
   }

   @Override
   protected void onStart() {
      this.childTask = null;
      this.failedCycles = 0;
      this.acquisitionFailed = false;
   }

   @Override
   protected Task onTick() {
      if (this.requirement == MiningRequirement.HAND) {
         return null;
      }

      // The cached child has been ticked at least once (the framework adopted it as this.sub and assigned
      // its controller), so observing its terminal state here is safe.
      if (this.childTask != null && (this.childTask.stopped() || this.childTask.isFinished())) {
         // Child terminated. isFinished() at the top of the Task framework would already have returned us
         // if the requirement were met; reaching here with it still unmet is a failed acquisition cycle.
         if (!StorageHelper.miningRequirementMetInventory(this.controller, this.requirement)) {
            this.failedCycles++;
            this.childTask = null;
            if (this.failedCycles >= MAX_FAILED_CYCLES) {
               this.acquisitionFailed = true;
               this.setDebugState("Could not acquire a " + this.requirement + " pickaxe; giving up.");
               this.stop();
               return null;
            }
         } else {
            // Requirement met (e.g. another step deposited a pickaxe) -- drop the spent child; isFinished()
            // will finish us next.
            this.childTask = null;
         }
      }

      if (this.childTask == null) {
         this.childTask = switch (this.requirement) {
            case WOOD -> TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
            case STONE -> TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
            case IRON -> TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
            case DIAMOND -> TaskCatalogue.getItemTask(Items.DIAMOND_PICKAXE, 1);
            default -> null;
         };
      }
      return this.childTask;
   }

   @Override
   protected void onStop(Task interruptTask) {
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof SatisfyMiningRequirementTask task ? task.requirement == this.requirement : false;
   }

   @Override
   protected String toDebugString() {
      return "Satisfy Mining Req: " + this.requirement;
   }

   @Override
   public boolean isFinished() {
      return StorageHelper.miningRequirementMetInventory(this.controller, this.requirement);
   }

   /** True once the bounded acquisition guard has given up trying to obtain the required pickaxe. */
   public boolean acquisitionFailed() {
      return this.acquisitionFailed;
   }

   /**
    * Dual-audience reasons for a latched acquisition failure (plan WS3 / DESIGN.md §3), empty until the
    * guard trips. {@code a} = concise human chat line; {@code b} = stable machine token for the model
    * channel (propagated into {@link com.player2.playerengine.tasks.crafting.CraftMacroResourceTask}'s
    * failureReason, the only channel {@code @get} reads).
    */
   public Optional<String> humanFailureReason() {
      return this.acquisitionFailed
         ? Optional.of("I can't make a " + tierWord() + " pickaxe right now — I'm missing the materials.")
         : Optional.empty();
   }

   public Optional<String> machineFailureReason() {
      return this.acquisitionFailed
         ? Optional.of("could_not_acquire_tool:" + this.requirement.name().toLowerCase())
         : Optional.empty();
   }

   private String tierWord() {
      return switch (this.requirement) {
         case WOOD -> "wooden";
         case STONE -> "stone";
         case IRON -> "iron";
         case DIAMOND -> "diamond";
         default -> "suitable";
      };
   }
}
