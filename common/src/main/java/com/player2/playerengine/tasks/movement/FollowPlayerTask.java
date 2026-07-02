package com.player2.playerengine.tasks.movement;

import com.player2.playerengine.FollowMode;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.executor.StopReason;
import com.player2.playerengine.executor.TaskStepExecutorAdapter;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.base.SurvivalInterruptTask;
import com.player2.playerengine.tasks.base.Task;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

public class FollowPlayerTask extends Task {
   private static final Logger LOGGER = LogManager.getLogger();

   // --- Follow-mode tuning (v1: local constants; same names/values/clamps documented for a future
   // settings holder, identical across both PlayerEngine branches for cross-branch parity). The values
   // already sit inside their documented clamp ranges, so no runtime clamp is needed for constants. ---
   /** Max blocks a COWARD companion may flee from its follow target before the leash overrides evasion. (clamp 4.0..40.0) */
   private static final double cowardMaxLeash = 12.0;
   /** Distance passed to the flee task when evading in COWARD mode. (clamp 6.0..40.0) */
   private static final double cowardFleeDistance = 12.0;
   /** Max blocks a DEFENDER companion may drift from the target while fighting before reporting a break-off. (clamp 6.0..48.0) */
   private static final double defenderMaxChase = 16.0;

   /**
    * Degradation report throttle. Mirrors the controller's {@code MIN_REPORT_INTERVAL_MS} pattern so a
    * sustained danger/chase episode does not spam either channel (player chat + model note).
    */
   private static final long MIN_REPORT_INTERVAL_MS = 1500L;

   private final String playerName;
   private final double followDistance;
   /**
    * Set when we reached the followed player's last-known position but they are gone (no longer in
    * the tracker / not merely out of render distance) — the death/disconnect/dimension-change signal.
    * Consumed in {@link #onStop} to arm a graceful {@link StopReason#FOLLOWED_TARGET_GONE} on the
    * step executor instead of letting it classify the stop as FATAL:task_stopped_without_finish.
    */
   private boolean targetGone = false;

   // --- Follow-mode state-change guards (drive the state-change-only [FollowDiag] events, no per-tick
   // spam) plus the shared degradation-report throttle/dedup state. ---
   /** Last COWARD sub-decision logged: one of FLEE / LEASH-CAP / CLEAR (null until first decision). */
   private String lastCowardDecision = null;
   /** True while a DEFENDER break-off has been reported and not yet cleared (reset within followDistance). */
   private boolean lastDefenderBrokeOff = false;
   private long lastDegradationReportMs = 0L;
   /** Translation key of the last player-facing degradation sent; used for dedup in place of the raw message string. */
   private String lastDegradationKey = null;

   public FollowPlayerTask(String playerName, double followDistance) {
      this.playerName = playerName;
      this.followDistance = followDistance;
   }

   public FollowPlayerTask(String playerName) {
      this(playerName, 2.0);
   }

   @Override
   protected void onStart() {
   }

   @Override
   protected Task onTick() {
      PlayerEngineController mod = this.controller;
      Optional<Vec3> lastPos = mod.getEntityTracker().getPlayerMostRecentPosition(this.playerName);
      if (lastPos.isEmpty()) {
         this.setDebugState("No player found/detected. Doing nothing until player loads into render distance.");
         return null;
      } else {
         Vec3 target = lastPos.get();
         if (target.closerThan(mod.getPlayer().position(), 1.0) && !mod.getEntityTracker().isPlayerLoaded(this.playerName)) {
            mod.logWarning("Failed to get to player \"" + this.playerName + "\". We moved to where we last saw them but now have no idea where they are.");
            // Reached their last-known position and they are gone (died / disconnected / changed
            // dimension) rather than merely out of render distance — graceful termination, not FATAL.
            this.targetGone = true;
            this.stop();
            return null;
         } else {
            Optional<Player> player = mod.getEntityTracker().getPlayerEntity(this.playerName);
            if (player.isEmpty()) {
               // If we're currently on a boat but lost track of the owner, don't auto-dismount.
               return new GetToBlockTask(new BlockPos((int)target.x, (int)target.y, (int)target.z), false);
            }

            Player targetPlayer = (Player)player.get();

            // --- Follow-mode behavior. NORMAL falls straight through to the existing follow path.
            // COWARD may divert this tick to a leashed flee; DEFENDER only guards the chase leash. All
            // of this stays inside UserTaskChain (priority 50), so survival-chain preemption and follow
            // auto-resume are unchanged. ---
            FollowMode mode = mod.getFollowMode();
            double distToTarget = mod.getPlayer().distanceTo(targetPlayer);
            if (mode == FollowMode.COWARD) {
               Task fleeTask = this.tickCoward(mod, distToTarget);
               if (fleeTask != null) {
                  return fleeTask;
               }
               // CLEAR (no danger) and LEASH-CAP (leash wins) both fall through to the normal,
               // boat-aware follow below — LEASH-CAP wants exactly that (get back to the target).
            } else if (mode == FollowMode.DEFENDER) {
               this.tickDefender(mod, distToTarget);
            }

            Entity ownerVehicle = targetPlayer.getVehicle();
            Entity myVehicle = mod.getPlayer().getVehicle();

            // If we're on a boat but the owner isn't (or is on a different vehicle), leave the boat.
            if (myVehicle instanceof Boat && myVehicle != ownerVehicle) {
               mod.getPlayer().stopRiding();
            }

            // If owner is on a boat and there's a seat, try to join.
            if (ownerVehicle instanceof Boat && myVehicle != ownerVehicle) {
               return new EnterBoatWithOwnerTask(targetPlayer, this.followDistance);
            }

            return new GetToEntityTask((Entity)targetPlayer, this.followDistance);
         }
      }
   }

