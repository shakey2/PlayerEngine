package com.player2.playerengine.agentic;

import com.player2.playerengine.tasks.base.Task;
import java.util.Optional;

public interface AgenticStepFactory {
    Optional<Task> createTask(AgenticStepSpec step, AgenticExecutionContext context);
}
