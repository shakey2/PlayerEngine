package com.player2.playerengine.tasks.base;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import java.util.function.Predicate;

public abstract class Task {
   public PlayerEngineController controller;
   private String oldDebugState = "";
   private String debugState = "";
   private Task sub = null;
   private boolean first = true;
   private boolean stopped = false;
   private boolean active = false;
   /** Installed in a chain but not yet given its first tick. */
   private boolean assigned = false;
   private boolean transientlySuspended = false;

   public void tick(TaskChain parentChain) {
      this.controller = parentChain.controller;
      parentChain.addTaskToChain(this);
      if (this.first) {
         Debug.logInternal("Task START: " + this);
         this.active = true;
         this.assigned = false;
         this.onStart();
         this.first = false;
         this.stopped = false;
      }

      if (!this.stopped) {
         Task newSub = this.onTick();
         if (!this.oldDebugState.equals(this.debugState)) {
            Debug.logInternal(this.toString());
            this.oldDebugState = this.debugState;
         }

         if (newSub != null) {
            if (!newSub.isEqual(this.sub) && this.canBeInterrupted(this.sub, newSub)) {
               if (this.sub != null) {
                  this.sub.stop(newSub);
               }

               this.sub = newSub;
            }

            this.sub.tick(parentChain);
         } else if (this.sub != null && this.canBeInterrupted(this.sub, null)) {
            this.sub.stop();
            this.sub = null;
         }
      }
   }

   public void reset() {
      this.first = true;
      this.active = false;
      this.stopped = false;
      this.assigned = true;
      this.transientlySuspended = false;
   }

   public void stop() {
      this.stop(null);
   }

   public void stop(Task interruptTask) {
      if ((this.active || this.assigned) && !this.stopped) {
         Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
         if ((this.transientlySuspended || this.assigned)
               && this instanceof TransientlyResumableTask resumable) {
            resumable.onTransientResumeAbandoned();
         } else if (!this.first) {
            this.onStop(interruptTask);
         }

         if (this.sub != null && !this.sub.stopped()) {
            this.sub.stop(interruptTask);
         }
         if (this instanceof TransientlyResumableTask resumable) {
            resumable.afterChildrenStopped();
         }

         this.first = true;
         this.active = false;
         this.assigned = false;
         this.stopped = true;
         this.transientlySuspended = false;
      }
   }

   public void fail(String reason) {
      this.stop();
      Debug.logMessage("Task FAILED: " + reason);
   }

   public void interrupt(Task interruptTask) {
      this.interrupt(interruptTask, TaskSuspensionCause.HIGHER_PRIORITY_CHAIN);
   }

   /**
    * Interrupts execution for a scheduler-owned transient pause. Resumable tasks receive their
    * checkpoint hook instead of the terminal {@link #onStop(Task)} hook; all legacy tasks retain the
    * historical interrupt behavior.
    */
   public void interrupt(Task interruptTask, TaskSuspensionCause cause) {
      if (this.active) {
         boolean checkpointDeclined = false;
         boolean resumableCandidate = false;
         if (!this.first) {
            boolean prepared = false;
            resumableCandidate = !this.isFinished()
                  && this instanceof TransientlyResumableTask;
            if (resumableCandidate) {
               TransientlyResumableTask resumable = (TransientlyResumableTask)this;
               prepared = resumable.prepareForTransientResume(
                     java.util.Objects.requireNonNull(cause, "cause"));
            }
            this.transientlySuspended = prepared && !this.isFinished();
            if (!this.transientlySuspended && !resumableCandidate) {
               this.onStop(interruptTask);
            }
            checkpointDeclined = resumableCandidate && !this.transientlySuspended;
         }

         if (this.sub != null && !this.sub.stopped()) {
            this.sub.interrupt(interruptTask, cause);
         }
         if (this.transientlySuspended || checkpointDeclined) {
            // The root checkpoint owns reconstruction from live state. Never retain an in-flight
            // child which may compare equal to a fresh child while carrying stale attempt state.
            if (this.sub != null && !this.sub.stopped()) {
               // interrupt() has already delivered the child's cleanup hook. stop() now only seals
               // its framework lifecycle so the detached child cannot remain logically active.
               this.sub.stop(interruptTask);
            }
            this.sub = null;
         }
         if (checkpointDeclined) {
            // Typed tasks that decline a checkpoint are terminalized only after child LIFO cleanup.
            this.onStop(interruptTask);
         }
         if (this instanceof TransientlyResumableTask resumable) {
            resumable.afterChildrenStopped();
         }

         this.first = true;
         if (checkpointDeclined) {
            // A task that explicitly implements the checkpoint contract but declines this pause is
            // fail-clean, not restartable. The chain reaps it when it next wins priority.
            this.active = false;
            this.assigned = false;
            this.stopped = true;
         }
      }
   }

   /** True only between a successful transient checkpoint and reset/resume or terminal discard. */
   public boolean isTransientlySuspended() {
      return this.transientlySuspended;
   }

   /** Discards a task which was detached for a transient overlay without losing its terminal hook. */
   public void abandonTransientResume() {
      if (this.transientlySuspended) {
         this.stop(null);
      }
   }

   protected void setDebugState(String state) {
      if (state == null) {
         state = "";
      }

      this.debugState = state;
   }

   public boolean isFinished() {
      return false;
   }

   public boolean isActive() {
      return this.active;
   }

   /** True after chain installation and before the task's first tick. */
   public boolean isAssigned() {
      return this.assigned;
   }

   public boolean stopped() {
      return this.stopped;
   }

   // Read-only: for stopped-child inspection in subclasses only. Do not use for sub-task replacement.
   protected Task getSub() {
      return this.sub;
   }

   protected abstract void onStart();

   protected abstract Task onTick();

   protected abstract void onStop(Task var1);

   protected abstract boolean isEqual(Task var1);

   protected abstract String toDebugString();

   @Override
   public String toString() {
      return "<" + this.toDebugString() + "> " + this.debugState;
   }

   @Override
   public boolean equals(Object obj) {
      return obj instanceof Task task ? this.isEqual(task) : false;
   }

   public boolean thisOrChildSatisfies(Predicate<Task> pred) {
      for (Task t = this; t != null; t = t.sub) {
         if (pred.test(t)) {
            return true;
         }
      }

      return false;
   }

   public boolean containsTask(Predicate<Task> pred) {
      return this.thisOrChildSatisfies(pred);
   }

   public boolean thisOrChildAreTimedOut() {
      return this.thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
   }

   private boolean canBeInterrupted(Task subTask, Task toInterruptWith) {
      return subTask == null ? true : subTask.thisOrChildSatisfies(task -> {
         if (task instanceof ITaskCanForce canForce) {
            if (toInterruptWith != null && toInterruptWith.controller == null) {
               toInterruptWith.controller = this.controller;
            }

            return !canForce.shouldForce(toInterruptWith);
         } else {
            return true;
         }
      });
   }

   public String getTaskTree() {
      StringBuilder builder = new StringBuilder("Main task:\n");
      Task cur = this;

      while (cur != null) {
         builder.append(cur.toDebugString());
         cur = cur.sub;
         if (cur != null) {
            builder.append("\nFor that doing:\n");
         }
      }

      return builder.toString();
   }
}
