package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskChain;
import com.player2.playerengine.tasks.base.TaskRunner;

public abstract class SingleTaskChain extends TaskChain {
   protected Task mainTask = null;
   private boolean interrupted = false;
   private final PlayerEngineController mod;

   public SingleTaskChain(TaskRunner runner) {
      super(runner);
      this.mod = runner.getMod();
   }

   @Override
   protected void onTick() {
      if (this.isActive()) {
          if (this.interrupted) {
             this.interrupted = false;
             if (this.mainTask != null
                   && !this.mainTask.stopped()
                   && !this.mainTask.isFinished()) {
                this.mainTask.reset();
             }
         }

         if (this.mainTask != null) {
            if (this.mainTask.controller == null) {
               this.mainTask.controller = this.controller;
            }

            if (!this.mainTask.isFinished() && !this.mainTask.stopped()) {
               this.mainTask.tick(this);
            } else if (this.mainTask.isFinished() && !this.mainTask.stopped()) {
               this.mainTask.stop(null);
               this.onTaskFinish(this.mod);
            } else {
               this.onTaskFinish(this.mod);
            }
         }
      }
   }

   @Override
   protected void onStop() {
      if (this.isActive() && this.mainTask != null) {
         this.mainTask.stop();
         this.mainTask = null;
      }
   }

   public void setTask(Task task) {
      this.installTask(task, false);
   }

   /** Installs a fresh task even when its equality contract matches the current task. */
   protected void replaceTask(Task task) {
      this.installTask(task, true);
   }

   /**
    * Installs a short overlay after the current task has explicitly checkpointed itself through
    * {@link Task#interrupt(Task, com.player2.playerengine.tasks.base.TaskSuspensionCause)}. The old
    * task is retained by the caller and must not receive the terminal stop hook used by a genuine
    * replacement.
    */
   protected void replaceTaskAfterTransientSuspend(Task task) {
      if (this.mainTask == null
            || (!this.mainTask.isTransientlySuspended() && !this.mainTask.isAssigned())) {
         throw new IllegalStateException("transient overlay requires a prepared suspended task");
      }
      this.mainTask = task;
      if (task != null) {
         task.controller = this.controller;
         task.reset();
      }
   }

   private void installTask(Task task, boolean forceReplace) {
      if (this.mainTask != null && this.mainTask.controller == null) {
         this.mainTask.controller = this.controller;
      }

      boolean replace =
            forceReplace
                  || this.mainTask == null
                  || this.mainTask.isFinished()
                  || this.mainTask.stopped()
                  || !this.mainTask.equals(task);
      if (replace) {
         if (this.mainTask != null) {
            this.mainTask.stop(task);
         }

         this.mainTask = task;
         if (task != null) {
            task.controller = this.controller;
            task.reset();
         }
      }
   }

   @Override
   public boolean isActive() {
      return this.mainTask != null;
   }

   protected abstract void onTaskFinish(PlayerEngineController var1);

   @Override
   public void onInterrupt(TaskChain other) {
      if (other != null) {
         Debug.logInternal("Chain Interrupted: " + this + " by " + other);
      }

      this.interrupted = true;
      if (this.mainTask != null && this.mainTask.isActive()
            && !this.mainTask.isFinished()) {
         this.mainTask.interrupt(null);
      }
   }

   protected boolean isCurrentlyRunning(PlayerEngineController mod) {
      return !this.interrupted && this.mainTask.isActive() && !this.mainTask.isFinished();
   }

   public Task getCurrentTask() {
      return this.mainTask;
   }
}
