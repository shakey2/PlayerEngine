package com.player2.playerengine.retrieval;

import com.player2.playerengine.commands.base.CommandExecutor;

/**
 * Virtual meta-command for model-requested RAG deep search (B5.1).
 * Not registered in {@link CommandExecutor}.
 */
public final class RagDeepSearchCommands {

    public static final String META_COMMAND_ID = "rag_deepsearch";

    private RagDeepSearchCommands() {}

    public static boolean isMetaCommand(String commandLine, CommandExecutor executor) {
        if (commandLine == null || commandLine.isBlank() || executor == null) {
            return false;
        }
        String withPrefix = executor.isClientCommand(commandLine)
                ? commandLine
                : executor.getCommandPrefix() + commandLine;
        return isMetaCommandId(
                com.player2.playerengine.player2api.AgentSideEffects.firstCommandId(withPrefix, executor));
    }

    public static boolean isMetaCommandId(String commandId) {
        return commandId != null && META_COMMAND_ID.equalsIgnoreCase(commandId.trim());
    }

    /** Replaces the static "always generate a command" line when deep-check rephrase is enabled. */
    public static String promptCommandFieldInstructions() {
        return """
                "command": "Pick the best listed world command id to achieve the goal, or `idle` to wait. \
                If no listed command can reasonably accomplish the current user goal, you may use the virtual command \
                `rag_deepsearch` (see below) to refresh the list — do not guess a poor fit. You can only run one command at a time.",
                """;
    }

    public static String promptCommandFieldInstructionsDefault() {
        return """
                "command": "Decide the best way to achieve the goals using the valid commands listed below. \
                YOU ALWAYS MUST GENERATE A COMMAND. Note you may also use the idle command `idle` to do nothing. \
                You can only run one command at a time! To replace the current one just write the new one.",
                """;
    }

    public static void appendPromptFooter(StringBuilder out) {
        out.append("rag_deepsearch: (virtual — not a world action) Request a refreshed command list when none of the ")
                .append("commands above can reasonably accomplish the user's current goal. ")
                .append("Use only if no listed command fits; do not use to stall when a listed command works. ")
                .append("Respond with JSON command \"rag_deepsearch\" and an optional short message; ")
                .append("the server will rerun retrieval if budget allows. On your next turn after refresh, ")
                .append("pick a listed world command id.\n\n");
    }
}
