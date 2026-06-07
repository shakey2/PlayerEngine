package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.PlaceBlockNearbyTask;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.util.helpers.LookHelper;
import com.player2.playerengine.util.helpers.MaterialAvailability;
import com.player2.playerengine.util.time.TimerGame;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class CraftMacroResourceTask extends ResourceTask implements DescribesProgress {
   private CraftMacroPlan plan;
   private CraftMacroPhase phase = CraftMacroPhase.PLAN;
   private BlockPos craftingTablePos;
   private boolean tablePlacedByTask;
   private final TimerGame craftDelay = new TimerGame(0.5);
   private final TimerGame lookHold = new TimerGame(0.25);
   private boolean delayActive;
   private int externalMaterialIndex;
   private int inventoryStepIndex;
   private int tableCraftsRemaining;
   private String failureReason;

   public CraftMacroResourceTask(CraftMacroPlan initialPlan) {
      super(initialPlan.requestedOutput());
      this.plan = initialPlan;
   }

   @Override
   public boolean isFinished() {
      return this.controller != null
         && this.phase == CraftMacroPhase.DONE
         && this.controller.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount();
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController mod) {
      return false;
   }

   @Override
   protected void onResourceStart(PlayerEngineController mod) {
      this.phase = CraftMacroPhase.PLAN;
      this.replan(mod);
   }

   @Override
   protected Task onResourceTick(PlayerEngineController mod) {
      if (this.failureReason != null) {
         this.setDebugState(this.failureReason);
         return null;
      }

      if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
         this.phase = CraftMacroPhase.DONE;
         return null;
      }

      switch (this.phase) {
         case PLAN -> {
            this.replan(mod);
            if (this.plan.isEmpty()) {
               this.phase = CraftMacroPhase.DONE;
               return null;
            }
            if (this.plan.externalMaterials().isEmpty()) {
               this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
               this.inventoryStepIndex = 0;
            } else {
               this.enterCollectPhase(mod);
            }
         }
         case COLLECT_MISSING_MATERIALS -> {
            Task collect = this.tickCollectMissingMaterials(mod);
            if (collect != null) {
               return collect;
            }
         }
         case CRAFT_2X2_INTERMEDIATES -> {
            if (!this.plan.externalMaterials().isEmpty() && !this.externalMaterialsMet(mod)) {
               this.enterCollectPhase(mod);
               return null;
            }
            Task t = this.runNextInventoryStep(mod);
            if (t != null) {
               return t;
            }
            if (!this.isInventoryPhaseComplete(mod)) {
               return null;
            }
            if (this.plan.requiresCraftingTable() && !this.craftMacroTableReachable(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
            } else if (this.plan.finalTableRecipe() != null) {
               this.syncCraftingTablePos(mod);
               this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
               this.phase = this.craftMacroTableReachable(mod)
                     ? CraftMacroPhase.MOVE_TO_TABLE
                     : CraftMacroPhase.FIND_OR_PLACE_TABLE;
            } else {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
            }
         }
         case FIND_OR_PLACE_TABLE -> {
            this.syncCraftingTablePos(mod);
            if (this.craftMacroTableReachable(mod)) {
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
               this.setDebugState("Placing crafting table");
               this.tablePlacedByTask = true;
               return new PlaceBlockNearbyTask(Blocks.CRAFTING_TABLE);
            }
            if (this.tryResumeInventoryCraftForTable(mod)) {
               return null;
            }
            this.beginIngredientRecovery(mod);
         }
         case MOVE_TO_TABLE -> {
            if (!this.ensureCraftingTablePos(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
               return null;
            }
            if (!CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
               this.setDebugState("Moving to table " + this.craftingTablePos.toShortString());
               return new GetCloseToBlockTask(this.craftingTablePos);
            }
            this.lookHold.setInterval(mod.getModSettings().getCraftTableLookHoldSeconds());
            this.lookHold.reset();
            this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
            this.phase = CraftMacroPhase.LOOK_AT_TABLE;
         }
         case LOOK_AT_TABLE -> {
            this.lookAtTable(mod);
            if (this.lookHold.elapsed()) {
               this.phase = CraftMacroPhase.CRAFT_3X3_OUTPUT;
               this.delayActive = false;
            }
         }
         case CRAFT_3X3_OUTPUT -> {
            if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            RecipeTarget tableRecipe = this.plan.finalTableRecipe();
            if (tableRecipe == null) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            if (!this.ensureCraftingTablePos(mod)) {
               this.phase = CraftMacroPhase.FIND_OR_PLACE_TABLE;
               return null;
            }
            if (!CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
               this.phase = CraftMacroPhase.MOVE_TO_TABLE;
               return null;
            }
            if (!CraftingInventoryOps.hasMaterials(mod, tableRecipe)) {
               this.setDebugState("Missing table-craft ingredients; recovering");
               this.beginIngredientRecovery(mod);
               return null;
            }
            if (this.tableCraftsRemaining <= 0) {
               this.tableCraftsRemaining = this.tableCraftsStillNeeded(mod);
            }
            if (this.tableCraftsRemaining <= 0) {
               this.phase = CraftMacroPhase.VERIFY_OUTPUT;
               return null;
            }
            this.setDebugState("Table crafting " + this.plan.outputItem().getDescription().getString());
            if (!this.delayActive) {
               this.lookAtTable(mod);
               this.craftDelay.setInterval(mod.getModSettings().getCraftDelaySeconds());
               this.craftDelay.reset();
               this.delayActive = true;
               return null;
            }
            if (!this.craftDelay.elapsed()) {
               this.lookAtTable(mod);
               return null;
            }
            this.delayActive = false;
            this.lookAtTable(mod);
            RecipeTarget single = new RecipeTarget(
                  tableRecipe.getOutputItem(), tableRecipe.getRecipe().outputCount(), tableRecipe.getRecipe());
            if (CraftingInventoryOps.performSingleCraft(mod, single)) {
               this.tableCraftsRemaining--;
            } else {
               this.beginIngredientRecovery(mod);
            }
         }
         case VERIFY_OUTPUT -> {
            if (mod.getItemStorage().getItemCount(this.plan.outputItem()) >= this.plan.targetCount()) {
               this.phase = CraftMacroPhase.DONE;
            } else {
               this.resumeAfterIncompleteOutput(mod);
            }
         }
         case DONE -> {
            return null;
         }
      }
      return null;
   }

   private void enterCollectPhase(PlayerEngineController mod) {
      this.phase = CraftMacroPhase.COLLECT_MISSING_MATERIALS;
      this.externalMaterialIndex = this.findFirstUnmetExternalIndex(mod);
   }

   /**
    * Runs one external gather subtask until counts are met. The index only advances when the target is satisfied
    * (never call Iterator.next() each tick — that cancelled mining after one tick).
    */
   private Task tickCollectMissingMaterials(PlayerEngineController mod) {
      List<ItemTarget> externals = this.plan.externalMaterials();
      while (this.externalMaterialIndex < externals.size()) {
         ItemTarget target = externals.get(this.externalMaterialIndex);
         if (this.isExternalTargetMet(mod, target)) {
            this.externalMaterialIndex++;
            continue;
         }
         this.setDebugState("Collecting " + target);
         ResourceTask gather = TaskCatalogue.getItemTask(target);
         return gather != null ? gather : this.advanceAfterCollectFail(mod);
      }
      if (!this.externalMaterialsMet(mod)) {
         // Defect A recovery: the sufficiency axis ({@link #isExternalTargetMet}) may have declared a
         // material "met" by counting reachable-but-uncollectable nearby ground drops, so no gather was
         // issued, yet the strict inventory-only {@link #externalMaterialsMet} still reports a shortfall.
         // Historically this set a permanent failureReason and the macro span forever (never DONE, never
         // self-stopped), wedging {@link ResolveStorageChestTask} on an inert child. Instead: (a) if the
         // output is already craftable from current inventory, proceed to craft; (b) otherwise terminate
         // the macro properly so the parent's obtain-attempt cap / timeout engages.
         if (this.outputCraftableFromInventory(mod)) {
            this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
            this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
            return null;
         }
         this.terminateMacro("Failed to collect required materials for craft macro");
         return null;
      }
      this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
      this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
      return null;
   }

   /**
    * True when the macro's final output can be crafted right now from items already in inventory
    * (pooled, so the chest plan counts mixed planks/logs via {@link ItemHelper#PLANKS}/{@link ItemHelper#LOG}).
    * Used to rescue a transient collect-shortfall instead of wedging on a permanent failure.
    */
   private boolean outputCraftableFromInventory(PlayerEngineController mod) {
      if (this.plan.outputItem() == Items.CHEST) {
         int chestsNeeded = this.plan.targetCount() - mod.getItemStorage().getItemCount(Items.CHEST);
         if (chestsNeeded <= 0) {
            return true;
         }
         int planksHave = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
         int logsHave = mod.getItemStorage().getItemCount(ItemHelper.LOG);
         return planksHave + logsHave * 4 >= 8 * chestsNeeded;
      }
      return false;
   }

   /**
    * Terminate the macro WITHOUT wedging: record the reason, mark the phase DONE, and self-stop so the
    * task becomes {@code stopped()}. The parent {@link ResolveStorageChestTask} then drops the inert
    * child and its bounded obtain-attempt cap / absolute timeout engages, instead of re-returning a
    * never-finishing child forever.
    */
   private void terminateMacro(String reason) {
      this.failureReason = reason;
      this.setDebugState(reason);
      this.phase = CraftMacroPhase.DONE;
      this.stop();
   }

   private int findFirstUnmetExternalIndex(PlayerEngineController mod) {
      List<ItemTarget> externals = this.plan.externalMaterials();
      for (int i = 0; i < externals.size(); i++) {
         if (!this.isExternalTargetMet(mod, externals.get(i))) {
            return i;
         }
      }
      return externals.size();
   }

   /**
    * Sufficiency-axis "do we already have enough of this external material?" gate (plan WS3,
    * decision 10). Routes through {@link MaterialAvailability#targetsMetSufficiency} so a freshly
    * collected/just-mined drop on the ground counts toward "met" (inventory + reachable nearby ground
    * drops) and the macro does not over-collect. Mineable blocks and the EllieGPS term are NEVER
    * counted here (they only steer source-routing). This is the PRIVATE, correctly-scoped gate; the
    * shared base {@link ResourceTask#isFinished()} stays on the strict inventory-only count.
    */
   private boolean isExternalTargetMet(PlayerEngineController mod, ItemTarget target) {
      boolean strict = mod.getItemStorage().getItemCount(target.getMatches()) >= target.getTargetCount();
      Vec3 origin = mod.getPlayer().position();
      double dropRadius = mod.getModSettings().getAggregateCountDropRadius();
      boolean sufficiency = MaterialAvailability.targetsMetSufficiency(mod, origin, dropRadius, target);
      if (sufficiency && !strict) {
         MaterialAvailability.Breakdown breakdown = MaterialAvailability.count(
            mod, target, origin, dropRadius, mod.getModSettings().getAggregateLocalSourceBlockRadius());
         Debug.logInternal(
            "CraftMacroResourceTask: external target met via sufficiency axis (decision flip) for "
               + target + " required=" + target.getTargetCount()
               + " inventory=" + breakdown.inventory()
               + " nearbyDrops=" + breakdown.nearbyDrops()
               + " sufficiencyCount=" + breakdown.sufficiencyCount());
      }
      return sufficiency;
   }

   /** Replan and resume collection or 2x2 crafts — never re-craft a table when one is already in the world. */
   private void beginIngredientRecovery(PlayerEngineController mod) {
      this.replan(mod);
      this.delayActive = false;
      if (!this.plan.externalMaterials().isEmpty() && !this.externalMaterialsMet(mod)) {
         this.enterCollectPhase(mod);
         return;
      }
      this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
      if (this.inventoryStepIndex < this.inventorySteps(mod).size()) {
         this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
         return;
      }
      if (!this.plan.externalMaterials().isEmpty()) {
         this.enterCollectPhase(mod);
         return;
      }
      this.terminateMacro("Cannot gather ingredients for " + this.plan.outputItem().getDescription().getString());
   }

   private void syncCraftingTablePos(PlayerEngineController mod) {
      if (this.craftingTablePos != null && mod.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE)) {
         return;
      }
      CraftingTableLocator.findReachable(mod, this.craftingTablePos).ifPresent(t -> {
         this.craftingTablePos = t.pos();
         this.tablePlacedByTask = t.placedByThisTask();
      });
   }

   private boolean ensureCraftingTablePos(PlayerEngineController mod) {
      this.syncCraftingTablePos(mod);
      return this.craftingTablePos != null && mod.getWorld().getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE);
   }

   private void lookAtTable(PlayerEngineController mod) {
      if (this.craftingTablePos != null) {
         LookHelper.lookAt(mod, this.craftingTablePos.getCenter());
      }
   }

   private int tableCraftsStillNeeded(PlayerEngineController mod) {
      RecipeTarget tableRecipe = this.plan.finalTableRecipe();
      if (tableRecipe == null) {
         return 0;
      }
      int outHave = mod.getItemStorage().getItemCount(this.plan.outputItem());
      int outNeed = this.plan.targetCount() - outHave;
      if (outNeed <= 0) {
         return 0;
      }
      return (int)Math.ceil((double)outNeed / tableRecipe.getRecipe().outputCount());
   }

   private void resumeAfterIncompleteOutput(PlayerEngineController mod) {
      this.beginIngredientRecovery(mod);
   }

   private boolean externalMaterialsMet(PlayerEngineController mod) {
      for (ItemTarget target : this.plan.externalMaterials()) {
         if (mod.getItemStorage().getItemCount(target.getMatches()) < target.getTargetCount()) {
            return false;
         }
      }
      return true;
   }

   private List<CraftMacroStep> inventorySteps(PlayerEngineController mod) {
      return this.plan.steps().stream()
            .filter(s -> s.kind() != CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE)
            .filter(s -> !this.shouldSkipInventoryStep(mod, s))
            .toList();
   }

   private int findFirstOpenInventoryStep(PlayerEngineController mod) {
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      for (int i = 0; i < steps.size(); i++) {
         if (!this.inventoryStepSatisfied(mod, steps.get(i))) {
            return i;
         }
      }
      return steps.size();
   }

   private boolean needsMoreInventorySteps(PlayerEngineController mod) {
      return this.findFirstOpenInventoryStep(mod) < this.inventorySteps(mod).size();
   }

   /** False while a craft delay is running or 2x2 steps remain (null from runNext is not "done"). */
   private boolean isInventoryPhaseComplete(PlayerEngineController mod) {
      if (this.delayActive) {
         return false;
      }
      return this.inventoryStepIndex >= this.inventorySteps(mod).size();
   }

   /** FIND_OR_PLACE with no table item: craft one in inventory before recovery/replan. */
   private boolean tryResumeInventoryCraftForTable(PlayerEngineController mod) {
      if (!this.plan.requiresCraftingTable() || this.craftMacroTableReachable(mod)) {
         return false;
      }
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      for (int i = 0; i < steps.size(); i++) {
         CraftMacroStep step = steps.get(i);
         if (step.kind() == CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY
               && !this.inventoryStepSatisfied(mod, step)) {
            this.setDebugState("Crafting " + step.debugLabel() + " before placement");
            this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
            this.inventoryStepIndex = i;
            this.delayActive = false;
            return true;
         }
      }
      return false;
   }

   private boolean craftMacroTableReachable(PlayerEngineController mod) {
      if (this.craftingTablePos != null && CraftingTableLocator.isReachable(mod, this.craftingTablePos)) {
         return true;
      }
      return CraftingTableLocator.findReachable(mod, this.craftingTablePos).isPresent();
   }

   private boolean shouldSkipInventoryStep(PlayerEngineController mod, CraftMacroStep step) {
      return step.kind() == CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY && this.craftMacroTableReachable(mod);
   }

   private Task advanceAfterCollectFail(PlayerEngineController mod) {
      if (this.outputCraftableFromInventory(mod)) {
         this.phase = CraftMacroPhase.CRAFT_2X2_INTERMEDIATES;
         this.inventoryStepIndex = this.findFirstOpenInventoryStep(mod);
         return null;
      }
      this.terminateMacro("Failed to collect materials for craft macro");
      return null;
   }

   private Task runNextInventoryStep(PlayerEngineController mod) {
      List<CraftMacroStep> steps = this.inventorySteps(mod);
      while (this.inventoryStepIndex < steps.size()) {
         CraftMacroStep step = steps.get(this.inventoryStepIndex);
         if (this.inventoryStepSatisfied(mod, step)) {
            this.inventoryStepIndex++;
            this.delayActive = false;
            continue;
         }
         this.setDebugState("Crafting " + step.debugLabel());
         if (!this.delayActive) {
            this.craftDelay.setInterval(mod.getModSettings().getCraftDelaySeconds());
            this.craftDelay.reset();
            this.delayActive = true;
            return null;
         }
         if (!this.craftDelay.elapsed()) {
            return null;
         }
         this.delayActive = false;
         int completed = CraftingInventoryOps.performCrafts(mod, step.recipeTarget(), step.craftsNeeded());
         if (completed > 0 || this.inventoryStepSatisfied(mod, step)) {
            this.inventoryStepIndex++;
         } else {
            this.beginIngredientRecovery(mod);
         }
         return null;
      }
      return null;
   }

   private boolean inventoryStepSatisfied(PlayerEngineController mod, CraftMacroStep step) {
      if (step.kind() == CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY) {
         if (this.craftMacroTableReachable(mod)) {
            return true;
         }
         if (mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) >= 1) {
            return true;
         }
      }
      int need = step.recipeTarget().getTargetCount();
      if (step.outputMatches() != null && step.outputMatches().length > 0) {
         // Pooled satisfaction (chest plan): any item in the match set counts (e.g. mixed planks).
         return mod.getItemStorage().getItemCount(step.outputMatches()) >= need;
      }
      Item output = step.recipeTarget().getOutputItem();
      return mod.getItemStorage().getItemCount(output) >= need;
   }

   private void replan(PlayerEngineController mod) {
      CraftMacroPlanner.plan(mod, this.plan.requestedOutput()).ifPresent(p -> this.plan = p);
   }

   @Override
   protected void onResourceStop(PlayerEngineController mod, Task interruptTask) {
      this.craftingTablePos = null;
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof CraftMacroResourceTask task && task.plan.outputItem().equals(this.plan.outputItem());
   }

   @Override
   protected String toDebugStringName() {
      return "CraftMacro: " + this.plan.outputItem().getDescription().getString() + " (" + this.phase + ")";
   }

   @Override
   public String describeProgress() {
      String table = this.craftingTablePos != null ? this.craftingTablePos.toShortString() : "none";
      return "phase=" + this.phase + " target=" + ItemHelper.stripItemName(this.plan.outputItem()) + " table=" + table;
   }

   public CraftMacroPhase getPhase() {
      return this.phase;
   }

   /** Terminal failure reason recorded by {@link #terminateMacro}, or {@code null} if none. */
   public String getFailureReason() {
      return this.failureReason;
   }

   public BlockPos getCraftingTablePos() {
      return this.craftingTablePos;
   }
}
