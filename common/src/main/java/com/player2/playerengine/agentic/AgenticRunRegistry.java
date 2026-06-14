package com.player2.playerengine.agentic;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory per-bot agentic run snapshots (not persisted). */
public final class AgenticRunRegistry {

    private static final ConcurrentHashMap<UUID, AgenticRunState> RUNS = new ConcurrentHashMap<>();

    private AgenticRunRegistry() {}

    public static void register(UUID botUuid, AgenticRunState state) {
        if (botUuid != null && state != null) {
            RUNS.put(botUuid, state);
        }
    }

    public static Optional<AgenticRunState> get(UUID botUuid) {
        return Optional.ofNullable(RUNS.get(botUuid));
    }

    public static Optional<AgenticRunSnapshot> snapshot(UUID botUuid) {
        return get(botUuid).map(AgenticRunState::toSnapshot);
    }

    public static void clear(UUID botUuid) {
        if (botUuid != null) {
            RUNS.remove(botUuid);
        }
    }

    /**
     * Structured per-step degradation level recorded ONLY on warning/partial branches.
     * CLEAN means no degradation occurred (the default). Reading this (never the *Progress
     * strings) is the correct signal for determining whether a run had a clean success.
     */
    public enum DegradationLevel { CLEAN, PARTIAL, SKIPPED }

    public static final class AgenticRunState {
        private final String runId;
        private final String goalSummary;
        private final String planningSource;
        private int activeStepIndex = -1;
        private String activeStepKind = "";
        private String state = "planning";
        private String lastMessage = "";
        private String gatherProgress = "";
        private String storageProgress = "";
        private String depositProgress = "";
        private String labelProgress = "";
        private String storageTargetSummary = "";
        // Set true once the run reaches a terminal outcome (see terminal(...)). The post-terminal
        // guard (C4) reads this off the run-state object the tasks hold, NOT via an
        // AgenticRunRegistry lookup, because AgenticPlanExecutor.finishOnServer() calls
        // AgenticRunRegistry.clear() on the terminal path — a registry lookup would find nothing.
        // The run-state object survives clear() because tasks keep their own reference to it.
        private volatile boolean terminal = false;

        // Structured degraded signals — separate from the *Progress strings, which are
        // rewritten on every run (including clean success) and MUST NOT be used as a
        // clean-vs-degraded signal. These fields are set ONLY on partial/warning branches.
        private DegradationLevel gatherDegradation = DegradationLevel.CLEAN;
        private String gatherDegradationReason = "";
        private DegradationLevel depositDegradation = DegradationLevel.CLEAN;
        private String depositDegradationReason = "";
        private DegradationLevel labelDegradation = DegradationLevel.CLEAN;
        private String labelDegradationReason = "";
        private DegradationLevel waypointDegradation = DegradationLevel.CLEAN;
        private String waypointDegradationReason = "";

        public AgenticRunState(String runId, String goalSummary, String planningSource) {
            this.runId = runId;
            this.goalSummary = goalSummary;
            this.planningSource = planningSource;
        }

        public void setActiveStep(int index, String kind) {
            this.activeStepIndex = index;
            this.activeStepKind = kind != null ? kind : "";
            this.state = "running";
        }

        public void setGatherProgress(String progress) {
            this.gatherProgress = progress != null ? progress : "";
        }

        public void setStorageProgress(String progress) {
            this.storageProgress = progress != null ? progress : "";
        }

        public void setDepositProgress(String progress) {
            this.depositProgress = progress != null ? progress : "";
        }

        public void setLabelProgress(String progress) {
            this.labelProgress = progress != null ? progress : "";
        }

        public void setStorageTargetSummary(String summary) {
            this.storageTargetSummary = summary != null ? summary : "";
        }

        // -----------------------------------------------------------------------------------------
        // Structured degraded-signal setters (called ONLY on partial/warning branches, never on
        // clean success or failure paths). Separate from the *Progress fields.
        // -----------------------------------------------------------------------------------------

