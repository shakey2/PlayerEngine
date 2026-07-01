package com.player2.playerengine.tasks.base;

/**
 * Marker: a survival-critical task (eating / food gathering) whose preemption of a running tracked
 * task (e.g. follow) is benign and expected, not a FATAL unexpected stop. FollowPlayerTask uses this
 * to arm a graceful {@code CANCELLED_SUPERSEDED_BY_SURVIVAL} instead of letting the step executor
 * classify the interrupted follow as {@code FATAL:task_stopped_without_finish}. Marker-interface
 * pattern mirrors {@link ITaskCanForce}.
 */
public interface SurvivalInterruptTask {
}
