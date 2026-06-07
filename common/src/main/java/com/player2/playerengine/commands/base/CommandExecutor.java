package com.player2.playerengine.commands.base;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Consumer;

public class CommandExecutor {
   private final HashMap<String, Command> commandSheet = new HashMap<>();
   private final PlayerEngineController mod;

   public CommandExecutor(PlayerEngineController mod) {
      this.mod = mod;
   }

   public void registerNewCommand(Command... commands) {
      for (Command command : commands) {
         if (this.commandSheet.containsKey(command.getName())) {
            Debug.logInternal("Command with name " + command.getName() + " already exists! Can't register that name twice.");
         } else {
            this.commandSheet.put(command.getName(), command);
         }
      }
   }

   public String getCommandPrefix() {
      return this.mod.getModSettings().getCommandPrefix();
   }

   public boolean isClientCommand(String line) {
      return line.startsWith(this.getCommandPrefix());
   }

   private void executeRecursive(
         Command[] commands,
         String[] parts,
         int index,
         String accumulatedNote,
         Runnable onFinish,
         Consumer<String> onFinishWithNote,
         Consumer<CommandException> getException) {
      if (index >= commands.length) {
         // Deliver the accumulated success note only once the whole chain has completed. A
         // degraded-but-successful part advances the chain exactly like a clean success (it must NOT
         // abort later parts — a partial gather is still a success), so its note is carried forward
         // and emitted here. A blank accumulated note is byte-identical to the old clean finish.
         if (accumulatedNote == null || accumulatedNote.isBlank()) {
            onFinish.run();
         } else {
            onFinishWithNote.accept(accumulatedNote);
         }
      } else {
         Command command = commands[index];
         String part = parts[index];

         try {
            if (command == null) {
               getException.accept(new CommandException("Invalid command:" + part));
               this.executeRecursive(commands, parts, index + 1, accumulatedNote, onFinish, onFinishWithNote, getException);
            } else {
               // Pass the executor's error route to the command so a command that ends via
               // finishWithError(..) reaches CommandExecutionStopReason.Error (the only supported way
               // to deliver custom FAILED feedback to the model) — that terminates the chain. By
               // contrast, both a clean finish() and a success-with-note advance to the next part; the
               // note (if any) is joined into accumulatedNote and delivered once the chain completes,
               // so a degraded-but-successful part never silently drops a later command.
               command.run(
                  this.mod,
                  part,
                  () -> this.executeRecursive(commands, parts, index + 1, accumulatedNote, onFinish, onFinishWithNote, getException),
                  getException,
                  (note) -> this.executeRecursive(
                        commands, parts, index + 1, joinNotes(accumulatedNote, note), onFinish, onFinishWithNote, getException));
            }
         } catch (CommandException var9) {
            getException.accept(new CommandException(var9.getMessage() + "\nUsage: " + command.getHelpRepresentation(), var9));
         }
      }
   }

   /** Joins two success notes with "; ", treating null/blank as empty, so notes across a chain combine. */
   private static String joinNotes(String existing, String next) {
      if (next == null || next.isBlank()) {
         return existing;
      }
      if (existing == null || existing.isBlank()) {
         return next;
      }
      return existing + "; " + next;
   }

   public void execute(String line, Runnable onFinish, Consumer<CommandException> getException) {
      this.execute(line, () -> {}, onFinish, getException);
   }

   /**
    * Executes a client command line. {@code onAccepted} runs after all command parts parse
    * successfully and before the first command's {@code run(...)} starts (Phase B5 grounding).
    * Delegates with a safe default note route that ignores any success note and finishes cleanly,
    * so callers that do not opt into the note route keep byte-identical behavior.
    */
   public void execute(
         String line,
         Runnable onAccepted,
         Runnable onFinish,
         Consumer<CommandException> getException) {
      this.execute(line, onAccepted, onFinish, (note) -> onFinish.run(), getException);
   }

   /**
    * Executes a client command line, carrying the success-detail route. {@code onAccepted} runs after
    * all command parts parse successfully and before the first command's {@code run(...)} starts
    * (Phase B5 grounding). {@code onFinishWithNote} is the success-detail route: when one or more
    * commands in the chain end via {@link Command#finishWithNote(String)} with a non-blank note, the
    * chain still runs to completion (a success-with-note advances like a clean success) and the joined
    * note is delivered here once the chain finishes. A fully clean chain routes through {@code onFinish}.
    */
   public void execute(
         String line,
         Runnable onAccepted,
         Runnable onFinish,
         Consumer<String> onFinishWithNote,
         Consumer<CommandException> getException) {
      if (!this.isClientCommand(line)) {
         return;
      }
      line = line.substring(this.getCommandPrefix().length());
      String[] parts = line.split(";");
      Command[] commands = new Command[parts.length];

      try {
         for (int i = 0; i < parts.length; i++) {
            commands[i] = this.getCommand(parts[i]);
         }
      } catch (CommandException var7) {
         getException.accept(var7);
         return;
      }

      if (onAccepted != null) {
         onAccepted.run();
      }
      this.executeRecursive(commands, parts, 0, null, onFinish, onFinishWithNote, getException);
   }

   public void execute(String line, Consumer<CommandException> getException) {
      this.execute(line, () -> {}, () -> {}, getException);
   }

   public void execute(String line) {
      this.execute(line, ex -> Debug.logWarning(ex.getMessage()));
   }

   public void executeWithPrefix(String line) {
      if (!line.startsWith(this.getCommandPrefix())) {
         line = this.getCommandPrefix() + line;
      }

      this.execute(line);
   }

   private Command getCommand(String line) throws CommandException {
      line = line.trim();
      if (line.length() != 0) {
         String command = line;
         int firstSpace = line.indexOf(32);
         if (firstSpace != -1) {
            command = line.substring(0, firstSpace);
         }

         if (!this.commandSheet.containsKey(command)) {
            throw new CommandException("Command " + command + " does not exist.");
         } else {
            return this.commandSheet.get(command);
         }
      } else {
         return null;
      }
   }

   public Collection<Command> allCommands() {
      return this.commandSheet.values();
   }

   public Command get(String name) {
      return this.commandSheet.getOrDefault(name, null);
   }
}
