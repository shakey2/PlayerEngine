package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.base.Task;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.level.ChunkPos;

public abstract class SearchChunksExploreTask extends Task {
   private final Object searcherMutex = new Object();
   private final Set<ChunkPos> alreadyExplored = new HashSet<>();
   private ChunkSearchTask searcher;
   private java.util.function.Consumer<ChunkPos> chunkLoadCallback;

   protected ChunkPos getBestChunkOverride(PlayerEngineController mod, List<ChunkPos> chunks) {
      return null;
   }

   @Override
   protected void onStart() {
      this.chunkLoadCallback = this::onChunkLoad;
      this.controller.getChunkTracker().addLoadCallback(this.chunkLoadCallback);
      this.resetSearch();
   }

   @Override
   protected Task onTick() {
      synchronized (this.searcherMutex) {
         if (this.searcher == null) {
            this.setDebugState("Exploring/Searching for valid chunk");
            return this.getWanderTask();
         } else {
            if (this.searcher.isActive() && this.searcher.isFinished()) {
               Debug.logWarning("Target object search failed.");
               this.alreadyExplored.addAll(this.searcher.getSearchedChunks());
               this.searcher = null;
            } else if (this.searcher.finished()) {
               this.setDebugState("Searching for target object...");
               Debug.logMessage("Search finished.");
               this.alreadyExplored.addAll(this.searcher.getSearchedChunks());
               this.searcher = null;
            }

            this.setDebugState("Searching within chunks...");
            return this.searcher;
         }
      }
   }

   @Override
   protected void onStop(Task interruptTask) {
      if (this.chunkLoadCallback != null) {
         this.controller.getChunkTracker().removeLoadCallback(this.chunkLoadCallback);
         this.chunkLoadCallback = null;
      }
   }

   private void onChunkLoad(ChunkPos pos) {
      if (this.searcher == null) {
         if (this.isActive()) {
            if (this.isChunkWithinSearchSpace(this.controller, pos)) {
               synchronized (this.searcherMutex) {
                  if (!this.alreadyExplored.contains(pos)) {
                     Debug.logMessage("New searcher: " + pos);
                     this.searcher = new SearchChunksExploreTask.SearchSubTask(pos);
                  }
               }
            }
         }
      }
   }

   protected Task getWanderTask() {
      return new TimeoutWanderTask(true); // EXPLORATION
   }

   public boolean failedSearch() {
      return this.searcher == null;
   }

   public void resetSearch() {
      this.searcher = null;
      this.alreadyExplored.clear();

      for (ChunkPos start : this.controller.getChunkTracker().getLoadedChunks()) {
         this.onChunkLoad(start);
      }
   }

   protected abstract boolean isChunkWithinSearchSpace(PlayerEngineController var1, ChunkPos var2);

   class SearchSubTask extends ChunkSearchTask {
      public SearchSubTask(ChunkPos start) {
         super(start);
      }

      @Override
      protected boolean isChunkPartOfSearchSpace(PlayerEngineController mod, ChunkPos pos) {
         return SearchChunksExploreTask.this.isChunkWithinSearchSpace(mod, pos);
      }

      @Override
      public ChunkPos getBestChunk(PlayerEngineController mod, List<ChunkPos> chunks) {
         ChunkPos override = SearchChunksExploreTask.this.getBestChunkOverride(mod, chunks);
         return override != null ? override : super.getBestChunk(mod, chunks);
      }

      @Override
      protected boolean isChunkSearchEqual(ChunkSearchTask other) {
         return other == this;
      }

      @Override
      protected String toDebugString() {
         return "Searching chunks...";
      }
   }
}
