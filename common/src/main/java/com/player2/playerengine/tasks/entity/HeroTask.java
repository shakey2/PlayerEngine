package com.player2.playerengine.tasks.entity;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.movement.GetToEntityTask;
import com.player2.playerengine.tasks.movement.PickupDroppedItemTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.tasks.resources.KillAndLootTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.EntityHelper;
import com.player2.playerengine.util.helpers.ItemHelper;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.Vec3;

public class HeroTask extends Task {
   // TODO: promote to ModSettings if operators need to tune the patrol radius.
   private static final double HERO_DEFENSE_RADIUS = 16.0; // blocks, 3D from the bot

   /** Baritone allowBreak value captured in onStart and restored in onStop (hard no-dig guarantee). */
   private boolean savedAllowBreak;

   @Override
   protected void onStart() {
      // Hard no-dig guarantee: pursuit must never break blocks to reach a mob. An underground/walled
      // hostile yields no path and is ignored. Capture the prior value (don't assume it was true) and
      // restore it in onStop. allowBreak is re-read per path calc, so setting it before any pursuit
      // path is dispatched is sufficient. (Also self-cancels any MineProcess — harmless; hero never mines.)
      this.savedAllowBreak = this.controller.getBaritoneSettings().allowBreak.get();
      this.controller.getBaritoneSettings().allowBreak.set(false);
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      if (mod.getFoodChain().needsToEat()) {
         this.setDebugState("Eat first.");
         return null;
      } else {
         Optional<Entity> experienceOrb = mod.getEntityTracker().getClosestEntity(ExperienceOrb.class);
         if (experienceOrb.isPresent()) {
            this.setDebugState("Getting experience.");
            return new GetToEntityTask(experienceOrb.get());
         } else {
            // Bounded selection pre-filter, bot-anchored. No depth term: allowBreak=false (onStart) is
            // the real no-dig guarantee, so a mob slightly below on open walkable ground stays a valid
            // target while an underground/walled one (any direction) is unreachable and ignored at the
            // pathing layer.
            Vec3 anchor = mod.getPlayer().position();
            Optional<Entity> localHostile = mod.getEntityTracker().getClosestEntity(
               anchor,
               e -> (e instanceof Monster || e instanceof Slime)
                    && !EntityHelper.isZombifiedPiglinFamily(e)
                    && e.closerThan(mod.getPlayer(), HERO_DEFENSE_RADIUS),
               Monster.class, Slime.class);
            if (localHostile.isPresent()) {
               this.setDebugState("Killing nearby hostiles or picking hostile drops.");
               return new KillAndLootTask(localHostile.get().getClass(), new ItemTarget(ItemHelper.HOSTILE_MOB_DROPS));
            }

            if (mod.getEntityTracker().itemDropped(ItemHelper.HOSTILE_MOB_DROPS)) {
               this.setDebugState("Picking hostile drops.");
               return new PickupDroppedItemTask(new ItemTarget(ItemHelper.HOSTILE_MOB_DROPS), true);
            } else {
               this.setDebugState("Searching for hostile mobs.");
               return TimeoutWanderTask.bounded(mod.getModSettings().getWanderBoundDefaultSeconds() * 1000L); // DISCRETE_RESOURCE
            }
         }
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
      // WS1: restore the no-dig lever to its pre-hero value (always, on any stop). onStop always runs
      // after onStart (the Task base gates onStop on !first), so savedAllowBreak is always initialized.
      this.controller.getBaritoneSettings().allowBreak.set(this.savedAllowBreak);

      // NOTE: no StopReason arming here. onStop also fires on the routine interrupt/chain-preemption
      // path (Task.interrupt(null) -> onStop(null)) whenever a higher-priority chain (food, defense,
      // MLG, unstuck) preempts the UserTaskChain — arming on interruptTask==null there would poison
      // pendingFinishReason with CANCELLED_OPERATOR and mislabel a later genuine terminal. Every real
      // stop path already arms the correct StopReason upstream via PlayerEngineController.stop(reason)
      // (first-writer-wins), exactly as FollowPlayerTask relies on. Hero adds no self-stop signal, so
      // it needs no onStop arm.
   }

   @Override
   protected boolean isEqual(Task other) {
      return other instanceof HeroTask;
   }

   @Override
   protected String toDebugString() {
      return "Killing all hostile mobs.";
   }
}
