package com.player2.playerengine.player2api.config;

/**
 * Server-sided override for bot keepInventory behavior on death.
 *
 * <p>{@link #FOLLOW_GAMERULE} is the default and required "OFF" state: bots obey the world's
 * {@code keepInventory} gamerule exactly as vanilla players do. {@link #FORCE_KEEP} and
 * {@link #FORCE_DROP} override the gamerule for all bots on the server/world regardless of its
 * value. See {@code KeepInventoryResolver} for the resolution logic.
 */
public enum KeepInventoryOverride {
    FOLLOW_GAMERULE, // default "OFF": obey the world keepInventory gamerule
    FORCE_KEEP,      // all bots keep inventory regardless of gamerule
    FORCE_DROP       // all bots drop inventory regardless of gamerule
}
