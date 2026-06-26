package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
import com.player2.playerengine.tasks.movement.BodyLanguageTask;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;
import com.player2.playerengine.util.time.Stopwatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UserTaskChain extends SingleTaskChain {
   private static final Logger LOGGER = LogManager.getLogger();

   private final Stopwatch taskStopwatch = new Stopwatch();
   private Runnable currentOnFinish = null;
   private boolean runningIdleTask;
   private boolean nextTaskIdleFlag;

   /**
    * A FollowPlayerTask suspended by a TRANSIENT body-language gesture (e.g. an inline {@code [bl:nod_head]}
    * marker or the {@code bodylang} command). A gesture is a short overlay, not a real replacement of the
    * follow: we stash the active follow here at gesture-start and resume it when the gesture finishes,
    * instead of idling. Any genuine non-gesture task that overwrites follow clears this (a real cancel).
    * Null when no follow is suspended. We re-run the same task object (it is {@code reset()} on re-assign);
    * if the target has since gone offline the resumed follow terminates gracefully on its own.
    */
   private FollowPlayerTask suspendedFollowTask = null;
   /** True while we are re-assigning {@link #suspendedFollowTask} so the resume path is not re-suspended. */
   private boolean resumingSuspendedFollow = false;

   public UserTaskChain(TaskRunner runner) {
      super(runner);
   }

   private static String prettyPrintTimeDuration(double seconds) {
      int minutes = (int)(seconds / 60.0);
      int hours = minutes / 60;
      int days = hours / 24;
      String result = "";
      if (days != 0) {
         result = result + result + " days ";
      }

      if (hours != 0) {
         result = result + result + " hours ";
      }

      if (minutes != 0) {
         result = result + result + " minutes ";
      }

      if (!result.isEmpty()) {
         result = result + "and ";
      }

      return result + result;
   }

   @Override
   protected void onTick() {
      if (PlayerEngineController.inGame()) {
         super.onTick();
      }
   }

   public void cancel(PlayerEngineController mod) {
      // An explicit cancel is a genuine stop of the user task — drop any follow suspended for a
      // transient gesture so it is not silently resumed after the user cancelled everything.
      this.suspendedFollowTask = null;
      if (this.mainTask != null && this.mainTask.isActive()) {
         this.stop();
         this.onTaskFinish(mod);
      }
   }

   @Override
   public float getPriority() {
      return 50.0F;
   }

   @Override
   public String getName() {
      return "User Tasks";
   }

   public void runTask(PlayerEngineController mod, Task task, Runnable onFinish) {
      this.runningIdleTask = this.nextTaskIdleFlag;
      this.nextTaskIdleFlag = false;
      this.currentOnFinish = onFinish;
      if (!this.runningIdleTask) {
         Debug.logMessage("User Task Set: " + task.toString());
      }

      boolean incomingIsFollow = task instanceof FollowPlayerTask;
      boolean incomingIsGesture = task instanceof BodyLanguageTask;
      // Captured BEFORE setTask() overwrites this.mainTask — the diag flag must reflect reality at
      // the moment of overwrite, not after the follow has already been cleared (the old bug logged
      // followWasActive=false at gesture-start because the overwrite ran first).
      boolean existingIsFollow = (this.mainTask instanceof FollowPlayerTask) && this.mainTask.isActive();
      FollowPlayerTask existingFollow = existingIsFollow ? (FollowPlayerTask) this.mainTask : null;

      // Transient-overlay rule: a body-language gesture overwriting an active follow SUSPENDS the
      // follow (to resume on gesture finish) rather than killing it. Any OTHER non-follow user task
      // (a genuine movement/agentic command) is a real replacement and clears any suspended follow.
      if (!this.resumingSuspendedFollow && !this.runningIdleTask) {
         if (incomingIsGesture && existingFollow != null) {
            this.suspendedFollowTask = existingFollow;
            LOGGER.info("[FollowDiag] FOLLOW-SUSPEND: active follow '{}' suspended by transient gesture '{}' (will resume on finish)",
                  existingFollow.toString(), task.toString());
         } else if (!incomingIsFollow && !incomingIsGesture) {
            // Genuine replacement task: cancel follow for good (matches pre-regression behaviour).
            if (this.suspendedFollowTask != null) {
               LOGGER.info("[FollowDiag] FOLLOW-SUSPEND-CLEAR: genuine task '{}' replaces follow; suspended follow discarded",
                     task.toString());
            }
            this.suspendedFollowTask = null;
         }
      }

      if (existingIsFollow && !this.runningIdleTask && !(incomingIsGesture && this.suspendedFollowTask != null)) {
         LOGGER.info("[FollowDiag] FOLLOW-OVERWRITE: active follow '{}' being replaced by '{}' (incoming isFollow={})",
               this.mainTask.toString(), task.toString(), incomingIsFollow);
      }
      if (incomingIsFollow && !this.runningIdleTask) {
         LOGGER.info("[FollowDiag] FOLLOW-START: FollowPlayerTask assigned to UserTaskChain (existingFollowActive={})",
               existingIsFollow);
      }

      mod.getTaskRunner().enable();
      this.taskStopwatch.begin();
      this.setTask(task);
      if (mod.getModSettings().failedToLoad()) {
         Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the file to re-load working settings.");
      }
   }

   @Override
   protected void onTaskFinish(PlayerEngineController mod) {
      boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
      double seconds = this.taskStopwatch.time();
      Task oldTask = this.mainTask;

      // Snapshot the transient-overlay resume intent BEFORE anything below can clear it. When
      // shouldIdle==false, mod.stop() (called further down) re-enters this chain's cancel(), which
      // nulls suspendedFollowTask — so we must capture the resume target here, up front, while it
      // still reflects the gesture that is finishing.
      FollowPlayerTask resumeFollow = null;
      if (oldTask instanceof BodyLanguageTask && this.suspendedFollowTask != null
            && !this.runningIdleTask && !this.resumingSuspendedFollow) {
         resumeFollow = this.suspendedFollowTask;
      }
      this.suspendedFollowTask = null;

      if (oldTask instanceof FollowPlayerTask && !this.runningIdleTask) {
         // String.format here is intentional: Log4j lazy-eval doesn't apply but the call is
         // at most once per task completion (not a hot path), and "%.1f" is required to keep
         // the output to 1 decimal place (raw double via {} would emit full precision).
         LOGGER.info("[FollowDiag] FOLLOW-FINISH: FollowPlayerTask ended after {}s; task.isFinished={} task.stopped={} shouldIdle={}",
               String.format("%.1f", seconds), oldTask.isFinished(), oldTask.stopped(), shouldIdle);
      }
      this.mainTask = null;
      if (!shouldIdle) {
         mod.stop();
      } else {
         mod.getBaritone().getPathingBehavior().forceCancel();
         mod.getBaritone().getInputOverrideHandler().clearAllKeys();
      }

      if (this.currentOnFinish != null) {
         this.currentOnFinish.run();
      }

      // Transient-overlay resume: a body-language gesture just finished and a follow was suspended for
      // it — restore the follow instead of idling. The onFinish callback above (the gesture's finish())
      // has already run its executor cleanup, so re-assigning is safe and cannot duplicate or leak the
      // gesture. Skip the resume if the onFinish callback already installed a fresh user task (a genuine
      // command issued during the gesture's completion) — mainTask would be non-null then.
      if (resumeFollow != null && this.mainTask == null) {
         LOGGER.info("[FollowDiag] FOLLOW-RESUME: gesture finished, resuming suspended follow '{}'",
               resumeFollow.toString());
         this.resumingSuspendedFollow = true;
         try {
            // reset() (via setTask) re-arms the task; if the target has since gone offline the resumed
            // follow self-terminates gracefully (it arms FOLLOWED_TARGET_GONE), so we never idle-loop
            // on a dead target here. Reuse a no-op onFinish — the original command boundary is closed.
            this.runTask(mod, resumeFollow, () -> {});
         } finally {
            this.resumingSuspendedFollow = false;
         }
         return;
      }

      boolean actuallyDone = this.mainTask == null;
      if (actuallyDone) {
         if (!this.runningIdleTask) {
            Debug.logMessage("User task FINISHED. Took %s seconds.", prettyPrintTimeDuration(seconds));
         }

         if (shouldIdle) {
            // Guard mirrors FOLLOW-FINISH at line 104: exclude the case where the idle task
            // itself is a follow invocation, which would emit a spurious "will NOT auto-resume".
            if (oldTask instanceof FollowPlayerTask && !this.runningIdleTask) {
               LOGGER.info("[FollowDiag] FOLLOW-IDLE-FALLBACK: follow ended, chain executing idleCommand='{}' - follow will NOT auto-resume",
                     mod.getModSettings().getIdleCommand());
            }
            this.controller.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
            this.signalNextTaskToBeIdleTask();
            this.runningIdleTask = true;
         }
      }
   }

   public boolean isRunningIdleTask() {
      return this.isActive() && this.runningIdleTask;
   }

   public void signalNextTaskToBeIdleTask() {
      this.nextTaskIdleFlag = true;
   }

   /** True when a non-idle user task is active (Part C0 idle guard). */
   public boolean hasActiveNonIdleUserTask() {
      return this.isActive() && !this.runningIdleTask && this.mainTask != null;
   }
}
