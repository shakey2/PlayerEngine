package com.player2.playerengine.player2api;

import com.player2.playerengine.executor.BudgetFallbackBehavior;
import com.player2.playerengine.executor.BudgetTracker;
import com.player2.playerengine.player2api.config.BudgetThresholds;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.config.PlayerBudgetConfig;
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
        if (target.useServerFile()) {
            var c = Player2ServerConfigHolder.get();
            c.setBudgetWindowMinutes(minutes);
            Player2ServerConfigHolder.validateAndFix(c);
            Player2ServerConfigHolder.save();
            BudgetTracker.resetAll();
            sendSaved(source, "budgetWindowMinutes=" + minutes + " (windows reset)", SERVER_FILE);
        } else {
            PlayerBudgetConfig cfg = PlayerBudgetConfigHolder.load(target.server(), target.playerUuid());
            cfg.setBudgetWindowMinutes(minutes);
            PlayerBudgetConfigHolder.save(target.server(), target.playerUuid(), cfg);
            BudgetTracker.resetAll();
            sendSaved(source, "budgetWindowMinutes=" + minutes + " (windows reset)", PLAYER_FILE);
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
        if (target.useServerFile()) {
            var c = Player2ServerConfigHolder.get();
            c.setJoulesRefreshIntervalSeconds(seconds);
            Player2ServerConfigHolder.validateAndFix(c);
            Player2ServerConfigHolder.save();
            sendSaved(source, "joulesRefreshIntervalSeconds=" + seconds, SERVER_FILE);
        } else {
            PlayerBudgetConfig cfg = PlayerBudgetConfigHolder.load(target.server(), target.playerUuid());
            cfg.setJoulesRefreshIntervalSeconds(seconds);
            PlayerBudgetConfigHolder.save(target.server(), target.playerUuid(), cfg);
            sendSaved(source, "joulesRefreshIntervalSeconds=" + seconds, PLAYER_FILE);
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
        sendSaved(source, "fallbackProfile=" + display + " (profile cache cleared)", SERVER_FILE);
        return 1;
    }

    public static int setFallbackBehaviorSwitch(CommandSourceStack source) {
        var c = Player2ServerConfigHolder.get();
        c.setBudgetFallbackBehavior(BudgetFallbackBehavior.SWITCH_PROFILE);
        Player2ServerConfigHolder.validateAndFix(c);
        Player2ServerConfigHolder.save();
        sendSaved(source, "budgetFallbackBehavior=SWITCH_PROFILE", SERVER_FILE);
        return 1;
    }

    public static int setFallbackBehaviorStop(CommandSourceStack source) {
        var c = Player2ServerConfigHolder.get();
        c.setBudgetFallbackBehavior(BudgetFallbackBehavior.HARD_STOP);
        Player2ServerConfigHolder.validateAndFix(c);
        Player2ServerConfigHolder.save();
        sendSaved(source, "budgetFallbackBehavior=HARD_STOP", SERVER_FILE);
        return 1;
    }

    public static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal("No server."));
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

        source.sendSuccess(() -> Component.literal("=== Budget Status ==="), false);
        String fileNote = target.useServerFile() ? SERVER_FILE : PLAYER_FILE;
        source.sendSuccess(() -> Component.literal("Thresholds from: " + fileNote), false);
        source.sendSuccess(() -> Component.literal(
                "Call limits: soft=" + thresholds.getSoftBudgetCallsPerWindow()
                        + " hard=" + thresholds.getHardBudgetCallsPerWindow()
                        + " window=" + thresholds.getBudgetWindowMinutes() + "min"), false);
        source.sendSuccess(() -> Component.literal(
                "Joules thresholds: soft=" + thresholds.getSoftJoulesThreshold()
                        + " hard=" + thresholds.getHardJoulesThreshold()
                        + " refresh=" + thresholds.getJoulesRefreshIntervalSeconds() + "s"), false);
        source.sendSuccess(() -> Component.literal(
                "Fallback (server-wide): profile=" + serverCfg.getFallbackProfile()
                        + " behavior=" + serverCfg.getBudgetFallbackBehavior()), false);

        var windows = BudgetTracker.statusSnapshot(serverCfg);
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
        String label = switch (field) {
            case SOFT_CALLS -> "softBudgetCallsPerWindow=" + value + " (0=disabled)";
            case HARD_CALLS -> "hardBudgetCallsPerWindow=" + value + " (0=disabled)";
            case SOFT_JOULES -> "softJoulesThreshold=" + value + " (0=disabled)";
            case HARD_JOULES -> "hardJoulesThreshold=" + value + " (0=disabled)";
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

    private static void sendSaved(CommandSourceStack source, String message, String fileLabel) {
        source.sendSuccess(() -> Component.literal(message + " (saved to " + fileLabel + ")."), false);
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
            return Target.failure(Component.literal(
                    "Run this budget command as a player on a dedicated server (per-player player-budget.json)."));
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
