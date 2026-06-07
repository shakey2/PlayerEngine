package com.player2.playerengine.tasks.resources;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.multiversion.ToolMaterialVer;
import com.player2.playerengine.multiversion.blockpos.BlockPosVer;
import com.player2.playerengine.tasks.AbstractDoToClosestObjectTask;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.construction.DestroyBlockTask;
import com.player2.playerengine.tasks.movement.PickupDroppedItemTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.MiningRequirement;
import com.player2.playerengine.util.helpers.MaterialAvailability;
import com.player2.playerengine.util.helpers.StorageHelper;
import com.player2.playerengine.util.helpers.WorldHelper;
import com.player2.playerengine.util.progresscheck.MovementProgressChecker;
import com.player2.playerengine.util.slots.CursorSlot;
import com.player2.playerengine.util.slots.PlayerSlot;
import com.player2.playerengine.util.time.TimerGame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

public class MineAndCollectTask extends ResourceTask {
   private final Block[] blocksToMine;
   private final MiningRequirement requirement;
   private final TimerGame cursorStackTimer = new TimerGame(3.0);
   private final MineAndCollectTask.MineOrCollectTask subtask;

   public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
      super(itemTargets);
      this.requirement = requirement;
      this.blocksToMine = blocksToMine;
      this.subtask = new MineAndCollectTask.MineOrCollectTask(this.blocksToMine, itemTargets);
   }

   public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
      this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
   }

   public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
      this(new ItemTarget[]{target}, blocksToMine, requirement);
   }

   public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
      this(new ItemTarget(item, count), blocksToMine, requirement);
   }

   public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
      List<Block> result = new ArrayList<>(targets.length);

      for (ItemTarget target : targets) {
         for (Item item : target.getMatches()) {
            Block block = Block.byItem(item);
            if (block != null && !WorldHelper.isAir(block)) {
               result.add(block);
            }
         }
      }

      return result.toArray(Block[]::new);
   }

   @Override
   protected void onResourceStart(PlayerEngineController mod) {
      mod.getBehaviour().push();
      mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
      this.subtask.resetSearch();
   }

   @Override
   protected boolean shouldAvoidPickingUp(PlayerEngineController mod) {
      return true;
   }

   @Override
   protected Task onResourceTick(PlayerEngineController mod) {
      // MineAndCollect-LOCAL sufficiency gate (plan WS3, decision 10). Anti-over-mining: as soon as the
      // SUFFICIENCY axis (inventory + reachable nearby ground / just-mined drops) covers the targets, we
      // must STOP mining new blocks. But "sufficiency met" does NOT mean "done" while strict inventory is
      // still short and the counted drops are still on the ground -- short-circuiting to null there would
      // strand the bot (inventory short, onResourceTick returns null, base auto-pickup disabled => stall,
      // and the counted drops never get collected). So we split the two axes (plan reviewer Option A):
      //   * STRICT inventory-only gate met  -> finish (return null); the base isFinished() will agree.
      //   * Sufficiency met via drops but inventory short -> DO NOT mine more; fall through to the subtask,
      //     which prefers nearby drops over wandering and collects the counted drops into the inventory.
      //     Once they land, the strict base isFinished() flips true and the chain finishes -- no over-mine,
      //     no stall.
      // The shared base ResourceTask.isFinished() stays on the strict gate (NOT overridden) so the other
      // 44 ResourceTask subclasses are unaffected. Mineable blocks never count toward either gate (they
      // only steer source-routing in getWanderTask).
      if (StorageHelper.itemTargetsMetInventory(mod, this.itemTargets)) {
         this.setDebugState("Strict inventory gate met; finishing (no over-mining).");
         return null;
      }

      // Sufficiency met via ground/in-flight drops while strict inventory is still short: do NOT mine
      // new blocks (that would over-mine). Tell the subtask to pursue drops only, so it actively collects
      // the already-counted drops instead of stalling or mining more.
      boolean sufficiencyViaDrops = StorageHelper.itemTargetsMetSufficiency(
         mod, mod.getPlayer().position(), mod.getModSettings().getAggregateCountDropRadius(), this.itemTargets);
      this.subtask.setCollectOnly(sufficiencyViaDrops);

      if (!StorageHelper.miningRequirementMet(mod, this.requirement)) {
         return new SatisfyMiningRequirementTask(this.requirement);
      } else {
         if (this.subtask.isMining()) {
            this.makeSureToolIsEquipped(mod);
         }

         return (Task)(this.subtask.wasWandering() && this.isInWrongDimension(mod) && !mod.getBlockScanner().anyFound(this.blocksToMine)
            ? this.getToCorrectDimensionTask(mod)
            : this.subtask);
      }
   }

   @Override
   protected void onResourceStop(PlayerEngineController mod, Task interruptTask) {
      mod.getBehaviour().pop();
   }

   @Override
   protected boolean isEqualResource(ResourceTask other) {
      return other instanceof MineAndCollectTask task ? Arrays.equals((Object[])task.blocksToMine, (Object[])this.blocksToMine) : false;
   }

   @Override
   protected String toDebugStringName() {
      return "Mine And Collect";
   }

   private void makeSureToolIsEquipped(PlayerEngineController mod) {
      if (this.cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
         assert this.controller.getPlayer() != null;

         ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot(this.controller);
         if (cursorStack != null && !cursorStack.isEmpty()) {
            Item item = cursorStack.getItem();
            if (item.isCorrectToolForDrops(cursorStack, mod.getWorld().getBlockState(this.subtask.miningPos()))) {
               Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot(mod.getInventory())).getItem();
               if (item instanceof DiggerItem) {
                  if (currentlyEquipped instanceof DiggerItem currentPick) {
                     DiggerItem swapPick = (DiggerItem)item;
                     if (ToolMaterialVer.getMiningLevel(swapPick) > ToolMaterialVer.getMiningLevel(currentPick)) {
                        mod.getSlotHandler().forceEquipSlot(this.controller, CursorSlot.SLOT);
                     }
                  } else {
                     mod.getSlotHandler().forceEquipSlot(this.controller, CursorSlot.SLOT);
                  }
               }
            }
         }

         this.cursorStackTimer.reset();
      }
   }

   public static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {
      private final Block[] blocks;
      private final ItemTarget[] targets;
      private final Set<BlockPos> blacklist = new HashSet<>();
      private final MovementProgressChecker progressChecker = new MovementProgressChecker();
      private final Task pickupTask;
      private BlockPos miningPos;
      // Settle wait (plan WS3 item c): after a block is mined, give its drop time to settle before we
      // re-evaluate "still short" in getWanderTask, so a just-mined drop is credited toward the
      // sufficiency axis and the bot does not over-mine / wander in the intervening ticks. Interval is
      // refreshed from config (mineCollectSettleSeconds) at start.
      private final TimerGame settleTimer = new TimerGame(1.0);
      // Collect-only mode (plan WS3, reviewer Option A): set by the parent when the SUFFICIENCY axis is
      // already covered (inventory + nearby drops) but strict inventory is still short. While true the
      // subtask must NOT target new blocks to mine -- it pursues drops only, so it collects the
      // already-counted drops instead of over-mining. Cleared again once inventory catches up.
      private boolean collectOnly;

      public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
         this.blocks = blocks;
         this.targets = targets;
         this.pickupTask = new PickupDroppedItemTask(targets, true);
      }

      @Override
      protected Vec3 getPos(PlayerEngineController mod, Object obj) {
         if (obj instanceof BlockPos b) {
            return WorldHelper.toVec3d(b);
         } else if (obj instanceof ItemEntity item) {
            return item.position();
         } else {
            throw new UnsupportedOperationException(
               "Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)")
            );
         }
      }

      @Override
      protected Optional<Object> getClosestTo(PlayerEngineController mod, Vec3 pos) {
         Tuple<Double, Optional<ItemEntity>> closestDrop = getClosestItemDrop(mod, pos, this.targets);
         // Collect-only: sufficiency already covered via drops but inventory short -> never target a new
         // block to mine (anti-over-mining); pursue the already-counted drops only.
         if (this.collectOnly) {
            return ((Optional)closestDrop.getB()).map(Object.class::cast);
         }

         Tuple<Double, Optional<BlockPos>> closestBlock = getClosestBlock(mod, pos, this.blocks);
         double blockSq = (Double)closestBlock.getA();
         double dropSq = (Double)closestDrop.getA();
         if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
            return ((Optional)closestDrop.getB()).map(Object.class::cast);
         } else {
            return dropSq <= blockSq ? ((Optional)closestDrop.getB()).map(Object.class::cast) : ((Optional)closestBlock.getB()).map(Object.class::cast);
         }
      }

      public static Tuple<Double, Optional<ItemEntity>> getClosestItemDrop(PlayerEngineController mod, Vec3 pos, ItemTarget... items) {
         Optional<ItemEntity> closestDrop = Optional.empty();
         if (mod.getEntityTracker().itemDropped(items)) {
            closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, items);
         }

         return new Tuple(closestDrop.<Double>map(itemEntity -> itemEntity.distanceToSqr(pos) + 10.0).orElse(Double.POSITIVE_INFINITY), closestDrop);
      }

      public static Tuple<Double, Optional<BlockPos>> getClosestBlock(PlayerEngineController mod, Vec3 pos, Block... blocks) {
         Optional<BlockPos> closestBlock = mod.getBlockScanner()
            .getNearestBlock(pos, check -> mod.getBlockScanner().isUnreachable(check) ? false : WorldHelper.canBreak(mod, check), blocks);
         return new Tuple(closestBlock.<Double>map(blockPos -> BlockPosVer.getSquaredDistance(blockPos, pos)).orElse(Double.POSITIVE_INFINITY), closestBlock);
      }

      @Override
      protected Vec3 getOriginPos(PlayerEngineController mod) {
         return mod.getPlayer().position();
      }

      @Override
      protected Task onTick() {
         PlayerEngineController mod = this.controller;
         if (mod.getBaritone().getPathingBehavior().isPathing()) {
            this.progressChecker.reset();
         }

         if (this.miningPos != null && !this.progressChecker.check(mod)) {
            mod.getBaritone().getPathingBehavior().forceCancel();
            Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
            mod.getBlockScanner().requestBlockUnreachable(this.miningPos, 2);
            this.blacklist.add(this.miningPos);
            this.miningPos = null;
            this.progressChecker.reset();
         }

         return super.onTick();
      }

      @Override
      protected Task getGoalTask(Object obj) {
         if (!(obj instanceof BlockPos newPos)) {
            if (obj instanceof ItemEntity) {
               this.miningPos = null;
               return this.pickupTask;
            } else {
               throw new UnsupportedOperationException(
                  "Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)")
               );
            }
         } else {
            if (this.miningPos == null || !this.miningPos.equals(newPos)) {
               this.progressChecker.reset();
            }

            this.miningPos = newPos;
            // Block (re)targeted for mining: start the settle window so the resulting drop has time to
            // settle before the next getWanderTask "still short" re-check (plan WS3 item c).
            this.settleTimer.reset();
            return new DestroyBlockTask(this.miningPos);
         }
      }

      @Override
      protected boolean isValid(PlayerEngineController mod, Object obj) {
         if (obj instanceof BlockPos b) {
            return mod.getBlockScanner().isBlockAtPosition(b, this.blocks) && WorldHelper.canBreak(this.controller, b);
         } else if (!(obj instanceof ItemEntity drop)) {
            return false;
         } else {
            Item item = drop.getItem().getItem();
            if (this.targets != null) {
               for (ItemTarget target : this.targets) {
                  if (target.matches(item)) {
                     return true;
                  }
               }
            }

            return false;
         }
      }

      @Override
      protected void onStart() {
         this.progressChecker.reset();
         this.miningPos = null;
         this.settleTimer.setInterval(this.controller.getModSettings().getMineCollectSettleSeconds());
         // Start "settled" so an immediate first wander check (nothing mined yet) is not blocked.
         this.settleTimer.forceElapse();
      }

      /**
       * SOURCE-ROUTING axis override (plan WS3, decision 11) &mdash; consulted only when the base
       * {@link AbstractDoToClosestObjectTask} has no reachable block AND no drop to pursue and is about
       * to wander. Before returning the (now bounded) wander we ask whether a reachable LOCAL source
       * could still cover the remaining shortfall, so a partly-depleted nearby tree + logs in inventory
       * mines the remaining nearby logs and collects its own drops instead of wandering off for a brand
       * new far source (the original bug). Order:
       * <ol>
       *   <li>Settle wait &mdash; if a block was just mined and its drop has not settled yet, wait (return
       *       {@code null}) so the drop is credited before we decide; avoids a spurious wander.</li>
       *   <li>Eligible nearby drops &mdash; prefer collecting them first (route to the pickup path).</li>
       *   <li>Reachable local mineable source can cover the remainder &mdash; keep mining locally
       *       (return {@code null} so the next tick re-scans the local source) rather than wandering.</li>
       *   <li>Otherwise &mdash; no local source can cover the remainder: return the bounded wander (WS4).</li>
       * </ol>
       * The base {@link AbstractDoToClosestObjectTask#getWanderTask} stays infinite for exploration
       * callers; only this discrete subclass is bounded.
       */
      @Override
      protected Task getWanderTask(PlayerEngineController mod) {
         double deadlineSeconds = mod.getModSettings().getWanderBoundDefaultSeconds();
         long deadlineMs = (long)(deadlineSeconds * 1000.0);
         Task boundedWander = deadlineMs > 0L ? TimeoutWanderTask.bounded(deadlineMs) : new TimeoutWanderTask(true);

         if (this.targets == null || this.targets.length == 0) {
            return boundedWander;
         }

         // (c) Settle wait: a just-mined drop may not be counted yet; wait it out before deciding.
         if (!this.settleTimer.elapsed()) {
            this.setDebugState("Waiting for just-mined drop to settle before re-checking 'still short'.");
            return null;
         }

         Vec3 origin = mod.getPlayer().position();
         double dropRadius = mod.getModSettings().getAggregateCountDropRadius();
         double localSourceBlockRadius = mod.getModSettings().getAggregateLocalSourceBlockRadius();

         // (b1) Prefer collecting eligible nearby drops over wandering (plan decision 7).
         if (mod.getEntityTracker().itemDropped(this.targets)
            && mod.getEntityTracker().getClosestItemDrop(origin, this.targets).isPresent()) {
            this.setDebugState("Eligible nearby drops present; collecting before any new-source wander.");
            return this.pickupTask;
         }

         // (b2) Reachable local mineable source can still cover the remainder: keep mining locally.
         // Skipped in collect-only mode: sufficiency is already covered via drops, so we must not start
         // mining a new local source (that would over-mine) -- we only collect the counted drops.
         if (!this.collectOnly) {
         for (ItemTarget target : this.targets) {
            if (ItemTarget.nullOrEmpty(target)) {
               continue;
            }
            if (MaterialAvailability.localSourceCanCoverRemainder(
                  mod, target, origin, dropRadius, localSourceBlockRadius)) {
               this.setDebugState("Local source can cover remainder; mining locally instead of wandering.");
               // Return null so AbstractDoToClosestObjectTask re-scans the local source next tick rather
               // than committing to a far-source wander. The mineable yield only steers routing here; it
               // never counts toward sufficiency / "done".
               return null;
            }
         }
         }

         // (b3) No local source (drops or reachable blocks) can cover the remainder: bounded wander.
         this.setDebugState("No local source can cover remainder; bounded wander for a new source.");
         return boundedWander;
      }

      @Override
      protected void onStop(Task interruptTask) {
      }

      @Override
      protected boolean isEqual(Task other) {
         return !(other instanceof MineAndCollectTask.MineOrCollectTask task)
            ? false
            : Arrays.equals((Object[])task.blocks, (Object[])this.blocks) && Arrays.equals((Object[])task.targets, (Object[])this.targets);
      }

      @Override
      protected String toDebugString() {
         return "Mining or Collecting";
      }

      public boolean isMining() {
         return this.miningPos != null;
      }

      /**
       * Set by the parent {@link MineAndCollectTask} each tick: true once the SUFFICIENCY axis is covered
       * (inventory + nearby drops) but strict inventory is still short. While true, the subtask targets
       * drops only and never mines a new block, so it collects the already-counted drops instead of
       * over-mining. False restores normal mine-or-collect routing.
       */
      public void setCollectOnly(boolean collectOnly) {
         this.collectOnly = collectOnly;
      }

      public BlockPos miningPos() {
         return this.miningPos;
      }
   }
}
