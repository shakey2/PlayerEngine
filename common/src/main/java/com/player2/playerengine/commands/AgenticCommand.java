package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.AgenticGoalRequest;
import com.player2.playerengine.agentic.AgenticPlanExecutor;
import com.player2.playerengine.agentic.AgenticPlannerService;
import com.player2.playerengine.agentic.AgenticSchemas;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.commands.base.GoalText;
import com.player2.playerengine.commands.base.RestOfLineArg;
import java.util.UUID;

public class AgenticCommand extends Command {

    public AgenticCommand() throws CommandException {
        super(
                AgenticSchemas.PLANNER_COMMAND_ID,
                "Plan and run bounded multi-step goals (gather drops, resolve storage chest). "
                        + "Use direct commands like pickup_drops for simple actions.",
                new RestOfLineArg("goal"));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        GoalText goal = parser.get(GoalText.class);
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        UUID botUuid = mod.getEntity().getUUID();
        AgenticGoalRequest request = new AgenticGoalRequest(
                AgenticSchemas.PLAN_SCHEMA_VERSION,
                ownerUuid,
                botUuid,
                goal.text(),
                "command",
                System.currentTimeMillis());

        // Issue C fix: the Player2 PLANNING HTTP round-trip (8-17s) must NOT block the server thread
        // (command call() runs on the server command-dispatch thread). Run planning off-thread; the
        // callback below is marshalled back onto the server thread by planAsync before it runs, so plan
        // acceptance, executor start, and the finishWithNote/finishWithError callbacks all execute on
        // the server thread exactly as before. The callback fires exactly once.
        AgenticPlannerService.planAsync(mod, request, outcome -> {
            if (!outcome.hasExecutablePlan()) {
                String base = outcome.failureMessage() != null
                        ? outcome.failureMessage()
                        : "I could not make a safe plan for that yet.";
                String detail = "";
                if (outcome.validation() != null && outcome.validation().errors() != null
                        && !outcome.validation().errors().isEmpty()) {
                    detail = " (" + String.join(", ", outcome.validation().errors()) + ")";
                }
                this.finishWithError(base + detail);
                return;
            }

            mod.log("Agentic plan: " + outcome.validation().sanitizedPlan().goalSummary()
                    + " (source=" + outcome.planningSource() + ")");
            AgenticPlanExecutor.start(
                    request,
                    outcome.validation().sanitizedPlan(),
                    mod,
                    outcome.planningSource(),
                    (success, message) -> {
                        if (success) {
                            this.finishWithNote(message);
                        } else {
                            this.finishWithError(message != null && !message.isBlank()
                                    ? message
                                    : "Agentic goal could not be completed.");
                        }
                    });
        });
    }
}
