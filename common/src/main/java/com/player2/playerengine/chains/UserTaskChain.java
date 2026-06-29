package com.player2.playerengine.chains;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.agentic.GatherLooseItemsTask;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
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
    * A user task suspended by a TRANSIENT body-language gesture (e.g. an inline {@code [bl:nod_head]}
    * marker or the {@code bodylang} command). A gesture is a short overlay, not a real replacement of the
    * active task: we stash the active task here at gesture-start and resume it when the gesture finishes,
    * instead of idling. Any genuine non-gesture task that overwrites the suspended task clears this
    * (a real cancel). Null when no task is suspended. We re-run the same task object (it is {@code reset()}
    * on re-assign); FollowPlayerTask self-terminates gracefully if the target has gone offline.
    * For ResourceTask subclasses (e.g. the task backing 'get'), reset() + re-run causes onResourceStart()
    * to reinitialize FSM state from live inventory, which re-checks already-met targets and skips
    * completed work — safe restart semantics.
    */
   private Task suspendedTask = null;
   /**
    * The {@code onFinish} callback that was registered when {@link #suspendedTask} was originally submitted.
    * Must be restored alongside the task on resume so the StepExecution terminal callback (and therefore
    * the CommandExecutor sequential chain) is not lost during the gesture overlay.
    */
   private Runnable suspendedOnFinish = null;
   /** True while we are re-assigning {@link #suspendedTask} so the resume path is not re-suspended. */
   private boolean resumingSuspended = false;

   public UserTaskChain(TaskRunner runner) {
      super(runner);
   }

   /**
    * The certified-safe-to-resume set (audit 2026-06). A gesture-suspended user task is only stashed and
    * resumed when it is one of these — every other task type falls through to the original overwrite-drop
    * (no resume) behaviour. These four re-initialize correctly from {@code onStart()} on a {@code reset()}
    * re-run and have no non-idempotent world-mutation side effects on re-run:
    * <ul>
    *   <li>{@link ResourceTask} (incl. {@code CraftMacroResourceTask} and the {@code CollectMeat}/
    *       {@code CollectFood} subclasses) — {@code isFinished()} re-checks live inventory every tick;
    *       already-met targets complete on the first tick, never duplicating work.</li>
    *   <li>{@link FollowPlayerTask} — empty {@code onStart()}; self-terminates if the target is gone.</li>
    *   <li>{@link MineBlockTask} — {@code onStart()} re-arms {@code Phase.RESOLVING}; re-resolves the block
    *       from the live world.</li>
    *   <li>{@link GatherLooseItemsTask} — {@code onStart()} re-arms {@code Phase.SCANNING}; rescans drops
    *       from the current position.</li>
    * </ul>
    * Stateful tracked tasks (smith/smelt/resolve-storage/deposit/label) and every container/build/combat
    * write task are deliberately excluded — resuming them would freeze the StepExecution chain, double-apply
    * a side effect, or report a false success.
    */
   private static boolean isResumeSafe(Task task) {
      return task instanceof ResourceTask
            || task instanceof FollowPlayerTask
            || task instanceof MineBlockTask
            || task instanceof GatherLooseItemsTask;
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
      // An explicit cancel is a genuine stop of the user task — drop any task suspended for a
      // transient gesture so it is not silently resumed after the user cancelled everything.
      this.suspendedTask = null;
      this.suspendedOnFinish = null;
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
      if (!this.runningIdleTask) {
         Debug.logMessage("User Task Set: " + task.toString());
      }

      boolean incomingIsFollow = task instanceof FollowPlayerTask;
      boolean incomingIsGesture = task instanceof BodyLanguageTask;
      // Captured BEFORE setTask() overwrites this.mainTask — the diag flag must reflect reality at
      // the moment of overwrite, not after the task has already been cleared (the old bug logged
      // followWasActive=false at gesture-start because the overwrite ran first).
      boolean existingIsFollow = (this.mainTask instanceof FollowPlayerTask) && this.mainTask.isActive();
      boolean existingIsActive = this.mainTask != null && this.mainTask.isActive()
            && !(this.mainTask instanceof BodyLanguageTask);
      Task existingTask = existingIsActive ? this.mainTask : null;

      // Transient-overlay rule: a body-language gesture overwriting an active non-gesture user task
      // stashes that task and its onFinish callback here. ONLY a task in the certified-safe set
      // (see {@link #isResumeSafe}) is actually re-run on gesture finish; every OTHER task type is
      // dropped (overwrite-drop, pre-fix behaviour, no re-run). We still stash the non-safe task's
      // onFinish so the gesture-finish path can fire it (fail-clean) — a tracked non-safe task carries
      // the StepExecution adapter lambda, and if it were simply lost on overwrite (setTask does NOT
      // fire the old onFinish) the StepExecution would never resolve and the CommandExecutor chain
      // would freeze. Firing it resolves the step truthfully (the adapter records a FAILED stop —
      // the gesture interrupted the step). For untracked non-safe tasks the stashed onFinish is a
      // harmless no-op, so firing it is equivalent to the pre-fix drop. Any OTHER genuine non-gesture
      // user task is a real replacement and clears any stashed task.
      if (!this.resumingSuspended && !this.runningIdleTask) {
         if (incomingIsGesture && existingTask != null) {
            this.suspendedTask = existingTask;
            this.suspendedOnFinish = this.currentOnFinish;  // save BEFORE overwrite below
            boolean willResume = isResumeSafe(existingTask);
            if (existingIsFollow) {
               LOGGER.info("[FollowDiag] FOLLOW-SUSPEND: active follow '{}' suspended by transient gesture '{}' (will resume on finish)",
                     existingTask.toString(), task.toString());
            } else if (willResume) {
               LOGGER.info("[FollowDiag] TASK-SUSPEND: resume-safe task '{}' suspended by transient gesture '{}' (will resume on finish)",
                     existingTask.toString(), task.toString());
            } else {
               // Non-safe: NOT resumed. Stashed only so its onFinish can be fired fail-clean on
               // gesture finish (a tracked step would otherwise freeze the CommandExecutor chain).
               LOGGER.info("[FollowDiag] TASK-DROP: non-resume-safe task '{}' dropped by transient gesture '{}' (will NOT resume; step resolved fail-clean on finish)",
                     existingTask.toString(), task.toString());
            }
         } else if (!incomingIsFollow && !incomingIsGesture) {
            // Genuine replacement task: cancel any suspended task for good (matches pre-regression behaviour).
            if (this.suspendedTask != null) {
               if (this.suspendedTask instanceof FollowPlayerTask) {
                  LOGGER.info("[FollowDiag] FOLLOW-SUSPEND-CLEAR: genuine task '{}' replaces follow; suspended follow discarded",
                        task.toString());
               } else {
                  LOGGER.info("[FollowDiag] TASK-SUSPEND-CLEAR: genuine task '{}' replaces suspended task '{}'; discarded",
                        task.toString(), this.suspendedTask.toString());
               }
            }
            this.suspendedTask = null;
            this.suspendedOnFinish = null;
         }
      }

      // Overwrite currentOnFinish AFTER the suspend logic has had a chance to save it above.
      this.currentOnFinish = onFinish;

      if (existingIsFollow && !this.runningIdleTask && !(incomingIsGesture && this.suspendedTask != null)) {
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
      // nulls suspendedTask/suspendedOnFinish — so we must capture both here, up front, while they
      // still reflect the gesture that is finishing.
      Task resumeTask = null;
      Runnable resumeOnFinish = null;
      if (oldTask instanceof BodyLanguageTask && this.suspendedTask != null
            && !this.runningIdleTask && !this.resumingSuspended) {
         resumeTask = this.suspendedTask;
         resumeOnFinish = this.suspendedOnFinish;
      }
      this.suspendedTask = null;
      this.suspendedOnFinish = null;

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

      // Transient-overlay finish: a body-language gesture just finished and a non-gesture task was
      // stashed for it. The onFinish callback above (the gesture's finish()) has already run its
      // executor cleanup. Skip everything here if that callback already installed a fresh user task (a
      // genuine command issued during the gesture's completion) — mainTask would be non-null then.
      if (resumeTask != null && this.mainTask == null) {
         if (isResumeSafe(resumeTask)) {
            // RESUME path (certified-safe set only): re-run the same task object. reset() (via setTask
            // inside runTask) re-arms it from onStart().
            //   - FollowPlayerTask: self-terminates gracefully if target is gone (FOLLOWED_TARGET_GONE).
            //   - ResourceTask (e.g. 'get'): onResourceStart() re-initializes FSM state from live
            //     inventory; already-met item targets cause isFinished() to return true on the first
            //     tick and the task completes cleanly.
            //   - MineBlockTask / GatherLooseItemsTask: onStart() re-arms the resolving/scanning phase.
            // resumeOnFinish restores the original StepExecution terminal callback so the
            // CommandExecutor sequential chain is not dropped.
            if (resumeTask instanceof FollowPlayerTask) {
               LOGGER.info("[FollowDiag] FOLLOW-RESUME: gesture finished, resuming suspended follow '{}'",
                     resumeTask.toString());
            } else {
               LOGGER.info("[FollowDiag] TASK-RESUME: gesture finished, resuming suspended task '{}'",
                     resumeTask.toString());
            }
            this.resumingSuspended = true;
            try {
               Runnable onFinishToRestore = (resumeOnFinish != null) ? resumeOnFinish : () -> {};
               this.runTask(mod, resumeTask, onFinishToRestore);
            } finally {
               this.resumingSuspended = false;
            }
            return;
         } else {
            // FAIL-CLEAN path (non-safe tasks): do NOT re-run the task — it is dropped (overwrite-drop,
            // pre-fix behaviour). But fire its stashed onFinish so a tracked StepExecution resolves
            // instead of hanging the CommandExecutor chain. The stashed callback is the
            // TaskStepExecutorAdapter lambda; the dropped task has isFinished()==false and stopped()==
            // true, so the adapter records a truthful FAILED stop (the gesture interrupted the step)
            // rather than a silent hang or a false success. For an untracked task the stashed onFinish
            // is a harmless no-op, so this is equivalent to the pre-fix drop.
            if (resumeOnFinish != null) {
               LOGGER.info("[FollowDiag] TASK-DROP-FINISH: gesture finished, non-resume-safe task '{}' dropped; firing its onFinish to resolve the step (no resume)",
                     resumeTask.toString());
               resumeOnFinish.run();
            }
            // Fall through to the idle/done bookkeeping below (mainTask stays null unless the fired
            // callback installed a fresh task).
         }
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