   /**
    * COWARD evasion decision for this tick. Reuses {@code MobDefenseChain}'s existing danger scan via
    * {@link com.player2.playerengine.chains.MobDefenseChain#isInDangerNow()} (combat itself is already
    * suppressed by {@code shouldDefendFromHostiles=true}). The leash is sacred (Non-negotiable #3):
    * <ul>
    *   <li>CLEAR — no danger → return {@code null} (normal follow).</li>
    *   <li>FLEE — in danger AND within {@code cowardMaxLeash} → return a leashed flee task.</li>
    *   <li>LEASH-CAP — in danger but at/over {@code cowardMaxLeash} → leash overrides evasion; report
    *       the cornered degradation and return {@code null} so the normal follow path pulls back.</li>
    * </ul>
    * Emits the state-change-only {@code [FollowDiag] COWARD-EVADE} event on a sub-state transition.
    */
   private Task tickCoward(PlayerEngineController mod, double distToTarget) {
      boolean inDanger = mod.getMobDefenseChain().isInDangerNow();
      String decision;
      Task fleeTask = null;
      if (!inDanger) {
         decision = "CLEAR";
      } else if (distToTarget < cowardMaxLeash) {
         decision = "FLEE";
         // Reuse the existing flee goal (getHostiles() already includes creepers); includeSkeletons=true
         // so we also back off ranged attackers. No new flee task is introduced.
         fleeTask = new RunAwayFromHostilesTask(cowardFleeDistance, true);
         // Truthfulness (DESIGN.md §3, plan WS4/WS6 report-point #2): while actively pursued but still
         // within the leash we cannot fully shake the threat — say so to both audiences so the model
         // never claims it got fully away or that it fought. Throttled/deduped on the player line below.
         this.reportDegradation(mod,
               "message.playerengine.follow.coward_fleeing",
               "follow(COWARD): a hostile is pursuing me; I am fleeing while staying within leash of you "
                     + "and not fighting it.");
      } else {
         decision = "LEASH-CAP";
         // The leash wins over evasion: cornered between danger and the follow target. Report to both
         // audiences (DESIGN.md §3) so the model never claims it got fully away or that it fought.
         this.reportDegradation(mod,
               "message.playerengine.follow.coward_cornered",
               "follow(COWARD): cornered — a threat is near but I am at my leash limit from you, so I "
                     + "stopped fleeing and stayed close instead of running off (I did not fight it).");
      }
      if (!decision.equals(this.lastCowardDecision)) {
         LOGGER.info("[FollowDiag] COWARD-EVADE: {} (distToTarget={} maxLeash={})",
               decision, String.format("%.1f", distToTarget), cowardMaxLeash);
         this.lastCowardDecision = decision;
      }
      return fleeTask;
   }

   /**
    * DEFENDER chase-leash guard for this tick. DEFENDER keeps combat enabled ({@code MobDefenseChain}
    * wins priority during a fight and this task does not run then); the guard fires the moment combat
    * yields and follow resumes far from the target — it reports the break-off and the normal follow
    * path (returned by {@link #onTick}) pulls back within {@code followDistance}. {@code MobDefenseChain}
    * target selection is NOT touched; the leash is enforced purely from the follow side. Emits the
    * state-change-only {@code [FollowDiag] DEFENDER-BREAKOFF} event once per break-off.
    */
   private void tickDefender(PlayerEngineController mod, double distToTarget) {
      if (distToTarget > defenderMaxChase) {
         if (!this.lastDefenderBrokeOff) {
            LOGGER.info("[FollowDiag] DEFENDER-BREAKOFF: distToTarget={} maxChase={} (returning to leash)",
                  String.format("%.1f", distToTarget), defenderMaxChase);
            this.lastDefenderBrokeOff = true;
            // Report the break-off exactly ONCE per episode (both audiences). Previously this call sat
            // OUTSIDE this state-change guard, so while the companion stayed beyond the chase leash it
            // re-fired every MIN_REPORT_INTERVAL_MS (~1.5s), spamming the player chat AND the model
            // conversation (each repeat triggered a fresh LLM round). The guard already gates the
            // [FollowDiag] log line above; the report belongs with it. Cleared in the branch below when
            // the companion returns within followDistance so a genuine future break-off reports again.
            this.reportDegradation(mod,
                  "message.playerengine.follow.defender_broke_off",
                  "follow(DEFENDER): I reached my max-chase limit from you while fighting, so I broke off "
                        + "the chase and am returning to stay near you.");
         }
      } else if (this.lastDefenderBrokeOff && distToTarget <= this.followDistance) {
         // Back at the leash — clear the guard so the next genuine break-off reports again.
         this.lastDefenderBrokeOff = false;
      }
   }

