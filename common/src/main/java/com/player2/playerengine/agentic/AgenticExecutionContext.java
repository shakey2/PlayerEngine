package com.player2.playerengine.agentic;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.agentic.AgenticRunRegistry.AgenticRunState;

public final class AgenticExecutionContext {

    private final PlayerEngineController controller;
    private final PlayerEngineSettings settings;
    private final AgenticRunState runState;
    private final AgenticExecutionMemory memory;

    public AgenticExecutionContext(PlayerEngineController controller, AgenticRunState runState) {
        this.controller = controller;
        this.settings = controller.getModSettings();
        this.runState = runState;
        this.memory = new AgenticExecutionMemory();
    }

    public PlayerEngineController controller() {
        return controller;
    }

    public PlayerEngineSettings settings() {
        return settings;
    }

    public AgenticRunState runState() {
        return runState;
    }

    public AgenticExecutionMemory memory() {
        return memory;
    }
}
