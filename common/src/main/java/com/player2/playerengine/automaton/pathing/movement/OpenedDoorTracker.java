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

import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.utils.IEntityContext;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Per-bot record of wooden doors that THIS bot opened while pathing, so they can be closed once the
 * bot has moved through and beyond them. We only ever track a door that was found closed (blocking)
 * at the moment the bot decided to open it (see {@link MovementTraverse#tryOpenDoors}); doors a player
 * left open are never recorded, so we never "tidy up" after the player. Closing is gated behind
 * {@link com.player2.playerengine.automaton.api.Settings#closeDoorsBehindBot} and is best-effort:
 * abandoned paths, teleports, or unloaded chunks simply drop the record without throwing.
 *
 * <p>Server thread only — {@link IEntityContext#world()} is a {@link ServerLevel} and every mutation
 * here runs from the movement tick on the server. Nothing here is model-facing.
 */
public final class OpenedDoorTracker {
   // Hard cap so a long, door-heavy path can never grow this unbounded.
   private static final int MAX_TRACKED = 64;

   // One tracker per Baritone instance; identity-keyed and pruned when the bot's entity is gone.
   private static final Map<IBaritone, OpenedDoorTracker> INSTANCES = new java.util.WeakHashMap<>();

   // Insertion-ordered so the oldest opened door is evicted first if we ever hit the cap.
   private final Map<TrackedDoor, Boolean> doors = new LinkedHashMap<>();

   public static OpenedDoorTracker forBaritone(IBaritone baritone) {
      synchronized (INSTANCES) {
         return INSTANCES.computeIfAbsent(baritone, b -> new OpenedDoorTracker());
      }
   }

   /**
    * Record that the bot just opened a wooden door at {@code pos}. Caller must have already confirmed
    * the door was closed/blocking (i.e. the bot is the opener). Idempotent per position+dimension.
    */
   public void recordOpened(ServerLevel level, BlockPos pos) {
      TrackedDoor key = new TrackedDoor(level.dimension(), pos.immutable());
      if (this.doors.containsKey(key)) {
         return;
      }
      if (this.doors.size() >= MAX_TRACKED) {
         Iterator<TrackedDoor> it = this.doors.keySet().iterator();
         if (it.hasNext()) {
            it.next();
            it.remove();
         }
      }
      this.doors.put(key, Boolean.TRUE);
   }

   /**
    * Close any tracked door the bot has fully cleared. A door is closed only when: the bot no longer
    * occupies it and isn't about to need it ({@code occupiedOrNeeded} excludes it), it is still a
    * wooden door, it is still open, it is not redstone-powered, and no living entity is standing in
    * either half. Doors in another dimension than the bot, or whose chunk is unloaded, are dropped.
    */
   public void closeClearedDoors(IEntityContext ctx, java.util.Set<BlockPos> occupiedOrNeeded) {
      if (this.doors.isEmpty()) {
         return;
      }
      ServerLevel level = ctx.world();
      ResourceKey<Level> here = level.dimension();
      Iterator<Map.Entry<TrackedDoor, Boolean>> it = this.doors.entrySet().iterator();
      while (it.hasNext()) {
         TrackedDoor door = it.next().getKey();
         // Different dimension (bot teleported / changed worlds) — we cannot act on it; drop it.
         if (!door.dimension.equals(here)) {
            it.remove();
            continue;
         }
         BlockPos pos = door.pos;
         // Still needed by the active path or the bot is still in/at it — leave it open for now.
         if (occupiedOrNeeded.contains(pos) || occupiedOrNeeded.contains(pos.above()) || occupiedOrNeeded.contains(pos.below())) {
            continue;
         }
         // Chunk unloaded — can't safely touch it; drop the record rather than force-load.
         if (!level.hasChunkAt(pos)) {
            it.remove();
            continue;
         }
         BlockState state = level.getBlockState(pos);
         if (!(state.getBlock() instanceof DoorBlock door1) || !DoorBlock.isWoodenDoor(state)) {
            // No longer the wooden door we opened (broken/replaced) — forget it.
            it.remove();
            continue;
         }
         if (!state.getValue(DoorBlock.OPEN)) {
            // Already closed (player or redstone closed it) — nothing to do, forget it.
            it.remove();
            continue;
         }
         if (state.getValue(DoorBlock.POWERED)) {
            // Held open by redstone; closing would just fight the circuit. Leave and forget.
            it.remove();
            continue;
         }
         if (entityStandingIn(level, pos)) {
            // Someone (player/mob) is in the doorway — never close on them. Try again next sweep.
            continue;
         }
         // setOpen handles setBlock + the door-close sound + BLOCK_CLOSE game event, and no-ops if the
         // state already matches. setOpen writes only the recorded half directly; OPEN syncs to the other
         // half via the block-update neighbor cascade (the `10` flag -> neighborChanged), the same path a
         // vanilla player click uses.
         door1.setOpen(ctx.entity(), level, state, pos, false);
         it.remove();
      }
   }

   /** Forget everything (e.g. path fully finished or cancelled). */
   public void clear() {
      this.doors.clear();
   }

   private static boolean entityStandingIn(ServerLevel level, BlockPos pos) {
      AABB box = new AABB(pos).expandTowards(0.0, 1.0, 0.0); // both halves of the door
      for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
         return true;
      }
      return false;
   }

   private static final class TrackedDoor {
      private final ResourceKey<Level> dimension;
      private final BlockPos pos;

      private TrackedDoor(ResourceKey<Level> dimension, BlockPos pos) {
         this.dimension = dimension;
         this.pos = pos;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof TrackedDoor other)) {
            return false;
         }
         return this.dimension.equals(other.dimension) && this.pos.equals(other.pos);
      }

      @Override
      public int hashCode() {
         return 31 * this.dimension.hashCode() + this.pos.hashCode();
      }
   }
}
