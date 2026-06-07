package com.player2.playerengine;

import com.player2.playerengine.control.KillAura;
import com.player2.playerengine.tasks.movement.DefaultGoToDimensionTask;
import com.player2.playerengine.util.BlockRange;
import com.player2.playerengine.util.helpers.ConfigHelper;
import com.player2.playerengine.util.helpers.ItemHelper;
import com.player2.playerengine.util.serialization.IFailableConfigFile;
import com.google.common.collect.Streams;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class PlayerEngineSettings implements IFailableConfigFile {

   private transient boolean failedToLoad = false;
   private boolean showDebugTickMs = false;
   private boolean showTaskChains = true;
   private boolean hideAllWarningLogs = false;
   private String commandPrefix = "@";
   private String logLevel = "NORMAL";
   private String chatLogPrefix = "[Alto Clef] ";
   private boolean showTimer = true;
   private float containerItemMoveDelay = 0.2F;
   private boolean useCraftingBookToCraft = true;
   private float resourcePickupDropRange = 16.0F;
   private int minimumFoodAllowed = 0;
   private int foodUnitsToCollect = 0;
   private float resourceChestLocateRange = 500.0F;
   private float resourceMineRange = 100.0F;
   private boolean avoidSearchingDungeonChests = true;
   private boolean avoidOceanBlocks = false;
   private float entityReachRange = 4.0F;
   private boolean collectPickaxeFirst = true;
   private boolean replantCrops = true;
   private boolean mobDefense = true;
   private KillAura.Strategy forceFieldStrategy = KillAura.Strategy.SMART;
   private boolean dodgeProjectiles = true;
   private boolean killOrAvoidAnnoyingHostiles = true;
   private boolean avoidDrowning = true;
   private boolean autoCloseScreenWhenLookingOrMining = true;
   private boolean extinguishSelfWithWater = true;
   private boolean autoEat = true;
   private boolean autoMLGBucket = true;
   private boolean autoReconnect = true;
   private boolean autoRespawn = true;
   private DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR overworldToNetherBehaviour = DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR.BUILD_PORTAL_VANILLA;
   private int netherFastTravelWalkingRange = 600;
   private String idleCommand = "idle";
   private String deathCommand = "";
   private boolean fastCraftMacrosEnabled = true;
   private float craftDelaySeconds = 0.5F;
   private float craftTableLookHoldSeconds = 0.25F;
   private boolean preferLocalCraftingTable = true;
   private boolean enableAgenticPlanner = false;
   private int agenticPlannerRagTopK = 8;
   private int agenticPlannerMaxSteps = 4;
   private boolean agenticPlannerFallbackGather = true;
   private float gatherLooseItemsRadius = 16.0F;
   private int gatherLooseItemsMaxItems = 64;
   private float gatherLooseItemsTimeoutSeconds = 60.0F;
   private float gatherLooseItemsSettleSeconds = 3.0F;
   private float agenticStorageSearchRadius = 20.0F;
   private float agenticStoragePlacementRadius = 8.0F;
   private boolean agenticStorageAllowPlacement = true;
   private boolean agenticStoragePreferExisting = true;
   private boolean agenticStorageAvoidLootChests = true;
   private float agenticStorageResolveTimeoutSeconds = 180.0F;
   private float agenticDepositTimeoutSeconds = 120.0F;
   private boolean agenticDepositKeepTools = true;
   private boolean agenticEnableLabelChest = true;
   private float agenticLabelTimeoutSeconds = 60.0F;
   private boolean agenticLabelUseModelText = false;
   private float aggregateCountDropRadius = 16.0F;
   private float aggregateLocalSourceBlockRadius = 32.0F;
   private float mineCollectSettleSeconds = 1.0F;
   private int wanderBoundDefaultSeconds = 90;
   private int wanderNoImprovementSeconds = 30;

   private List<Item> throwawayItems = Arrays.asList(
      Items.DRIPSTONE_BLOCK,
      Items.ROOTED_DIRT,
      Items.GRAVEL,
      Items.SAND,
      Items.DIORITE,
      Items.ANDESITE,
      Items.GRANITE,
      Items.TUFF,
      Items.COBBLESTONE,
      Items.DIRT,
      Items.COBBLED_DEEPSLATE,
      Items.ACACIA_LEAVES,
      Items.BIRCH_LEAVES,
      Items.DARK_OAK_LEAVES,
      Items.OAK_LEAVES,
      Items.JUNGLE_LEAVES,
      Items.SPRUCE_LEAVES,
      Items.NETHERRACK,
      Items.MAGMA_BLOCK,
      Items.SOUL_SOIL,
      Items.SOUL_SAND,
      Items.NETHER_BRICKS,
      Items.NETHER_BRICK,
      Items.BASALT,
      Items.BLACKSTONE,
      Items.END_STONE,
      Items.SANDSTONE,
      Items.STONE_BRICKS
   );
   private int reservedBuildingBlockCount = 64;
   private boolean dontThrowAwayCustomNameItems = true;
   private boolean dontThrowAwayEnchantedItems = true;
   private boolean throwAwayUnusedItems = true;

   private List<Item> importantItems = Streams.concat(
         new Stream[]{
            Stream.of(
               Items.TOTEM_OF_UNDYING,
               Items.ENCHANTED_GOLDEN_APPLE,
               Items.ENDER_EYE,
               Items.TRIDENT,
               Items.DIAMOND,
               Items.DIAMOND_BLOCK,
               Items.NETHERITE_SCRAP,
               Items.NETHERITE_INGOT,
               Items.NETHERITE_BLOCK
            ),
            Stream.of(ItemHelper.DIAMOND_ARMORS),
            Stream.of(ItemHelper.NETHERITE_ARMORS),
            Stream.of(ItemHelper.DIAMOND_TOOLS),
            Stream.of(ItemHelper.NETHERITE_TOOLS),
            Stream.of(ItemHelper.SHULKER_BOXES)
         }
      )
      .toList();
   private boolean limitFuelsToSupportedFuels = true;

   private List<Item> supportedFuels = Streams.concat(new Stream[]{Stream.of(Items.COAL, Items.CHARCOAL)}).toList();
   private BlockPos homeBasePosition = new BlockPos(0, 64, 0);
   private List<BlockRange> areasToProtect = Collections.emptyList();

   public static void load(Consumer<PlayerEngineSettings> onReload) {
      ConfigHelper.loadConfig(PlayerEngine.MOD_ID+ "_settings.json", PlayerEngineSettings::new, PlayerEngineSettings.class, onReload);
   }

   public boolean shouldShowTaskChain() {
      return this.showTaskChains;
   }

   public boolean shouldShowDebugTickMs() {
      return this.showDebugTickMs;
   }

   public boolean shouldHideAllWarningLogs() {
      return this.hideAllWarningLogs;
   }

   public String getLogLevel() {
      return this.logLevel;
   }

   public String getCommandPrefix() {
      return this.commandPrefix;
   }

   public String getChatLogPrefix() {
      return this.chatLogPrefix;
   }

   public boolean shouldShowTimer() {
      return this.showTimer;
   }

   public float getResourcePickupRange() {
      return this.resourcePickupDropRange;
   }

   public float getResourceChestLocateRange() {
      return this.resourceChestLocateRange;
   }

   public float getResourceMineRange() {
      return this.resourceMineRange;
   }

   public float getContainerItemMoveDelay() {
      return this.containerItemMoveDelay;
   }

   public boolean shouldUseCraftingBookToCraft() {
      return this.useCraftingBookToCraft;
   }

   public int getFoodUnitsToCollect() {
      return this.foodUnitsToCollect;
   }

   public int getMinimumFoodAllowed() {
      return this.minimumFoodAllowed;
   }

   public boolean isMobDefense() {
      return this.mobDefense;
   }

   public boolean isDodgeProjectiles() {
      return this.dodgeProjectiles;
   }

   public boolean isAutoEat() {
      return this.autoEat;
   }

   public boolean isAutoReconnect() {
      return this.autoReconnect;
   }

   public boolean isAutoRespawn() {
      return this.autoRespawn;
   }

   public boolean shouldReplantCrops() {
      return this.replantCrops;
   }

   public boolean shouldDealWithAnnoyingHostiles() {
      return this.killOrAvoidAnnoyingHostiles;
   }

   public KillAura.Strategy getForceFieldStrategy() {
      return this.forceFieldStrategy;
   }

   public String getIdleCommand() {
      return this.idleCommand == "" ? "idle" : this.idleCommand;
   }

   public String getDeathCommand() {
      return this.deathCommand;
   }

   public boolean shouldRunIdleCommandWhenNotActive() {
      String command = this.getIdleCommand();
      return command != null && !command.isBlank();
   }

   public boolean shouldAutoMLGBucket() {
      return this.autoMLGBucket;
   }

   public boolean shouldCollectPickaxeFirst() {
      return this.collectPickaxeFirst;
   }

   public boolean shouldAvoidDrowning() {
      return this.avoidDrowning;
   }

   public boolean shouldCloseScreenWhenLookingOrMining() {
      return this.autoCloseScreenWhenLookingOrMining;
   }

   public boolean shouldExtinguishSelfWithWater() {
      return this.extinguishSelfWithWater;
   }

   public boolean shouldAvoidSearchingForDungeonChests() {
      return this.avoidSearchingDungeonChests;
   }

   public boolean shouldAvoidOcean() {
      return this.avoidOceanBlocks;
   }

   public boolean isThrowaway(Item item) {
      return this.throwawayItems.contains(item);
   }

   public boolean isImportant(Item item) {
      return this.importantItems.contains(item);
   }

   public boolean shouldThrowawayUnusedItems() {
      return this.throwAwayUnusedItems;
   }

   public int getReservedBuildingBlockCount() {
      return this.reservedBuildingBlockCount;
   }

   public boolean getDontThrowAwayCustomNameItems() {
      return this.dontThrowAwayCustomNameItems;
   }

   public boolean getDontThrowAwayEnchantedItems() {
      return this.dontThrowAwayEnchantedItems;
   }

   public float getEntityReachRange() {
      return this.entityReachRange;
   }

   public Item[] getThrowawayItems(PlayerEngineController mod, boolean includeProtected) {
      return this.throwawayItems.stream().filter(item -> includeProtected || !mod.getBehaviour().isProtected(item)).toArray(Item[]::new);
   }

   public Item[] getThrowawayItems(PlayerEngineController mod) {
      return this.getThrowawayItems(mod, false);
   }

   public boolean shouldLimitFuelsToSupportedFuels() {
      return this.limitFuelsToSupportedFuels;
   }

   public boolean isSupportedFuel(Item item) {
      return !this.limitFuelsToSupportedFuels || this.supportedFuels.contains(item);
   }

   public Item[] getSupportedFuelItems() {
      return this.supportedFuels.toArray(Item[]::new);
   }

   public DefaultGoToDimensionTask.OVERWORLD_TO_NETHER_BEHAVIOUR getOverworldToNetherBehaviour() {
      return this.overworldToNetherBehaviour;
   }

   public int getNetherFastTravelWalkingRange() {
      return this.netherFastTravelWalkingRange;
   }

   public BlockPos getHomeBasePosition() {
      return this.homeBasePosition;
   }

   public boolean isFastCraftMacrosEnabled() {
      return this.fastCraftMacrosEnabled;
   }

   public double getCraftDelaySeconds() {
      return clamp(this.craftDelaySeconds, 0.0F, 5.0F);
   }

   public double getCraftTableLookHoldSeconds() {
      return clamp(this.craftTableLookHoldSeconds, 0.0F, 3.0F);
   }

   public boolean isPreferLocalCraftingTable() {
      return this.preferLocalCraftingTable;
   }

   public boolean isEnableAgenticPlanner() {
      return this.enableAgenticPlanner;
   }

   public int getAgenticPlannerRagTopK() {
      return (int) clamp(this.agenticPlannerRagTopK, 1, 20);
   }

   public int getAgenticPlannerMaxSteps() {
      return (int) clamp(this.agenticPlannerMaxSteps, 1, 4);
   }

   public boolean isAgenticPlannerFallbackGather() {
      return this.agenticPlannerFallbackGather;
   }

   public double getGatherLooseItemsRadius() {
      return clamp(this.gatherLooseItemsRadius, 2.0F, 64.0F);
   }

   public int getGatherLooseItemsMaxItems() {
      return (int) clamp(this.gatherLooseItemsMaxItems, 1, 1024);
   }

   public double getGatherLooseItemsTimeoutSeconds() {
      return clamp(this.gatherLooseItemsTimeoutSeconds, 5.0F, 300.0F);
   }

   public double getGatherLooseItemsSettleSeconds() {
      return clamp(this.gatherLooseItemsSettleSeconds, 0.5F, 20.0F);
   }

   public double getAgenticStorageSearchRadius() {
      return clamp(this.agenticStorageSearchRadius, 4.0F, 64.0F);
   }

   public double getAgenticStoragePlacementRadius() {
      return clamp(this.agenticStoragePlacementRadius, 2.0F, 16.0F);
   }

   public boolean isAgenticStorageAllowPlacement() {
      return this.agenticStorageAllowPlacement;
   }

   public boolean isAgenticStoragePreferExisting() {
      return this.agenticStoragePreferExisting;
   }

   public boolean isAgenticStorageAvoidLootChests() {
      return this.agenticStorageAvoidLootChests;
   }

   public double getAgenticStorageResolveTimeoutSeconds() {
      return clamp(this.agenticStorageResolveTimeoutSeconds, 10.0F, 300.0F);
   }

   public double getAgenticDepositTimeoutSeconds() {
      return clamp(this.agenticDepositTimeoutSeconds, 10.0F, 300.0F);
   }

   public boolean isAgenticDepositKeepTools() {
      return this.agenticDepositKeepTools;
   }

   public boolean isAgenticEnableLabelChest() {
      return this.agenticEnableLabelChest;
   }

   public double getAgenticLabelTimeoutSeconds() {
      return clamp(this.agenticLabelTimeoutSeconds, 10.0F, 180.0F);
   }

   public boolean isAgenticLabelUseModelText() {
      return this.agenticLabelUseModelText;
   }

   public double getAggregateCountDropRadius() {
      return clamp(this.aggregateCountDropRadius, 0.0F, 64.0F);
   }

   public double getAggregateLocalSourceBlockRadius() {
      return clamp(this.aggregateLocalSourceBlockRadius, 0.0F, 128.0F);
   }

   public double getMineCollectSettleSeconds() {
      return clamp(this.mineCollectSettleSeconds, 0.0F, 5.0F);
   }

   public int getWanderBoundDefaultSeconds() {
      return (int) clamp(this.wanderBoundDefaultSeconds, 0, 600);
   }

   public int getWanderNoImprovementSeconds() {
      return (int) clamp(this.wanderNoImprovementSeconds, 0, 300);
   }

   private static double clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }

   private static double clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   @Override
   public void onFailLoad() {
      this.failedToLoad = true;
   }

   @Override
   public boolean failedToLoad() {
      return this.failedToLoad;
   }
}
