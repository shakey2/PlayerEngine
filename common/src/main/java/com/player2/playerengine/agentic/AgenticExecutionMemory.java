package com.player2.playerengine.agentic;

import java.util.Optional;

/** Per-run agentic execution memory shared across steps in one plan. */
public final class AgenticExecutionMemory {

    private AgenticStorageTarget storageTarget;

    public Optional<AgenticStorageTarget> storageTarget() {
        return Optional.ofNullable(storageTarget);
    }

    public void setStorageTarget(AgenticStorageTarget target) {
        this.storageTarget = target;
    }

    public void clearStorageTarget() {
        this.storageTarget = null;
    }
}
