package com.player2.playerengine.tasks.base;

/**
 * Supplies a truthful terminal outcome for tasks whose completion cannot be represented by
 * {@link Task#isFinished()} alone.
 */
public interface TrackedTaskOutcomeProvider {
    boolean isTerminal();

    boolean isSuccessful();

    String controlledReason();
}
