package com.player2.playerengine.tasks.crafting;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.util.CraftingRecipe;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.RecipeTarget;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class CraftMacroPlanner {
   private CraftMacroPlanner() {
   }

   public static Optional<CraftMacroPlan> plan(PlayerEngineController mod, ItemTarget requested) {
      Optional<Item> outputOpt = CraftMacroSupport.resolveSingleOutputItem(requested);
      if (outputOpt.isEmpty()) {
         return Optional.empty();
      }
      Item outputItem = outputOpt.get();
      int targetCount = requested.getTargetCount();
      int have = mod.getItemStorage().getItemCount(outputItem);
      if (have >= targetCount) {
         return Optional.of(
               new CraftMacroPlan(requested, outputItem, targetCount, List.of(), List.of(), false, null, null));
      }

      if (outputItem == Items.CHEST) {
         return Optional.of(planChest(mod, requested, outputItem, targetCount));
      }
      if (Arrays.asList(ItemHelper.WOOD_SIGN).contains(outputItem)) {
         ItemHelper.WoodItems wood = findWoodForItem(mod, outputItem);
         if (wood == null) {
            wood = ItemHelper.getWoodItems(com.player2.playerengine.util.WoodType.OAK);
         }
         return Optional.of(planSign(mod, requested, outputItem, targetCount, wood));
      }
      if (outputItem == Items.CRAFTING_TABLE) {
         ItemHelper.WoodItems wood = pickBestWoodFamily(mod);
         return Optional.of(planCraftingTable(mod, requested, targetCount, wood));
      }
      if (outputItem == Items.STICK) {
         ItemHelper.WoodItems wood = pickBestWoodFamily(mod);
         return Optional.of(planSticks(mod, requested, targetCount, wood));
      }
      if (Arrays.asList(ItemHelper.PLANKS).contains(outputItem)) {
         ItemHelper.WoodItems wood = findWoodForItem(mod, outputItem);
         if (wood == null) {
            return Optional.empty();
         }
         return Optional.of(planPlanks(mod, requested, outputItem, targetCount, wood));
      }
      return Optional.empty();
   }

   /**
    * Chest plan with mixed-wood pooling (scoped to the chest output only). Unlike the single-wood
    * {@link #buildWoodPlan} path used by planks/sticks/table/sign, this pools plank and log counts
    * across ALL wood families the bot holds (the real vanilla chest recipe uses {@code #minecraft:planks}
    * and accepts mixed planks — see {@code TaskCatalogue} chest entry), so >=2 logs of any mix is enough
    * to craft a chest immediately instead of chasing one nearby wood type.
    */
   private static CraftMacroPlan planChest(PlayerEngineController mod, ItemTarget requested, Item outputItem, int targetCount) {
      int need = targetCount - mod.getItemStorage().getItemCount(outputItem);
      int planksNeeded = 8 * need;

      int plankHave = mod.getItemStorage().getItemCount(ItemHelper.PLANKS);
      int logHave = mod.getItemStorage().getItemCount(ItemHelper.LOG);

      List<CraftMacroStep> steps = new ArrayList<>();
      List<ItemTarget> external = new ArrayList<>();

      // Crafting-table cost is paid in planks too (4) when no table is reachable / on hand.
      boolean needTableCraft = mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) < 1 && !hasReachableCraftingTable(mod);
      int tablePlankCost = needTableCraft ? 4 : 0;
      int totalPlankBudget = planksNeeded + tablePlankCost;

      int plankCapacity = plankHave + logHave * 4;
      if (plankCapacity < totalPlankBudget) {
         int logsToGet = (int)Math.ceil((double)(totalPlankBudget - plankCapacity) / 4.0);
         if (logsToGet > 0) {
            // Any log will do — gather pooled, do not fixate on one wood family. Request the catalogue
            // "log" entry (a NAMED target) rather than a bare multi-match ItemTarget(ItemHelper.LOG, ...):
            // a multi-match, non-catalogue target sent to TaskCatalogue.getItemTask self-recurses through
            // getSquashedItemTask -> CataloguedResourceTask -> getItemTask forever and overflows the stack
            // (server crash). The named "log" entry (TaskCatalogue: mine("log", ..., ItemHelper.LOG)) pools
            // every log type via its registered MineAndCollectTask and resolves to a real gather task.
            external.add(new ItemTarget("log", logsToGet));
         }
      }

      // Plank shortfall (after counting pooled planks already on hand) is covered by converting logs.
      // Emit one survival-honest plank step PER wood family the bot actually holds logs for (right log ->
      // right plank). Each step is marked with the pooled #planks match set, and — critically — its
      // satisfaction target is the CUMULATIVE running total of planks expected once that family's logs are
      // converted (plankHave + planks budgeted through this family), NOT just this family's own contribution.
      //
      // Why cumulative: inventoryStepSatisfied compares the pooled #planks count against the step's
      // targetCount. If every per-family step used only its own small contribution (e.g. 4) as the target,
      // then as soon as the FIRST family produced enough to meet that small number, every LATER family step
      // would already read as "satisfied" against the shared pooled count and be skipped — stranding the
      // remaining logs and leaving the chest short (the acacia-4-planks-then-quit bug: oak logs left unused).
      // Cumulative targets make each family step advance the pooled total toward the full budget.
      int plankShortfall = totalPlankBudget - plankHave;
      if (plankShortfall > 0) {
         int budgetedPlanks = 0;
         for (ItemHelper.WoodItems wood : ItemHelper.getWoodItems()) {
            if (budgetedPlanks >= plankShortfall) {
               break;
            }
            int familyLogs = mod.getItemStorage().getItemCount(wood.log);
            if (familyLogs <= 0) {
               continue;
            }
            int planksFromFamily = familyLogs * 4;
            int planksToMake = Math.min(planksFromFamily, plankShortfall - budgetedPlanks);
            int crafts = (int)Math.ceil((double)planksToMake / 4.0);
            budgetedPlanks += planksToMake;
            int cumulativeTarget = plankHave + budgetedPlanks;
            steps.add(
                  new CraftMacroStep(
                        CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY,
                        new RecipeTarget(wood.planks, cumulativeTarget, plankRecipe(wood.log)),
                        crafts,
                        "planks(" + wood.prefix + ")",
                        ItemHelper.PLANKS));
         }
      }

      if (needTableCraft) {
         // Craft a crafting table from any plank (the table-craft consume path matches any plank too).
         steps.add(
               new CraftMacroStep(
                     CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY, anyPlankTableRecipe(), 1, "crafting_table"));
      }

      RecipeTarget chestRecipe = anyPlankChestRecipe();
      int crafts = (int)Math.ceil((double)need / chestRecipe.getRecipe().outputCount());
      steps.add(new CraftMacroStep(CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE, chestRecipe, crafts, "chest"));

      return new CraftMacroPlan(requested, outputItem, targetCount, steps, external, true, null, chestRecipe);
   }

   private static CraftMacroPlan planSign(
         PlayerEngineController mod, ItemTarget requested, Item outputItem, int targetCount, ItemHelper.WoodItems wood) {
      int have = mod.getItemStorage().getItemCount(outputItem);
      int need = targetCount - have;
      int crafts = (int)Math.ceil((double)need / 3.0);
      int planksNeeded = 6 * crafts;
      int sticksNeeded = crafts;
      return buildWoodPlan(mod, requested, outputItem, targetCount, wood, planksNeeded, sticksNeeded, true, signRecipe(wood));
   }

   private static CraftMacroPlan planCraftingTable(
         PlayerEngineController mod, ItemTarget requested, int targetCount, ItemHelper.WoodItems wood) {
      int need = targetCount - mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE);
      int planksNeeded = 4 * need;
      CraftMacroPlan base = buildWoodPlan(mod, requested, Items.CRAFTING_TABLE, targetCount, wood, planksNeeded, 0, false, null);
      List<CraftMacroStep> steps = new ArrayList<>(base.steps());
      steps.add(
            new CraftMacroStep(
                  CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY, tableRecipe(wood.planks), need, "crafting_table"));
      return new CraftMacroPlan(
            base.requestedOutput(),
            base.outputItem(),
            base.targetCount(),
            steps,
            base.externalMaterials(),
            false,
            wood,
            null);
   }

   private static CraftMacroPlan planSticks(
         PlayerEngineController mod, ItemTarget requested, int targetCount, ItemHelper.WoodItems wood) {
      int have = mod.getItemStorage().getItemCount(Items.STICK);
      int need = targetCount - have;
      int crafts = (int)Math.ceil((double)need / 4.0);
      int planksNeeded = 2 * crafts;
      return buildWoodPlan(mod, requested, Items.STICK, targetCount, wood, planksNeeded, crafts, false, null);
   }

   private static CraftMacroPlan planPlanks(
         PlayerEngineController mod, ItemTarget requested, Item plank, int targetCount, ItemHelper.WoodItems wood) {
      int have = mod.getItemStorage().getItemCount(plank);
      int need = targetCount - have;
      List<ItemTarget> external = new ArrayList<>();
      int plankHave = mod.getItemStorage().getItemCount(wood.planks);
      int logHave = mod.getItemStorage().getItemCount(wood.log);
      int potential = plankHave + logHave * 4;
      if (potential < need && logHave == 0 && plankHave < need) {
         int logsNeeded = (int)Math.ceil((double)(need - plankHave) / 4.0);
         external.add(new ItemTarget(wood.log, logsNeeded));
      }
      List<CraftMacroStep> steps = new ArrayList<>();
      if (need > 0) {
         steps.add(
               new CraftMacroStep(
                     CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY,
                     new RecipeTarget(plank, need, plankRecipe(wood.log)),
                     (int)Math.ceil((double)need / 4.0),
                     "planks"));
      }
      return new CraftMacroPlan(requested, plank, targetCount, steps, external, false, wood, null);
   }

   private static CraftMacroPlan buildWoodPlan(
         PlayerEngineController mod,
         ItemTarget requested,
         Item outputItem,
         int targetCount,
         ItemHelper.WoodItems wood,
         int planksNeeded,
         int sticksNeeded,
         boolean requiresTable,
         RecipeTarget finalRecipe) {
      List<CraftMacroStep> steps = new ArrayList<>();
      List<ItemTarget> external = new ArrayList<>();

      int plankHave = mod.getItemStorage().getItemCount(wood.planks);
      int logHave = mod.getItemStorage().getItemCount(wood.log);
      int stickHave = mod.getItemStorage().getItemCount(Items.STICK);

      int sticksStill = Math.max(0, sticksNeeded - stickHave);
      boolean needTableCraft = requiresTable
            && mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) < 1
            && !hasReachableCraftingTable(mod)
            && finalRecipe != null;
      int tablePlankCost = needTableCraft ? 4 : 0;
      int stickPlankCost = sticksStill > 0 ? sticksStill * 2 : 0;
      int totalPlankBudget = planksNeeded + stickPlankCost + tablePlankCost;
      int plankCapacity = plankHave + logHave * 4;
      if (plankCapacity < totalPlankBudget) {
         int logsToGet = (int)Math.ceil((double)(totalPlankBudget - plankCapacity) / 4.0);
         if (logsToGet > 0) {
            external.add(new ItemTarget(wood.log, logsToGet));
         }
      }

      if (planksNeeded > plankHave || logHave > 0) {
         int crafts = (int)Math.ceil((double)Math.max(planksNeeded, plankHave + logHave * 4) / 4.0);
         if (planksNeeded > 0) {
            steps.add(
                  new CraftMacroStep(
                        CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY,
                        new RecipeTarget(wood.planks, planksNeeded, plankRecipe(wood.log)),
                        Math.max(1, (int)Math.ceil((double)planksNeeded / 4.0)),
                        "planks"));
         }
      }

      if (sticksStill > 0) {
         int plankForSticks = plankHave + logHave * 4;
         if (plankForSticks < sticksStill * 2) {
            int extraPlanks = sticksStill * 2 - plankForSticks;
            if (!steps.stream().anyMatch(s -> s.kind() == CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY)) {
               steps.add(
                     new CraftMacroStep(
                           CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY,
                           new RecipeTarget(wood.planks, extraPlanks, plankRecipe(wood.log)),
                           (int)Math.ceil((double)extraPlanks / 4.0),
                           "planks_for_sticks"));
            }
         }
         steps.add(
               new CraftMacroStep(
                     CraftMacroStepKind.CRAFT_STICKS_IN_INVENTORY,
                     sticksRecipeTarget(sticksNeeded),
                     (int)Math.ceil((double)sticksStill / 4.0),
                     "sticks"));
      }

      if (requiresTable
            && mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) < 1
            && !hasReachableCraftingTable(mod)
            && finalRecipe != null) {
         if (plankHave + logHave * 4 < 4) {
            int extra = 4 - (plankHave + logHave * 4);
            steps.add(
                  new CraftMacroStep(
                        CraftMacroStepKind.CRAFT_PLANKS_IN_INVENTORY,
                        new RecipeTarget(wood.planks, extra, plankRecipe(wood.log)),
                        (int)Math.ceil((double)extra / 4.0),
                        "planks_for_table"));
         }
         steps.add(
               new CraftMacroStep(CraftMacroStepKind.CRAFT_CRAFTING_TABLE_IN_INVENTORY, tableRecipe(wood.planks), 1, "crafting_table"));
      }

      if (finalRecipe != null) {
         int outHave = mod.getItemStorage().getItemCount(outputItem);
         int outNeed = targetCount - outHave;
         int crafts = (int)Math.ceil((double)outNeed / finalRecipe.getRecipe().outputCount());
         steps.add(
               new CraftMacroStep(
                     CraftMacroStepKind.CRAFT_OUTPUT_IN_TABLE, finalRecipe, crafts, outputItem.getDescription().getString()));
      }

      return new CraftMacroPlan(requested, outputItem, targetCount, steps, external, requiresTable, wood, finalRecipe);
   }

   private static boolean hasReachableCraftingTable(PlayerEngineController mod) {
      return CraftingTableLocator.findReachable(mod, null).isPresent();
   }

   private static ItemHelper.WoodItems pickBestWoodFamily(PlayerEngineController mod) {
      return ItemHelper.getWoodItems().stream()
            .max(Comparator.comparingInt(w -> woodAvailabilityScore(mod, w)))
            .orElse(ItemHelper.getWoodItems(com.player2.playerengine.util.WoodType.OAK));
   }

   /** Inventory plus nearby scanned log blocks (weighted like planks) so macros do not chase the wrong wood type. */
   private static int woodAvailabilityScore(PlayerEngineController mod, ItemHelper.WoodItems wood) {
      int score = mod.getItemStorage().getItemCount(wood.planks) + mod.getItemStorage().getItemCount(wood.log) * 4;
      score += nearbyLogBlocks(mod, wood) * 4;
      return score;
   }

   private static int nearbyLogBlocks(PlayerEngineController mod, ItemHelper.WoodItems wood) {
      int count = 0;
      count += knownBlockCount(mod, wood.log);
      count += knownBlockCount(mod, wood.wood);
      count += knownBlockCount(mod, wood.strippedLog);
      count += knownBlockCount(mod, wood.strippedWood);
      return count;
   }

   private static int knownBlockCount(PlayerEngineController mod, Item item) {
      if (item == null || item == Items.AIR) {
         return 0;
      }
      Block block = Block.byItem(item);
      return block != null && block != Blocks.AIR ? mod.getBlockScanner().getKnownLocations(block).size() : 0;
   }

   private static ItemHelper.WoodItems findWoodForItem(PlayerEngineController mod, Item item) {
      for (ItemHelper.WoodItems wood : ItemHelper.getWoodItems()) {
         if (wood.planks == item || wood.sign == item || wood.log == item) {
            return wood;
         }
      }
      return null;
   }

   private static CraftingRecipe plankRecipe(Item log) {
      return CraftingRecipe.newShapedRecipe("planks", new Item[][]{new Item[]{log}, null, null, null}, 4);
   }

   private static RecipeTarget sticksRecipeTarget(int stickCount) {
      return new RecipeTarget(
            Items.STICK,
            stickCount,
            CraftingRecipe.newShapedRecipe(
                  "sticks", new ItemTarget[]{new ItemTarget("planks"), null, new ItemTarget("planks"), null}, 4));
   }

   private static RecipeTarget tableRecipe(Item plank) {
      return new RecipeTarget(
            Items.CRAFTING_TABLE,
            1,
            CraftingRecipe.newShapedRecipe(
                  "crafting_table",
                  new ItemTarget[]{new ItemTarget(plank), new ItemTarget(plank), new ItemTarget(plank), new ItemTarget(plank)},
                  1));
   }

   /** Chest recipe whose 8 plank slots accept ANY plank ({@code #minecraft:planks}), pooling mixed planks. */
   private static RecipeTarget anyPlankChestRecipe() {
      ItemTarget p = new ItemTarget(ItemHelper.PLANKS, 1);
      return new RecipeTarget(
            Items.CHEST,
            1,
            CraftingRecipe.newShapedRecipe(
                  new ItemTarget[]{p, p, p, p, ItemTarget.EMPTY, p, p, p, p}, 1));
   }

   /** Crafting-table recipe whose 4 plank slots accept ANY plank, so the chest plan can use mixed planks. */
   private static RecipeTarget anyPlankTableRecipe() {
      ItemTarget p = new ItemTarget(ItemHelper.PLANKS, 1);
      return new RecipeTarget(
            Items.CRAFTING_TABLE, 1, CraftingRecipe.newShapedRecipe(new ItemTarget[]{p, p, p, p}, 1));
   }

   private static RecipeTarget signRecipe(ItemHelper.WoodItems wood) {
      ItemTarget planks = new ItemTarget(wood.planks, 1);
      ItemTarget stick = TaskCatalogue.getItemTarget("stick", 1);
      return new RecipeTarget(
            wood.sign,
            3,
            CraftingRecipe.newShapedRecipe(
                  new ItemTarget[]{planks, planks, planks, planks, planks, planks, null, stick, null}, 3));
   }
}
