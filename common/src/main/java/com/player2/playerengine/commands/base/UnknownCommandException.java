package com.player2.playerengine.commands.base;

/**
 * Thrown by {@link CommandExecutor#getCommand(String)} when the emitted command name is not
 * registered and is not a known alias. Carries the enriched "did you mean" message when a
 * threshold-gated fuzzy match exists.
 *
 * <p>This subtype exists so the error route in {@code AgentSideEffects} can discriminate the
 * unknown-command case from other {@link CommandException} failures (e.g. item-arg rejections,
 * runtime errors) and broadcast an honest player-facing correction only for this case — preventing
 * double-broadcast of {@code @get} failures while still correcting unknown command names.
 */
public class UnknownCommandException extends CommandException {
   public UnknownCommandException(String message) {
      super(message);
   }

   public UnknownCommandException(String message, Exception child) {
      super(message, child);
   }
}
