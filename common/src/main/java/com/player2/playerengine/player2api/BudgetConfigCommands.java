package com.player2.playerengine.player2api;

import com.player2.playerengine.executor.BudgetFallbackBehavior;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.config.PlayerBudgetConfig;
import com.player2.playerengine.help.ArgNote;
import com.player2.playerengine.help.HelpEntry;
import com.player2.playerengine.help.HelpRegistry;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Read/write budget threshold fields for {@code /playerengine player2 budget …}.
 * Integrated singleplayer uses {@code server_player2.json}; dedicated PROMPTER_PAYS uses per-player files.
 */
public final class BudgetConfigCommands {

    private static final String SERVER_FILE = "server_player2.json";
    private static final String PLAYER_FILE = "player-budget.json";

    private BudgetConfigCommands() {}

    /**
     * Contributes a {@link HelpEntry} for every {@code /playerengine player2 budget …} leaf
     * (all OP-only, permission 2). Called from {@code DefaultCommands.register} where the budget
     * brigadier subtree is actually wired. Idempotent put-by-path. Keys and usage are plain String
     * literals for the Layer-1 lint; only human prose lives behind {@code help.playerengine.*} keys
     * (config tokens and values stay English).
     */
    public static void registerHelpEntries() {
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget soft", "player2 budget soft <value>",
                "help.playerengine.player2-budget-soft.short", null,
                List.of(new ArgNote("value", "help.playerengine.player2-budget-soft.arg.value")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget hard", "player2 budget hard <value>",
                "help.playerengine.player2-budget-hard.short", null,
                List.of(new ArgNote("value", "help.playerengine.player2-budget-hard.arg.value")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget window", "player2 budget window <minutes>",
                "help.playerengine.player2-budget-window.short", null,
                List.of(new ArgNote("minutes", "help.playerengine.player2-budget-window.arg.minutes")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget joules_soft",
                "player2 budget joules_soft <value>",
                "help.playerengine.player2-budget-joules_soft.short", null,
                List.of(new ArgNote("value", "help.playerengine.player2-budget-joules_soft.arg.value")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget joules_hard",
                "player2 budget joules_hard <value>",
                "help.playerengine.player2-budget-joules_hard.short", null,
                List.of(new ArgNote("value", "help.playerengine.player2-budget-joules_hard.arg.value")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget joules_refresh",
                "player2 budget joules_refresh <seconds>",
                "help.playerengine.player2-budget-joules_refresh.short", null,
                List.of(new ArgNote("seconds", "help.playerengine.player2-budget-joules_refresh.arg.seconds")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget fallback_profile",
                "player2 budget fallback_profile <name>",
                "help.playerengine.player2-budget-fallback-profile.short", null,
                List.of(new ArgNote("name", "help.playerengine.player2-budget-fallback-profile.arg.name")), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget fallback_behavior switch",
                "player2 budget fallback_behavior switch",
                "help.playerengine.player2-budget-fallback-behavior-switch.short", null,
                List.of(), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget fallback_behavior stop",
                "player2 budget fallback_behavior stop",
                "help.playerengine.player2-budget-fallback-behavior-stop.short", null,
                List.of(), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget reset", "player2 budget reset",
                "help.playerengine.player2-budget-reset.short", null,
                List.of(), 2, null, "budget"));
        HelpRegistry.register(new HelpEntry("playerengine", "player2 budget status", "player2 budget status",
                "help.playerengine.player2-budget-status.short", null,
                List.of(), 2, null, "budget"));
    }

    public static int setSoft(CommandSourceStack source, int value) {
        return setIntField(source, value, Field.SOFT_CALLS);
    }

    public static int setHard(CommandSourceStack source, int value) {
        return setIntField(source, value, Field.HARD_CALLS);
    }

    public static int setWindow(CommandSourceStack source, int minutes) {
        Target target = resolveTarget(source);
        if (target.failed()) {
            source.sendFailure(target.failure());
            return 0;
        }
        Component fieldLabel = Component.translatable("message.playerengine.budget.field.window_minutes", minutes);
        if (target.useServerFile()) {
            var c = Player2ServerConfigHolder.get();
            c.setBudgetWindowMinutes(minutes);
            Player2ServerConfigHolder.validateAndFix(c);
            Player2ServerConfigHolder.save();
            BudgetTracker.resetAll();
            sendSaved(source, fieldLabel, SERVER_FILE);
        } else {
            PlayerBudgetConfig cfg = PlayerBudgetConfigHolder.load(target.server(), target.playerUuid());
            cfg.setBudgetWindowMinutes(minutes);
            PlayerBudgetConfigHolder.save(target.server(), target.playerUuid(), cfg);
            BudgetTracker.resetAll();
            sendSaved(source, fieldLabel, PLAYER_FILE);
        }
        return 1;
    }

    public static int setJoulesSoft(CommandSourceStack source, int value) {
        return setIntField(source, value, Field.SOFT_JOULES);
    }

    public static int setJoulesHard(CommandSourceStack source, int value) {
        return setIntField(source, value, Field.HARD_JOULES);
    }

    public static int setJoulesRefresh(CommandSourceStack source, int seconds) {
        Target target = resolveTarget(source);
        if (target.failed()) {
            source.sendFailure(target.failure());
            return 0;
        }
        Component fieldLabel = Component.translatable("message.playerengine.budget.field.joules_refresh", seconds);
        if (target.useServerFile()) {
            var c = Player2ServerConfigHolder.get();
            c.setJoulesRefreshIntervalSeconds(seconds);
            Player2ServerConfigHolder.validateAndFix(c);
            Player2ServerConfigHolder.save();
            sendSaved(source, fieldLabel, SERVER_FILE);
        } else {
            PlayerBudgetConfig cfg = PlayerBudgetConfigHolder.load(target.server(), target.playerUuid());
            cfg.setJoulesRefreshIntervalSeconds(seconds);
            PlayerBudgetConfigHolder.save(target.server(), target.playerUuid(), cfg);
            sendSaved(source, fieldLabel, PLAYER_FILE);
        }
        return 1;
    }

    public static int setFallbackProfile(CommandSourceStack source, String profileName) {
        var c = Player2ServerConfigHolder.get();
        c.setFallbackProfile(profileName);
        Player2ServerConfigHolder.validateAndFix(c);
        Player2ServerConfigHolder.save();
        ProfileUrlResolver.invalidateCache();
        String display = profileName == null ? "none" : profileName;
        Component fieldLabel = Component.translatable("message.playerengine.budget.field.fallback_profile", display);
        sendSaved(source, fieldLabel, SERVER_FILE);
        return 1;
    }

    public static int setFallbackBehaviorSwitch(CommandSourceStack source) {
        var c = Player2ServerConfigHolder.get();
        c.setBudgetFallbackBehavior(BudgetFallbackBehavior.SWITCH_PROFILE);
        Player2ServerConfigHolder.validateAndFix(c);
        Player2ServerConfigHolder.save();
        sendSaved(source, Component.translatable("message.playerengine.budget.field.fallback_behavior_switch"), SERVER_FILE);
        return 1;
    }

    public static int setFallbackBehaviorStop(CommandSourceStack source) {
        var c = Player2ServerConfigHolder.get();
        c.setBudgetFallbackBehavior(BudgetFallbackBehavior.HARD_STOP);
        Player2ServerConfigHolder.validateAndFix(c);
        Player2ServerConfigHolder.save();
        sendSaved(source, Component.translatable("message.playerengine.budget.field.fallback_behavior_stop"), SERVER_FILE);
        return 1;
    }

    public static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("message.playerengine.budget.error.no_server"));
            return 0;
        }
        Target target = resolveTarget(source);
        if (target.failed()) {
            source.sendFailure(target.failure());
            return 0;
        }
        BudgetThresholds thresholds = target.useServerFile()
                ? Player2ServerConfigHolder.get()
                : PlayerBudgetConfigHolder.load(server, target.playerUuid());
        Player2ServerRuntimeConfig serverCfg = Player2ServerConfigHolder.get();

        source.sendSuccess(() -> Component.translatable("message.playerengine.budget.status.header"), false);
        String fileNote = target.useServerFile() ? SERVER_FILE : PLAYER_FILE;
        source.sendSuccess(() -> Component.translatable("message.playerengine.budget.status.thresholds_from", fileNote), false);
        source.sendSuccess(() -> Component.translatable(
                "message.playerengine.budget.status.call_limits",
                thresholds.getSoftBudgetCallsPerWindow(),
                thresholds.getHardBudgetCallsPerWindow(),
                thresholds.getBudgetWindowMinutes()), false);
        source.sendSuccess(() -> Component.translatable(
                "message.playerengine.budget.status.joules_thresholds",
                thresholds.getSoftJoulesThreshold(),
                thresholds.getHardJoulesThreshold(),
                thresholds.getJoulesRefreshIntervalSeconds()), false);
        source.sendSuccess(() -> Component.translatable(
                "message.playerengine.budget.status.fallback",
                serverCfg.getFallbackProfile(),
                serverCfg.getBudgetFallbackBehavior()), false);

        var windows = BudgetTracker.statusSnapshot(serverCfg);
        if (windows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.playerengine.budget.status.no_call_windows"), false);
        } else {
            long now = System.currentTimeMillis();
            for (var entry : windows.entrySet()) {
                var snap = entry.getValue();
                long resets = Math.max(0, snap.windowEndMs() - now) / 1000L;
                source.sendSuccess(() -> Component.translatable(
                        "message.playerengine.budget.status.call_window_entry",
                        entry.getKey(),
                        snap.callCount(),
                        resets), false);
            }
        }
        var joulesSnaps = JoulesCache.statusSnapshot();
        if (joulesSnaps.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.playerengine.budget.status.no_joules_data"), false);
        } else {
            for (var entry : joulesSnaps.entrySet()) {
                var snap = entry.getValue();
                long ageS = (System.currentTimeMillis() - snap.refreshedAtMs) / 1000L;
                Component patronDisplay = snap.patronTier.isEmpty()
                        ? Component.translatable("message.playerengine.budget.status.patron_none")
                        : Component.literal(snap.patronTier);
                source.sendSuccess(() -> Component.translatable(
                        "message.playerengine.budget.status.joules_entry",
                        entry.getKey(),
                        snap.joulesDisplay(),
                        patronDisplay,
                        ageS), false);
            }
        }
        return 1;
    }

    private enum Field {
        SOFT_CALLS, HARD_CALLS, SOFT_JOULES, HARD_JOULES
    }

    private static int setIntField(CommandSourceStack source, int value, Field field) {
        Target target = resolveTarget(source);
        if (target.failed()) {
            source.sendFailure(target.failure());
            return 0;
        }
        Component label = switch (field) {
            case SOFT_CALLS -> Component.translatable("message.playerengine.budget.field.soft_calls", value);
            case HARD_CALLS -> Component.translatable("message.playerengine.budget.field.hard_calls", value);
            case SOFT_JOULES -> Component.translatable("message.playerengine.budget.field.soft_joules", value);
            case HARD_JOULES -> Component.translatable("message.playerengine.budget.field.hard_joules", value);
        };
        if (target.useServerFile()) {
            var c = Player2ServerConfigHolder.get();
            applyField(c, field, value);
            Player2ServerConfigHolder.validateAndFix(c);
            Player2ServerConfigHolder.save();
            sendSaved(source, label, SERVER_FILE);
        } else {
            PlayerBudgetConfig cfg = PlayerBudgetConfigHolder.load(target.server(), target.playerUuid());
            applyField(cfg, field, value);
            PlayerBudgetConfigHolder.save(target.server(), target.playerUuid(), cfg);
            sendSaved(source, label, PLAYER_FILE);
        }
        return 1;
    }

    private static void applyField(Player2ServerRuntimeConfig c, Field field, int value) {
        switch (field) {
            case SOFT_CALLS -> c.setSoftBudgetCallsPerWindow(value);
            case HARD_CALLS -> c.setHardBudgetCallsPerWindow(value);
            case SOFT_JOULES -> c.setSoftJoulesThreshold(value);
            case HARD_JOULES -> c.setHardJoulesThreshold(value);
        }
    }

    private static void applyField(PlayerBudgetConfig cfg, Field field, int value) {
        switch (field) {
            case SOFT_CALLS -> cfg.setSoftBudgetCallsPerWindow(value);
            case HARD_CALLS -> cfg.setHardBudgetCallsPerWindow(value);
            case SOFT_JOULES -> cfg.setSoftJoulesThreshold(value);
            case HARD_JOULES -> cfg.setHardJoulesThreshold(value);
        }
    }

    private static void sendSaved(CommandSourceStack source, Component fieldLabel, String fileLabel) {
        source.sendSuccess(() -> Component.translatable("message.playerengine.budget.saved", fieldLabel, fileLabel), false);
    }

    private static Target resolveTarget(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean serverFile = BudgetThresholdsResolver.usesServerBudgetStore(server);
        UUID uuid = null;
        try {
            ServerPlayer player = source.getPlayerOrException();
            uuid = player.getUUID();
        } catch (Exception ignored) {
        }
        if (!serverFile && uuid == null) {
            return Target.failure(Component.translatable("message.playerengine.budget.error.player_only"));
        }
        return new Target(server, uuid, serverFile, null);
    }

    private record Target(MinecraftServer server, UUID playerUuid, boolean useServerFile, Component failure) {
        boolean failed() {
            return failure != null;
        }

        static Target failure(Component message) {
            return new Target(null, null, false, message);
        }
    }
}
