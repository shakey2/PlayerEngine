package com.player2.playerengine.trackers;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.player2.playerengine.automaton.utils.accessor.ServerChunkManagerAccessor;
import dev.architectury.event.events.common.ChunkEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public class SimpleChunkTracker {
   private final PlayerEngineController mod;
   private final Set<ChunkPos> loaded = new HashSet<>();
   private final List<Consumer<ChunkPos>> loadCallbacks = new ArrayList<>();

   public SimpleChunkTracker(PlayerEngineController mod) {
      this.mod = mod;
      ChunkEvent.LOAD_DATA.register((chunk, level, data) -> this.onLoad(chunk.getPos()));
      ChunkEvent.SAVE_DATA.register((chunk, level, data) -> this.onUnload(chunk.getPos()));
   }

   /** In-process chunk-load notifications (safe during gameplay; does not touch Architectury's listener list). */
   public void addLoadCallback(Consumer<ChunkPos> callback) {
      this.loadCallbacks.add(callback);
   }

   public void removeLoadCallback(Consumer<ChunkPos> callback) {
      this.loadCallbacks.remove(callback);
   }

   private void onLoad(ChunkPos pos) {
      this.loaded.add(pos);
      if (!this.loadCallbacks.isEmpty()) {
         for (Consumer<ChunkPos> callback : List.copyOf(this.loadCallbacks)) {
            callback.accept(pos);
         }
      }
   }

   private void onUnload(ChunkPos pos) {
      this.loaded.remove(pos);
   }

   /**
    * Whether a chunk is ALREADY in memory, via a live, NON-LOADING chunk-source query.
    *
    * <p>Issue C fix (preserved): this must never call {@code Level.getChunk(pos.x, pos.z)}, which is
    * {@code getChunk(x, z, ChunkStatus.FULL, nonnull=true)} — a SYNCHRONOUS chunk LOAD/GENERATION on
    * the server thread (ServerChunkCache.getChunkFutureMainThread -> managedBlock). When used as a
    * guard inside a per-candidate hot loop (e.g. ChestPlacementSelector's 1445-candidate scan near
    * unloaded-chunk boundaries) it WAS the multi-second server-thread stall it was meant to prevent.
    *
    * <p>The previous Issue C implementation checked membership in the event-maintained {@link #loaded}
    * set, but the Architectury ChunkEvent LOAD_DATA/SAVE_DATA signals are wrong for "currently in
    * memory": LOAD_DATA never fires for freshly GENERATED chunks (only disk deserialization), chunks
    * loaded before this tracker's construction are never added, and SAVE_DATA fires on every
    * world save for chunks that REMAIN loaded — so the set was empty/stale and every chunk-gated
    * feature saw the world as unloaded (the "chunk_unloaded x1445 / no_valid_site" failure).
    *
    * <p>{@code ServerChunkCache.hasChunk(x, z)} is a pure {@code getVisibleChunkIfPresent} holder
    * lookup against ChunkStatus.FULL — it NEVER loads or generates a chunk — so it is both
    * authoritative and safe in hot loops.
    */
   public boolean isChunkLoaded(ChunkPos pos) {
      var world = this.mod.getWorld();
      return world != null && world.getChunkSource().hasChunk(pos.x, pos.z);
   }

   public boolean isChunkLoaded(BlockPos pos) {
      return this.isChunkLoaded(new ChunkPos(pos));
   }

   /**
    * Whether the chunk containing {@code pos} is at BLOCK-TICKING level — i.e. a furnace (or any
    * other block entity) at that position will actually progress this tick.
    *
    * <p>Uses {@link ServerChunkManagerAccessor#automatone$getChunkNow(int, int)}, which delegates to
    * {@code chunkHolder.getTickingChunk()}. Non-null means the chunk is at EntityTicking/BlockTicking
    * level; null means it is absent or not yet ticking.
    *
    * <p>This is a CHECKS-ONLY call — it never generates, loads, or force-loads any chunk.
    * It is hot-loop safe (pure holder map lookup, same as {@link #isChunkLoaded}).
    *
    * <p>NOT the same as {@link #isChunkLoaded}: a chunk can be visible/present (isChunkLoaded=true)
    * without being at block-ticking level. Only isChunkSimulated=true guarantees furnace progress.
    */
   public boolean isChunkSimulated(ChunkPos pos) {
      var world = this.mod.getWorld();
      if (world == null) return false;
      var src = world.getChunkSource();
      if (!(src instanceof ServerChunkManagerAccessor accessor)) return false;
      return accessor.automatone$getChunkNow(pos.x, pos.z) != null;
   }

   public boolean isChunkSimulated(BlockPos pos) {
      return this.isChunkSimulated(new ChunkPos(pos));
   }

   public List<ChunkPos> getLoadedChunks() {
      List<ChunkPos> result = new ArrayList<>(this.loaded);
      return result.stream().filter(this::isChunkLoaded).distinct().collect(Collectors.toList());
   }

   public boolean scanChunk(ChunkPos chunk, Predicate<BlockPos> onBlockStop) {
      if (!this.isChunkLoaded(chunk)) {
         return false;
      } else {
         int bottomY = this.mod.getWorld().getMinBuildHeight();
         int topY = this.mod.getWorld().getMaxBuildHeight();

         for (int xx = chunk.getMinBlockX(); xx <= chunk.getMaxBlockX(); xx++) {
            for (int yy = bottomY; yy <= topY; yy++) {
               for (int zz = chunk.getMinBlockZ(); zz <= chunk.getMaxBlockZ(); zz++) {
                  if (onBlockStop.test(new BlockPos(xx, yy, zz))) {
                     return true;
                  }
               }
            }
         }

         return false;
      }
   }

   public void scanChunk(ChunkPos chunk, Consumer<BlockPos> onBlock) {
      this.scanChunk(chunk, block -> {
         onBlock.accept(block);
         return false;
      });
   }

   public void reset(PlayerEngineController mod) {
      Debug.logInternal("CHUNKS RESET");
      this.loaded.clear();
   }
}
