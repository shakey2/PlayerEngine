package com.player2.playerengine.player2api;

/**
 * Effective, fully-resolved bot lifecycle settings for an owner.
 *
 * <p>Both values are concrete booleans: {@code botPermadeath} has already had the Model-A hardcore
 * fallback applied by {@link BotLifecycleSettingsResolver} (the nullable tri-state lives upstream in
 * the config/snapshot, not here).
 */
public record BotLifecycleSettings(boolean autoRespawn, boolean botPermadeath) {}
