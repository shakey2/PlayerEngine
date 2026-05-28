package com.player2.playerengine.retrieval.learning;

public enum AliasLearningOutcome {
    COMMITTED,
    SKIPPED_DISABLED,
    SKIPPED_COMMAND_MISMATCH,
    SKIPPED_INVALID_TOOL,
    SKIPPED_DUPLICATE,
    SKIPPED_CAP_REACHED,
    SKIPPED_EXECUTION_ERROR,
    FAILED_WRITE
}