   /**
    * Throttled best-effort degradation report reaching BOTH audiences (DESIGN.md §3): the player via
    * a translatable {@link Component} sent directly to the owner's {@code displayClientMessage} (so the
    * CLIENT resolves the translation in its own locale), and the model via {@code AiConversationFeedback}
    * (an {@code InfoMessage} on this companion's queue, surfaced on the next LLM round). Follow has no
    * open command boundary for an in-task degradation, so the model channel is the conversation
    * feedback queue rather than a command {@code finishWithNote}. Throttled/deduped on the translation
    * key so sustained danger/chase does not spam either channel.
    *
    * @param playerKey    the translation key for the player-facing message (used as the dedup token
    *                     AND resolved to a {@link Component} so the client renders it in its own locale)
    * @param modelMessage the bounded, curated English string for the model's info queue (never localized)
    */
   private void reportDegradation(PlayerEngineController mod, String playerKey, String modelMessage) {
      long now = System.currentTimeMillis();
      if (playerKey.equals(this.lastDegradationKey)
            && (now - this.lastDegradationReportMs) < MIN_REPORT_INTERVAL_MS) {
         return;
      }
      this.lastDegradationReportMs = now;
      this.lastDegradationKey = playerKey;
      // Player channel: send as a translatable Component so the CLIENT resolves the locale.
      // Owner resolution mirrors PlayerEngineController#reportAgenticProgress (instanceof first,
      // then closest-player fallback) without routing through the String-based API which would
      // collapse the Component back to an en_us literal before it reaches the network layer.
      ServerPlayer target;
      if (mod.getOwner() instanceof ServerPlayer sp) {
         target = sp;
      } else {
         target = mod.getClosestPlayer().orElse(null);
      }
      if (target != null) {
         target.displayClientMessage(Component.translatable(playerKey), false);
      }
      // Model channel (truthfulness): a short, curated, bounded phrase — never logs/stack/unbounded text.
      AiConversationFeedback.enqueueInfo(mod, modelMessage);
   }

   @Override
   protected void onStop(Task interruptTask) {
      LOGGER.info("[FollowDiag] FOLLOW-TASK-STOP: targetGone={} interruptTask={} isFinished={}",
            this.targetGone,
            (interruptTask != null) ? interruptTask.getClass().getSimpleName() : "(none)",
            this.isFinished());
      // If we stopped because the followed player vanished, arm a graceful FOLLOWED_TARGET_GONE on
      // the step executor before the user task chain fires its terminal onFinish callback. Without
      // this, the chain sees a task that stopped without finishing and labels it FATAL.
      if (this.targetGone && this.controller != null
            && this.controller.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
         adapter.armPendingChainCancel(StopReason.FOLLOWED_TARGET_GONE);
      } else if (interruptTask instanceof SurvivalInterruptTask && this.controller != null
            && this.controller.getStepExecutorAdapter() instanceof TaskStepExecutorAdapter adapter) {
         // A survival-critical task (auto-eat / food gathering) preempted this follow. Benign, not
         // FATAL: arm a graceful CANCELLED_SUPERSEDED_BY_SURVIVAL so the follow step's onFinish reads
         // it and transitions FAILED with that reason instead of FATAL:task_stopped_without_finish.
         // Its name contains "CANCELLED_", so the raw chat dump is suppressed by the adapter's gate.
         adapter.armPendingChainCancel(StopReason.CANCELLED_SUPERSEDED_BY_SURVIVAL);
      }
   }

   @Override
   protected boolean isEqual(Task other) {
      return !(other instanceof FollowPlayerTask task)
         ? false
         : task.playerName.equals(this.playerName) && Math.abs(this.followDistance - task.followDistance) < 0.1;
   }

   @Override
   protected String toDebugString() {
      return "Going to player " + this.playerName;
   }
}
