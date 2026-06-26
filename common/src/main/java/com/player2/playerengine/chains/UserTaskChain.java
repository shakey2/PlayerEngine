package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskRunner;
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
      boolean existingIsFollow = (this.mainTask instanceof FollowPlayerTask) && this.mainTask.isActive();
      if (existingIsFollow && !this.runningIdleTask) {
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
