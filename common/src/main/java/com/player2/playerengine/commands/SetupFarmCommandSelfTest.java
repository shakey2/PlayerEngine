package com.player2.playerengine.commands;

import net.minecraft.core.BlockPos;

/** Pure location-alias contract checks for the direct setup_farm command. */
public final class SetupFarmCommandSelfTest {
    private SetupFarmCommandSelfTest() {
    }

    public static void runAll() {
        BlockPos botSurface = new BlockPos(1, 63, 2);
        BlockPos ownerSurface = new BlockPos(10, 70, -4);

        require(SetupFarmCommand.resolveLocationAlias("bot", botSurface, ownerSurface)
                        .equals(botSurface),
                "bot resolves to the bot surface anchor");
        require(SetupFarmCommand.resolveLocationAlias("HERE", botSurface, ownerSurface)
                        .equals(botSurface),
                "here is a case-insensitive bot alias");
        require(SetupFarmCommand.resolveLocationAlias("owner", botSurface, ownerSurface)
                        .equals(ownerSurface),
                "owner resolves to the owner surface anchor");
        require(SetupFarmCommand.resolveLocationAlias("player", botSurface, ownerSurface)
                        .equals(ownerSurface),
                "player is an owner alias");
        require(SetupFarmCommand.resolveLocationAlias("owner", botSurface, null) == null,
                "owner aliases reject a missing or unavailable owner");
        require(SetupFarmCommand.resolveLocationAlias("village", botSurface, ownerSurface) == null,
                "unknown aliases are rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