        public void setGatherDegraded(DegradationLevel level, String reason) {
            this.gatherDegradation = level != null ? level : DegradationLevel.CLEAN;
            this.gatherDegradationReason = reason != null ? reason : "";
        }

        public void setDepositDegraded(DegradationLevel level, String reason) {
            this.depositDegradation = level != null ? level : DegradationLevel.CLEAN;
            this.depositDegradationReason = reason != null ? reason : "";
        }

        public void setLabelDegraded(DegradationLevel level, String reason) {
            this.labelDegradation = level != null ? level : DegradationLevel.CLEAN;
            this.labelDegradationReason = reason != null ? reason : "";
        }

        /**
         * Records a waypoint-registration degradation (SKIPPED or PARTIAL) in the run state.
         * Called by {@code WaypointAutoRegistrar} when auto-registration is skipped or fails.
         * Never called on clean auto-registration success (the milestone player line is sufficient).
         */
        public void setWaypointDegraded(DegradationLevel level, String reason) {
            this.waypointDegradation = level != null ? level : DegradationLevel.CLEAN;
            this.waypointDegradationReason = reason != null ? reason : "";
        }

        public DegradationLevel getGatherDegradation() { return gatherDegradation; }
        public String getGatherDegradationReason() { return gatherDegradationReason; }
        public DegradationLevel getDepositDegradation() { return depositDegradation; }
        public String getDepositDegradationReason() { return depositDegradationReason; }
        public DegradationLevel getLabelDegradation() { return labelDegradation; }
        public String getLabelDegradationReason() { return labelDegradationReason; }
        public DegradationLevel getWaypointDegradation() { return waypointDegradation; }
        public String getWaypointDegradationReason() { return waypointDegradationReason; }

        /**
         * Returns the most relevant per-step progress note for the given step kind, used to surface
         * the specific failure reason (e.g. "could_not_obtain_chest_materials") in the terminal
         * outcome message. Falls back to the empty string when nothing was recorded.
         */
        public String progressForKind(String kind) {
            if (kind == null) {
                return "";
            }
            String k = kind.toLowerCase(java.util.Locale.ROOT);
            if (k.contains("gather")) {
                return gatherProgress;
            }
            if (k.contains("deposit")) {
                return depositProgress;
            }
            if (k.contains("label")) {
                return labelProgress;
            }
            // resolve_storage_chest and other storage steps report via storageProgress.
            return storageProgress;
        }

        public void terminal(String state, String message) {
            this.state = state;
            this.lastMessage = message != null ? message : "";
            this.activeStepKind = "";
            this.terminal = true;
        }

        /**
         * True once this run has gone terminal. Used by the C4 post-terminal guard so a late step-task
         * progress callback cannot overwrite a failure line. Checked on this run-state object directly
         * (tasks hold a reference) rather than via the registry, which is cleared on the terminal path.
         */
        public boolean isTerminal() {
            return terminal;
        }

        public AgenticRunSnapshot toSnapshot() {
            StringBuilder msg = new StringBuilder();
            if (!lastMessage.isBlank()) {
                msg.append(lastMessage);
            }
            if (!gatherProgress.isBlank()) {
                if (msg.length() > 0) {
                    msg.append(" | ");
                }
                msg.append(gatherProgress);
            }
            if (!storageProgress.isBlank()) {
                if (msg.length() > 0) {
                    msg.append(" | ");
                }
                msg.append(storageProgress);
            }
            if (!depositProgress.isBlank()) {
                if (msg.length() > 0) {
                    msg.append(" | ");
                }
                msg.append(depositProgress);
            }
            if (!labelProgress.isBlank()) {
                if (msg.length() > 0) {
                    msg.append(" | ");
                }
                msg.append(labelProgress);
            }
            return new AgenticRunSnapshot(
                    runId,
                    goalSummary,
                    activeStepIndex,
                    activeStepKind,
                    state,
                    msg.toString(),
                    planningSource,
                    storageProgress,
                    depositProgress,
                    labelProgress,
                    storageTargetSummary);
        }
    }
}
