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

package com.player2.playerengine.automaton.pathing.movement;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.automaton.Baritone;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.automaton.behavior.InventoryBehavior;
import com.player2.playerengine.automaton.cache.WorldData;
import com.player2.playerengine.automaton.utils.BlockStateInterface;
import com.player2.playerengine.automaton.utils.ToolSet;
import com.player2.playerengine.automaton.utils.accessor.ILivingEntityAccessor;
import com.player2.playerengine.structureprotection.PlayerPlacedBlockStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class CalculationContext {
   private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
   /** Clamp bounds for the structure-protection penalty — must stay strictly below the 1000000.0 impossible gate. */
   protected static final double MIN_PROTECT_PENALTY = 2.0;
   protected static final double MAX_PROTECT_PENALTY = 999999.0;
   public final boolean safeForThreadedUse;
   public final IBaritone baritone;
   public final Level world;
   public final WorldData worldData;
   public final BlockStateInterface bsi;
   @Nullable
   public final ToolSet toolSet;
   public final boolean hasWaterBucket;
   public final boolean hasThrowaway;
   public final boolean canSprint;
   protected final double placeBlockCost;
   public final boolean allowBreak;
   public final boolean allowParkour;
   public final boolean allowParkourPlace;
   public final boolean allowJumpAt256;
   public final boolean allowParkourAscend;
   public final boolean assumeWalkOnWater;
   public final boolean allowDiagonalDescend;
   public final boolean allowDiagonalAscend;
   public final boolean allowDownward;
   public final int maxFallHeightNoWater;
   public final int maxFallHeightBucket;
   public final double waterWalkSpeed;
   public final double breakBlockAdditionalCost;
   public double backtrackCostFavoringCoefficient;
   public double jumpPenalty;
   public final double walkOnWaterOnePenalty;
   public final int worldBottom;
   public final int worldTop;
   public final int width;
   public final int requiredSideSpace;
   public final int height;
   private final IInventoryProvider player;
   private final MutableBlockPos blockPos;
   /** Dimension id ({@code minecraft:overworld}) of this context's world; resolved once (per-path constant). */
   private final String protectionDimId;
   public final int breathTime;
   public final int startingBreathTime;
   public final boolean allowSwimming;
   private final int airIncreaseOnLand;
   private final int airDecreaseInWater;

   public CalculationContext(IBaritone baritone) {
      this(baritone, false);
   }

   public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
      this.safeForThreadedUse = forUseOnAnotherThread;
      this.baritone = baritone;
      LivingEntity entity = baritone.getEntityContext().entity();
      this.player = entity instanceof IInventoryProvider ? (IInventoryProvider)entity : null;
      this.world = baritone.getEntityContext().world();
      this.protectionDimId = this.world.dimension().location().toString();
      this.worldData = (WorldData)baritone.getWorldProvider().getCurrentWorld();
      this.bsi = new BlockStateInterface(this.world);
      this.toolSet = this.player == null ? null : new ToolSet(entity);
      this.hasThrowaway = baritone.settings().allowPlace.get() && ((Baritone)baritone).getInventoryBehavior().hasGenericThrowaway();
      this.hasWaterBucket = this.player != null
         && baritone.settings().allowWaterBucketFall.get()
         && LivingEntityInventory.isValidHotbarIndex(InventoryBehavior.getSlotWithStack(this.player.getLivingInventory(), PlayerEngine.WATER_BUCKETS))
         && !this.world.dimensionType().ultraWarm();
      this.canSprint = this.player != null && baritone.settings().allowSprint.get();
      this.placeBlockCost = baritone.settings().blockPlacementPenalty.get();
      this.allowBreak = baritone.settings().allowBreak.get();
      this.allowParkour = baritone.settings().allowParkour.get();
      this.allowParkourPlace = baritone.settings().allowParkourPlace.get();
      this.allowJumpAt256 = baritone.settings().allowJumpAt256.get();
      this.allowParkourAscend = baritone.settings().allowParkourAscend.get();
      this.assumeWalkOnWater = baritone.settings().assumeWalkOnWater.get();
      this.allowDiagonalDescend = baritone.settings().allowDiagonalDescend.get();
      this.allowDiagonalAscend = baritone.settings().allowDiagonalAscend.get();
      this.allowDownward = baritone.settings().allowDownward.get();
      this.maxFallHeightNoWater = baritone.settings().maxFallHeightNoWater.get();
      this.maxFallHeightBucket = baritone.settings().maxFallHeightBucket.get();
      int depth = EnchantmentHelper.getDepthStrider(entity);
      if (depth > 3) {
         depth = 3;
      }

      float mult = depth / 3.0F;
      this.waterWalkSpeed = 9.09090909090909 * (1.0F - mult) + 4.63284688441047 * mult;
      this.breakBlockAdditionalCost = baritone.settings().blockBreakAdditionalPenalty.get();
      this.backtrackCostFavoringCoefficient = baritone.settings().backtrackCostFavoringCoefficient.get();
      this.jumpPenalty = baritone.settings().jumpPenalty.get();
      this.walkOnWaterOnePenalty = baritone.settings().walkOnWaterOnePenalty.get();
      this.worldTop = this.world.getMaxBuildHeight();
      this.worldBottom = this.world.getMinBuildHeight();
      EntityDimensions dimensions = entity.getDimensions(Pose.STANDING);
      this.width = Mth.ceil(dimensions.width);
      this.requiredSideSpace = getRequiredSideSpace(dimensions);
      this.height = Mth.ceil(dimensions.height);
      this.blockPos = new MutableBlockPos();
      this.allowSwimming = baritone.settings().allowSwimming.get();
      this.breathTime = baritone.settings().ignoreBreath.get() ? Integer.MAX_VALUE : entity.getMaxAirSupply();
      this.startingBreathTime = entity.getAirSupply();
      this.airIncreaseOnLand = ((ILivingEntityAccessor)entity).automatone$getNextAirOnLand(0);
      this.airDecreaseInWater = this.breathTime - ((ILivingEntityAccessor)entity).automatone$getNextAirUnderwater(this.breathTime);
   }

   public static int getRequiredSideSpace(EntityDimensions dimensions) {
      return Mth.ceil((dimensions.width - 1.0F) * 0.5F);
   }

   public final IBaritone getBaritone() {
      return this.baritone;
   }

   public BlockState get(int x, int y, int z) {
      return this.bsi.get0(x, y, z);
   }

   public boolean isLoaded(int x, int z) {
      return this.bsi.isLoaded(x, z);
   }

   public BlockState get(BlockPos pos) {
      return this.get(pos.getX(), pos.getY(), pos.getZ());
   }

   public Block getBlock(int x, int y, int z) {
      return this.get(x, y, z).getBlock();
   }

   /**
    * The {@code respectStructuresBreakPenalty} multiplier, clamped strictly below the 1000000.0 "impossible"
    * gate ({@link #MIN_PROTECT_PENALTY}..{@link #MAX_PROTECT_PENALTY}). Read-site clamp so an out-of-range
    * setting can never trip MovementHelper's {@code >= 1000000} gate and freeze the bot.
    */
   protected double protectedBreakPenalty() {
      double p = this.baritone.settings().respectStructuresBreakPenalty.get();
      if (p < MIN_PROTECT_PENALTY) {
         return MIN_PROTECT_PENALTY;
      }
      return p > MAX_PROTECT_PENALTY ? MAX_PROTECT_PENALTY : p;
   }

   /**
    * Place cost on a protected position: the finite penalty, but with the <em>product</em>
    * {@code placeBlockCost * penalty} also clamped below the impossible gate (placeBlockCost is itself a
    * setting, so the product — not just the multiplier — must stay finite).
    */
   protected double protectedPlaceCost() {
      double cost = this.placeBlockCost * protectedBreakPenalty();
      return cost > MAX_PROTECT_PENALTY ? MAX_PROTECT_PENALTY : cost;
   }

   public double costOfPlacingAt(int x, int y, int z, BlockState current) {
      if (!this.hasThrowaway) {
         return 1000000.0;
      } else {
         // Protected = strong-but-FINITE place cost (never the 1000000 impossible gate) so a walled-in bot is not frozen.
         return this.isProtected(x, y, z) ? protectedPlaceCost() : this.placeBlockCost;
      }
   }

   public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
      if (!this.allowBreak) {
         return 1000000.0;
      } else {
         // Protected = strong-but-FINITE break penalty (last-resort), never the 1000000 impossible gate -> no freeze.
         return this.isProtected(x, y, z) ? protectedBreakPenalty() : 1.0;
      }
   }

   public double placeBucketCost() {
      return this.placeBlockCost;
   }

   public boolean canPlaceAgainst(BlockPos pos) {
      return this.canPlaceAgainst(pos.getX(), pos.getY(), pos.getZ());
   }

   public boolean canPlaceAgainst(int againstX, int againstY, int againstZ) {
      return this.canPlaceAgainst(againstX, againstY, againstZ, this.bsi.get0(againstX, againstY, againstZ));
   }

   public boolean canPlaceAgainst(int againstX, int againstY, int againstZ, BlockState state) {
      // Protection is NOT consulted here: placing a new block *against* a protected block (using it as a
      // support face) never modifies the protected block, so gating it would be a hard exclusion (freeze
      // risk in narrow liquid-pillar/ascend cases). The cost of placing AT a protected position is the
      // finite penalty in costOfPlacingAt; this face check stays protection-agnostic.
      return MovementHelper.canPlaceAgainst(this.bsi, againstX, againstY, againstZ, state);
   }

   public boolean isProtected(int x, int y, int z) {
      if (!this.baritone.settings().respectStructuresEnabled.get()) {
         return false; // toggle off -> legacy behavior (no protection)
      }
      PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
      if (store == null) {
         return false; // no world store loaded -> nothing protected
      }
      this.blockPos.set(x, y, z); // reused mutable pos: contains() reads it synchronously, allocation-free
      return store.contains(this.protectionDimId, this.blockPos);
   }

   public double oxygenCost(double baseCost, BlockState headState) {
      return headState.getFluidState().is(FluidTags.WATER) && !headState.is(Blocks.BUBBLE_COLUMN)
         ? this.airDecreaseInWater * baseCost
         : -1 * this.airIncreaseOnLand * baseCost;
   }
}
