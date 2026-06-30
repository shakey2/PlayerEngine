/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.player2.playerengine.automaton.command.defaults;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.player2api.BudgetConfigCommands;
import com.player2.playerengine.player2api.JoulesCache;
import com.player2.playerengine.player2api.ProfileUrlResolver;
import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.network.Player2ServerNetworking;
import com.player2.playerengine.automaton.Baritone;
import com.player2.playerengine.automaton.api.BaritoneAPI;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.Settings;
import com.player2.playerengine.automaton.api.command.ICommand;
import com.player2.playerengine.automaton.api.command.argument.ICommandArgument;
import com.player2.playerengine.automaton.api.command.exception.CommandException;
import com.player2.playerengine.automaton.api.command.exception.CommandNotEnoughArgumentsException;
import com.player2.playerengine.automaton.api.command.manager.ICommandManager;
import com.player2.playerengine.automaton.api.entity.IAutomatone;
import com.player2.playerengine.automaton.command.argument.ArgConsumer;
import com.player2.playerengine.automaton.command.manager.BaritoneArgumentType;
import com.player2.playerengine.automaton.command.manager.BaritoneCommandManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.HoverEvent.Action;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class DefaultCommands {
   public static final ExecutionControlCommands controlCommands = new ExecutionControlCommands();
   public static final SelCommand selCommand = new SelCommand();
   public static final DynamicCommandExceptionType BARITONE_COMMAND_FAILED_EXCEPTION = new DynamicCommandExceptionType(Message.class::cast);

   public static void registerAll() {
      for (ICommand command : new ArrayList<>(
         Arrays.asList(
            new HelpCommand(),
            new SetCommand(),
            new CommandAlias(Arrays.asList("modified", "mod", "com/player2/playerengine/automaton", "modifiedsettings"), "List modified settings", "set modified"),
            new CommandAlias("reset", "Reset all settings or just one", "set reset"),
            new GoalCommand(),
            new GotoCommand(),
            new PathCommand(),
            new ProcCommand(),
            new ETACommand(),
            new VersionCommand(),
            new RepackCommand(),
            new BuildCommand(),
            new SchematicaCommand(),
            new ComeCommand(),
            new AxisCommand(),
            new ForceCancelCommand(),
            new GcCommand(),
            new InvertCommand(),
            new TunnelCommand(),
            new RenderCommand(),
            new FarmCommand(),
            new ChestsCommand(),
            new FollowCommand(),
            new ExploreFilterCommand(),
            new ReloadAllCommand(),
            new SaveAllCommand(),
            new ExploreCommand(),
            new BlacklistCommand(),
            new FindCommand(),
            new MineCommand(),
            new ClickCommand(),
            new SurfaceCommand(),
            new ThisWayCommand(),
            new WaypointsCommand(),
            new FishCommand(),
            new CommandAlias("sethome", "Sets your home waypoint", "waypoints save home"),
            new CommandAlias("home", "Path to your home waypoint", "waypoints goto home"),
            selCommand
         )
      )) {
         ICommandManager.registry.register(command);
      }
      CommandRegistrationEvent.EVENT.register((dispatcher, ctx, dedicated) -> register(dispatcher));
   }

   private static void logRanCommand(CommandSourceStack source, String command, String rest) {
      if (BaritoneAPI.getGlobalSettings().echoCommands.get()) {
         String msg = command + rest;
         String toDisplay = BaritoneAPI.getGlobalSettings().censorRanCommands.get() ? command + " ..." : msg;
         source.sendSuccess(
            () -> {
               MutableComponent component = Component.translatable("message.playerengine.commands.echo_prefix", toDisplay);
               component.setStyle(
                  component.getStyle()
                     .applyFormat(ChatFormatting.WHITE)
                     .withHoverEvent(new HoverEvent(Action.SHOW_TEXT, Component.translatable("message.playerengine.commands.echo_hover")))
                     .withClickEvent(new ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/automatone " + msg))
               );
               return component;
            },
            false
         );
      }
   }

   public static boolean runCommand(CommandSourceStack source, String msg, IBaritone baritone) throws CommandException {
      if (msg.trim().equalsIgnoreCase("damn")) {
         source.sendSuccess(() -> Component.translatable("message.playerengine.commands.easter_egg"), false);
         return false;
      } else if (msg.trim().equalsIgnoreCase("orderpizza")) {
         PlayerEngine.LOGGER.fatal("No pizza :(");
         return false;
      } else if (msg.isEmpty()) {
         return runCommand(source, "help", baritone);
      } else {
         Tuple<String, List<ICommandArgument>> pair = BaritoneCommandManager.expand(msg);
         String command = (String)pair.getA();
         String rest = msg.substring(((String)pair.getA()).length());
         ArgConsumer argc = new ArgConsumer(baritone.getCommandManager(), (List<ICommandArgument>)pair.getB(), baritone);
         if (!argc.hasAny()) {
            Settings.Setting<?> setting = BaritoneAPI.getGlobalSettings().byLowerName.get(command.toLowerCase(Locale.ROOT));
            if (setting != null) {
               logRanCommand(source, command, rest);
               if (setting.getValueClass() == Boolean.class) {
                  baritone.getCommandManager().execute(source, String.format("set toggle %s", setting.getName()));
               } else {
                  baritone.getCommandManager().execute(source, String.format("set %s", setting.getName()));
               }

               return true;
            }
         } else if (argc.hasExactlyOne()) {
            for (Settings.Setting<?> setting : BaritoneAPI.getGlobalSettings().allSettings) {
               if (!setting.getName().equals("logger") && setting.getName().equalsIgnoreCase((String)pair.getA())) {
                  logRanCommand(source, command, rest);

                  try {
                     baritone.getCommandManager().execute(source, String.format("set %s %s", setting.getName(), argc.getString()));
                  } catch (CommandNotEnoughArgumentsException var10) {
                  }

                  return true;
               }
            }
         }

         if (ICommandManager.getCommand((String)pair.getA()) != null) {
            logRanCommand(source, command, rest);
         }

         return baritone.getCommandManager().execute(source, pair);
      }
   }

   private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
      LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(PlayerEngine.MOD_ID).requires(s -> s.hasPermission(2));
      root.then(Commands.literal("player2")
            .then(Commands.literal("reload").executes(ctx -> {
               if (!isLogicalServer(ctx.getSource())) {
                  return 0;
               }
               Player2ServerConfigHolder.load();
               syncPlayer2ConfigToAllPlayers(ctx.getSource());
               ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.reload_success"), false);
               return 1;
            }))
            .then(Commands.literal("payer")
                  .then(Commands.literal("prompter").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     var c = Player2ServerConfigHolder.get();
                     c.setPayerMode(Player2PayerMode.PROMPTER_PAYS);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     syncPlayer2ConfigToAllPlayers(ctx.getSource());
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.payer_prompter"), false);
                     return 1;
                  }))
                  .then(Commands.literal("owner").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     var c = Player2ServerConfigHolder.get();
                     c.setPayerMode(Player2PayerMode.OWNER_PAYS_ALL);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     syncPlayer2ConfigToAllPlayers(ctx.getSource());
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.payer_owner"), false);
                     return 1;
                  })))
            .then(Commands.literal("dedicated")
                  .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     boolean v = BoolArgumentType.getBool(ctx, "value");
                     var c = Player2ServerConfigHolder.get();
                     c.setDedicatedClientProxy(v);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     syncPlayer2ConfigToAllPlayers(ctx.getSource());
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.dedicated", v), false);
                     return 1;
                  })))
            .then(Commands.literal("owner_offline_continue")
                  .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     boolean v = BoolArgumentType.getBool(ctx, "value");
                     var c = Player2ServerConfigHolder.get();
                     c.setOwnerOfflineServerContinuation(v);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     syncPlayer2ConfigToAllPlayers(ctx.getSource());
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.owner_offline_continue", v), false);
                     return 1;
                  })))
            .then(Commands.literal("call_by_name")
                  .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     boolean v = BoolArgumentType.getBool(ctx, "value");
                     var c = Player2ServerConfigHolder.get();
                     c.setCallByNameChat(v);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.call_by_name", v), false);
                     return 1;
                  })))
            .then(Commands.literal("npc_max_spawn")
                  .then(Commands.argument("value", IntegerArgumentType.integer(1, 20)).executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     int v = IntegerArgumentType.getInteger(ctx, "value");
                     var c = Player2ServerConfigHolder.get();
                     c.setMaxSpawnedCompanionsPerPlayer(v);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.npc_max_spawn", v), false);
                     return 1;
                  })))
            .then(Commands.literal("npc_max_stored_ids")
                  .then(Commands.argument("value", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) {
                        return 0;
                     }
                     int v = IntegerArgumentType.getInteger(ctx, "value");
                     var c = Player2ServerConfigHolder.get();
                     c.setMaxStoredCharacterIdsPerPlayer(v);
                     Player2ServerConfigHolder.validateAndFix(c);
                     Player2ServerConfigHolder.save();
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.config.npc_max_stored_ids", v), false);
                     return 1;
                  })))
            .then(Commands.literal("budget")
                  .then(Commands.literal("soft")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setSoft(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "value"));
                        })))
                  .then(Commands.literal("hard")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setHard(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "value"));
                        })))
                  .then(Commands.literal("window")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setWindow(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "minutes"));
                        })))
                  .then(Commands.literal("joules_soft")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setJoulesSoft(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "value"));
                        })))
                  .then(Commands.literal("joules_hard")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setJoulesHard(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "value"));
                        })))
                  .then(Commands.literal("joules_refresh")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(60, 86400)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setJoulesRefresh(ctx.getSource(),
                                   IntegerArgumentType.getInteger(ctx, "seconds"));
                        })))
                  .then(Commands.literal("fallback_profile")
                        .then(Commands.argument("name", StringArgumentType.string()).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           String raw = StringArgumentType.getString(ctx, "name");
                           String profileName = raw.equalsIgnoreCase("none") ? null : raw;
                           return BudgetConfigCommands.setFallbackProfile(ctx.getSource(), profileName);
                        })))
                  .then(Commands.literal("fallback_behavior")
                        .then(Commands.literal("switch").executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setFallbackBehaviorSwitch(ctx.getSource());
                        }))
                        .then(Commands.literal("stop").executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           return BudgetConfigCommands.setFallbackBehaviorStop(ctx.getSource());
                        })))
                  .then(Commands.literal("reset").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     BudgetTracker.resetAll();
                     JoulesCache.invalidateAll();
                     ProfileUrlResolver.invalidateCache();
                     ctx.getSource().sendSuccess(() -> Component.translatable("message.playerengine.budget.reset_success"), false);
                     return 1;
                  }))
                  .then(Commands.literal("status").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     return BudgetConfigCommands.status(ctx.getSource());
                  })))
            .then(Commands.literal("chain")
                  .then(Commands.literal("status").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     var source = ctx.getSource();
                     int pruned = PlayerEngineController.pruneStaleControllers(source.getServer());
                     var controllers = PlayerEngineController.staticControllers;
                     if (controllers.isEmpty()) {
                        source.sendSuccess(() -> pruned > 0
                              ? Component.translatable("message.playerengine.chain.no_active_bots_pruned", pruned)
                              : Component.translatable("message.playerengine.chain.no_active_bots"), false);
                        return 1;
                     }
                     if (pruned > 0) {
                        source.sendSuccess(() -> Component.translatable(
                              "message.playerengine.chain.pruned_controllers", pruned), false);
                     }
                     for (var entry : controllers.entrySet()) {
                        PlayerEngineController bot = entry.getValue();
                        if (bot.getEntity() == null || bot.getEntity().isRemoved()) {
                           continue;
                        }
                        String botName = bot.getChainStatusDisplayName();
                        source.sendSuccess(() -> Component.translatable("message.playerengine.chain.status_header", botName), false);
                        com.player2.playerengine.agentic.AgenticRunRegistry
                              .snapshot(bot.getEntity().getUUID())
                              .ifPresent(run -> {
                                 source.sendSuccess(() -> Component.translatable(
                                       "message.playerengine.chain.agentic_run",
                                       run.runId(), run.state(), run.goalSummary()), false);
                                 source.sendSuccess(() -> Component.translatable(
                                       "message.playerengine.chain.planning_info",
                                       run.planningSource(), run.activeStepIndex(), run.activeStepKind()), false);
                                 if (run.lastMessage() != null && !run.lastMessage().isBlank()) {
                                    source.sendSuccess(() -> Component.translatable(
                                          "message.playerengine.chain.progress", run.lastMessage()), false);
                                 }
                                 if (run.storageTargetSummary() != null && !run.storageTargetSummary().isBlank()) {
                                    source.sendSuccess(() -> Component.translatable(
                                          "message.playerengine.chain.storage_target", run.storageTargetSummary()), false);
                                 }
                                 if (run.storageProgress() != null && !run.storageProgress().isBlank()) {
                                    source.sendSuccess(() -> Component.translatable(
                                          "message.playerengine.chain.storage_phase", run.storageProgress()), false);
                                 }
                                 if (run.depositProgress() != null && !run.depositProgress().isBlank()) {
                                    source.sendSuccess(() -> Component.translatable(
                                          "message.playerengine.chain.deposit_phase", run.depositProgress()), false);
                                 }
                                 if (run.labelProgress() != null && !run.labelProgress().isBlank()) {
                                    source.sendSuccess(() -> Component.translatable(
                                          "message.playerengine.chain.label_phase", run.labelProgress()), false);
                                 }
                              });
                        var execOpt = bot.getStepExecutorAdapter().getActiveExecution();
                        if (execOpt.isEmpty()) {
                           source.sendSuccess(() -> Component.translatable("message.playerengine.chain.no_step_yet"), false);
                           continue;
                        }
                        StepExecution exec = execOpt.get();
                        if (exec.getState() == StepState.RUNNING) {
                           long secs = exec.getElapsedMs() / 1000L;
                           source.sendSuccess(() -> Component.translatable(
                                   "message.playerengine.chain.active_step",
                                   exec.getStepId(), exec.getStepKind(), secs), false);
                           String last = exec.getLastLogEntry();
                           source.sendSuccess(() -> Component.translatable(
                                   "message.playerengine.chain.last_log_entry", last), false);
                        } else {
                           source.sendSuccess(() -> Component.translatable(
                                   "message.playerengine.chain.last_step",
                                   exec.getStepId(), exec.getStepKind(), exec.getState()), false);
                        }
                        com.player2.playerengine.tasks.base.Task userTask = bot.getUserTaskChain().getCurrentTask();
                        if (userTask != null) {
                           source.sendSuccess(() -> Component.translatable(
                                   "message.playerengine.chain.user_task", userTask), false);
                           if (userTask instanceof com.player2.playerengine.tasks.crafting.DescribesProgress progress) {
                              source.sendSuccess(() -> Component.translatable(
                                      "message.playerengine.chain.task_progress", progress.describeProgress()), false);
                           }
                           String tree = userTask.getTaskTree();
                           if (tree != null && !tree.isBlank()) {
                              source.sendSuccess(() -> Component.translatable(
                                      "message.playerengine.chain.task_tree", tree), false);
                           }
                        }
                        source.sendSuccess(() -> Component.translatable("message.playerengine.chain.full_log_divider"), false);
                        for (String logEntry : exec.getLog()) {
                           source.sendSuccess(() -> Component.translatable("message.playerengine.chain.log_entry", logEntry), false);
                        }
                     }
                     return 1;
                  }))));
      root.then(Commands.argument("command", StringArgumentType.greedyString())
            .executes(command -> {
               CommandSourceStack source = command.getSource();
               String sub = BaritoneArgumentType.getCommand(command, "command");
               Entity entity = source.getEntity();
               if (entity instanceof LivingEntity living) {
                  return runCommand(source, living, sub);
               }
               return runBaritoneWithoutExecutorEntity(source, sub);
            }));
      dispatcher.register(root);
   }

   private static void syncPlayer2ConfigToAllPlayers(CommandSourceStack source) {
      MinecraftServer server = source.getServer();
      if (server == null) {
         return;
      }
      for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
         Player2ServerNetworking.sendConfigSync(sp);
      }
   }

   /**
    * Player2 server JSON and sync only apply on the logical server so remote clients cannot mutate their local config
    * by running these commands on a client-only dispatcher.
    */
   private static boolean isLogicalServer(CommandSourceStack source) {
      if (source.getLevel().isClientSide()) {
         source.sendFailure(Component.translatable("message.playerengine.commands.server_only"));
         return false;
      }
      return true;
   }

   private static Baritone findAnyBaritone(MinecraftServer server) {
      if (server == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity e : level.getAllEntities()) {
            if (e instanceof LivingEntity living && e instanceof IAutomatone) {
               IBaritone b = IBaritone.KEY.getNullable(living);
               if (b instanceof Baritone baritone) {
                  return baritone;
               }
            }
         }
      }
      return null;
   }

   /**
    * Dedicated console, command blocks, etc. have no executor entity; route Baritone parsing through any loaded Automatone,
    * or show server-admin hints when none exist.
    */
   private static int runBaritoneWithoutExecutorEntity(CommandSourceStack source, String rawCommand) throws CommandSyntaxException {
      if (!isLogicalServer(source)) {
         return 0;
      }
      MinecraftServer server = source.getServer();
      if (server == null) {
         source.sendFailure(Component.translatable("message.playerengine.commands.no_server"));
         return 0;
      }
      Baritone baritone = findAnyBaritone(server);
      if (baritone != null) {
         try {
            boolean ok = new BaritoneCommandManager(baritone).execute(source, BaritoneCommandManager.expand(rawCommand));
            if (!ok) {
               source.sendFailure(Component.translatable("message.playerengine.commands.unknown_command"));
               return 0;
            }
            return 1;
         } catch (CommandException e) {
            throw BARITONE_COMMAND_FAILED_EXCEPTION.create(e.handle());
         }
      }
      String cmd = rawCommand.trim();
      if (cmd.isEmpty() || cmd.equalsIgnoreCase("help") || cmd.equals("?")) {
         sendConsoleBaritoneFallbackHelp(source);
         return 1;
      }
      if (cmd.equalsIgnoreCase("version")) {
         source.sendSuccess(() -> Component.translatable("message.playerengine.commands.version_no_npc"), false);
         return 1;
      }
      source.sendFailure(Component.translatable("message.playerengine.commands.no_automaton_npc"));
      return 0;
   }

   private static void sendConsoleBaritoneFallbackHelp(CommandSourceStack source) {
      source.sendSuccess(() -> Component.translatable("message.playerengine.commands.console_help_header"), false);
      source.sendSuccess(() -> Component.translatable("message.playerengine.commands.console_help_admin"), false);
      source.sendSuccess(() -> Component.translatable("message.playerengine.commands.console_help_budget"), false);
      source.sendSuccess(() -> Component.translatable("message.playerengine.commands.console_help_chain"), false);
      source.sendSuccess(() -> Component.translatable("message.playerengine.commands.console_help_ingame"), false);
   }

   private static int runCommand(CommandSourceStack source, Entity target, String command) throws CommandSyntaxException {
      if (!(target instanceof LivingEntity)) {
         throw EntityArgument.NO_ENTITIES_FOUND.create();
      } else {
         try {
            for (LivingEntity entity : target.level().getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(100.0, 100.0, 100.0), e -> true)) {
               if (entity instanceof IAutomatone) {
                  runCommand(source, command, BaritoneAPI.getProvider().getBaritone(entity));
               }
            }

            return 1;
         } catch (CommandException var6) {
            throw BARITONE_COMMAND_FAILED_EXCEPTION.create(var6.handle());
         }
      }
   }
}
