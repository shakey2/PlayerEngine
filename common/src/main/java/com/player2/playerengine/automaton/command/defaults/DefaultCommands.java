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
import com.player2.playerengine.executor.BudgetFallbackBehavior;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
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
               MutableComponent component = Component.literal(String.format("> %s", toDisplay));
               component.setStyle(
                  component.getStyle()
                     .applyFormat(ChatFormatting.WHITE)
                     .withHoverEvent(new HoverEvent(Action.SHOW_TEXT, Component.literal("Click to rerun command")))
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
         source.sendSuccess(() -> Component.literal("daniel"), false);
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
               ctx.getSource().sendSuccess(() -> Component.literal("Reloaded Player2 server config (server files)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server payer mode: PROMPTER_PAYS (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server payer mode: OWNER_PAYS_ALL (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server dedicatedClientProxy=" + v + " (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server ownerOfflineServerContinuation=" + v + " (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server callByNameChat=" + v + " (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server maxSpawnedCompanionsPerPlayer=" + v + " (server config saved)."), false);
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
                     ctx.getSource().sendSuccess(() -> Component.literal("Server maxStoredCharacterIdsPerPlayer=" + v + " (0=unlimited; server config saved)."), false);
                     return 1;
                  })))
            .then(Commands.literal("budget")
                  .then(Commands.literal("soft")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "value");
                           var c = Player2ServerConfigHolder.get();
                           c.setSoftBudgetCallsPerWindow(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("softBudgetCallsPerWindow=" + v + " (0=disabled; saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("hard")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "value");
                           var c = Player2ServerConfigHolder.get();
                           c.setHardBudgetCallsPerWindow(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("hardBudgetCallsPerWindow=" + v + " (0=disabled; saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("window")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "minutes");
                           var c = Player2ServerConfigHolder.get();
                           c.setBudgetWindowMinutes(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           BudgetTracker.resetAll();
                           ctx.getSource().sendSuccess(() -> Component.literal("budgetWindowMinutes=" + v + " (saved; windows reset)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("joules_soft")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "value");
                           var c = Player2ServerConfigHolder.get();
                           c.setSoftJoulesThreshold(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("softJoulesThreshold=" + v + " (0=disabled; saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("joules_hard")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "value");
                           var c = Player2ServerConfigHolder.get();
                           c.setHardJoulesThreshold(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("hardJoulesThreshold=" + v + " (0=disabled; saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("joules_refresh")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(60, 86400)).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           int v = IntegerArgumentType.getInteger(ctx, "seconds");
                           var c = Player2ServerConfigHolder.get();
                           c.setJoulesRefreshIntervalSeconds(v);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("joulesRefreshIntervalSeconds=" + v + " (saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("fallback_profile")
                        .then(Commands.argument("name", StringArgumentType.string()).executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           String raw = StringArgumentType.getString(ctx, "name");
                           String profileName = raw.equalsIgnoreCase("none") ? null : raw;
                           var c = Player2ServerConfigHolder.get();
                           c.setFallbackProfile(profileName);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ProfileUrlResolver.invalidateCache();
                           String display = profileName == null ? "none" : profileName;
                           ctx.getSource().sendSuccess(() -> Component.literal("fallbackProfile=" + display + " (saved; profile cache cleared)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("fallback_behavior")
                        .then(Commands.literal("switch").executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           var c = Player2ServerConfigHolder.get();
                           c.setBudgetFallbackBehavior(BudgetFallbackBehavior.SWITCH_PROFILE);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("budgetFallbackBehavior=SWITCH_PROFILE (saved)."), false);
                           return 1;
                        }))
                        .then(Commands.literal("stop").executes(ctx -> {
                           if (!isLogicalServer(ctx.getSource())) return 0;
                           var c = Player2ServerConfigHolder.get();
                           c.setBudgetFallbackBehavior(BudgetFallbackBehavior.HARD_STOP);
                           Player2ServerConfigHolder.validateAndFix(c);
                           Player2ServerConfigHolder.save();
                           ctx.getSource().sendSuccess(() -> Component.literal("budgetFallbackBehavior=HARD_STOP (saved)."), false);
                           return 1;
                        })))
                  .then(Commands.literal("reset").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     BudgetTracker.resetAll();
                     JoulesCache.invalidateAll();
                     ProfileUrlResolver.invalidateCache();
                     ctx.getSource().sendSuccess(() -> Component.literal("Budget windows, Joules cache, and profile cache cleared."), false);
                     return 1;
                  }))
                  .then(Commands.literal("status").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     var config = Player2ServerConfigHolder.get();
                     var source = ctx.getSource();
                     source.sendSuccess(() -> Component.literal("=== Budget Status ==="), false);
                     source.sendSuccess(() -> Component.literal(
                             "Call limits: soft=" + config.getSoftBudgetCallsPerWindow()
                                     + " hard=" + config.getHardBudgetCallsPerWindow()
                                     + " window=" + config.getBudgetWindowMinutes() + "min"), false);
                     source.sendSuccess(() -> Component.literal(
                             "Joules thresholds: soft=" + config.getSoftJoulesThreshold()
                                     + " hard=" + config.getHardJoulesThreshold()
                                     + " refresh=" + config.getJoulesRefreshIntervalSeconds() + "s"), false);
                     source.sendSuccess(() -> Component.literal(
                             "Fallback: profile=" + config.getFallbackProfile()
                                     + " behavior=" + config.getBudgetFallbackBehavior()), false);
                     // Per-billing-key call windows
                     var windows = BudgetTracker.statusSnapshot(config);
                     if (windows.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("No active call windows."), false);
                     } else {
                        long now = System.currentTimeMillis();
                        for (var entry : windows.entrySet()) {
                           var snap = entry.getValue();
                           long resets = Math.max(0, snap.windowEndMs() - now) / 1000L;
                           source.sendSuccess(() -> Component.literal(
                                   "  " + entry.getKey() + ": calls=" + snap.callCount()
                                           + " resets_in=" + resets + "s"), false);
                        }
                     }
                     // Per-billing-key Joules snapshots
                     var joulesSnaps = JoulesCache.statusSnapshot();
                     if (joulesSnaps.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("No cached Joules data."), false);
                     } else {
                        for (var entry : joulesSnaps.entrySet()) {
                           var snap = entry.getValue();
                           long ageS = (System.currentTimeMillis() - snap.refreshedAtMs) / 1000L;
                           source.sendSuccess(() -> Component.literal(
                                   "  " + entry.getKey() + ": joules=" + snap.joulesDisplay()
                                           + " patron=" + (snap.patronTier.isEmpty() ? "none" : snap.patronTier)
                                           + " age=" + ageS + "s"), false);
                        }
                     }
                     return 1;
                  })))
            .then(Commands.literal("chain")
                  .then(Commands.literal("status").executes(ctx -> {
                     if (!isLogicalServer(ctx.getSource())) return 0;
                     var source = ctx.getSource();
                     var controllers = PlayerEngineController.staticControllers;
                     if (controllers.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("No active PlayerEngine bots."), false);
                        return 1;
                     }
                     for (var entry : controllers.entrySet()) {
                        PlayerEngineController bot = entry.getValue();
                        String botName = bot.getEntity().getName().getString();
                        source.sendSuccess(() -> Component.literal("=== Chain Status: " + botName + " ==="), false);
                        var execOpt = bot.getStepExecutorAdapter().getActiveExecution();
                        if (execOpt.isEmpty()) {
                           source.sendSuccess(() -> Component.literal("No step has run yet."), false);
                           continue;
                        }
                        StepExecution exec = execOpt.get();
                        if (exec.getState() == StepState.RUNNING) {
                           long secs = exec.getElapsedMs() / 1000L;
                           source.sendSuccess(() -> Component.literal(
                                   "Active step:    " + exec.getStepKind() + " [RUNNING] (" + secs + "s)"), false);
                           String last = exec.getLastLogEntry();
                           source.sendSuccess(() -> Component.literal("Last log entry: " + last), false);
                        } else {
                           source.sendSuccess(() -> Component.literal(
                                   "Last step: " + exec.getStepKind() + " [" + exec.getState() + "]"), false);
                        }
                        source.sendSuccess(() -> Component.literal("--- Full log ---"), false);
                        for (String logEntry : exec.getLog()) {
                           source.sendSuccess(() -> Component.literal("  " + logEntry), false);
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
         source.sendFailure(Component.literal(
               "Player2 admin commands apply to the server's playerengine/ files and only run on the logical server."));
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
         source.sendFailure(Component.literal("No server."));
         return 0;
      }
      Baritone baritone = findAnyBaritone(server);
      if (baritone != null) {
         try {
            boolean ok = new BaritoneCommandManager(baritone).execute(source, BaritoneCommandManager.expand(rawCommand));
            if (!ok) {
               source.sendFailure(Component.literal("Unknown command."));
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
         source.sendSuccess(() -> Component.literal("[PlayerEngine] Automatone (spawn an Automaton NPC for full version/help)."), false);
         return 1;
      }
      source.sendFailure(Component.literal(
            "No Automaton NPC in any loaded dimension — Baritone commands need an in-world executor. "
                  + "Use /playerengine player2 ... for server billing/proxy settings."));
      return 0;
   }

   private static void sendConsoleBaritoneFallbackHelp(CommandSourceStack source) {
      source.sendSuccess(() -> Component.literal("=== PlayerEngine (no in-world Automaton) ==="), false);
      source.sendSuccess(() -> Component.literal(
            "Server admin: /playerengine player2 reload | payer prompter|owner | dedicated <true|false> | owner_offline_continue <true|false> | call_by_name <true|false>"), false);
      source.sendSuccess(() -> Component.literal(
            "Budget: /playerengine player2 budget soft|hard|window|joules_soft|joules_hard|joules_refresh|fallback_profile|fallback_behavior|reset|status"), false);
      source.sendSuccess(() -> Component.literal(
            "Chain diagnostics: /playerengine player2 chain status"), false);
      source.sendSuccess(() -> Component.literal("In-game (as a player): /playerengine help — or load an Automaton for full console Baritone routing."), false);
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
