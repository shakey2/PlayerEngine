package com.player2.playerengine.player2api;

import com.player2.playerengine.player2api.config.KeepInventoryOverride;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;

/**
 * Stateless resolver for the effective bot keepInventory decision, combining the server-sided
 * {@link KeepInventoryOverride} config with the caller-supplied world {@code keepInventory}
 * gamerule value. Deliberately has no per-player parameter — the feature is server-authoritative
 * only (see {@code masterplan/bot-keepinventory-gamerule-plan.md}).
 */
public final class KeepInventoryResolver {
    private KeepInventoryResolver() {}

    /** effectiveKeep = (override == FOLLOW_GAMERULE) ? worldGameruleKeep : (override == FORCE_KEEP). */
    public static boolean effectiveKeep(boolean worldGameruleKeep) {
        KeepInventoryOverride ov = Player2ServerConfigHolder.get().getBotKeepInventoryOverride();
        return switch (ov) {
            case FORCE_KEEP -> true;
            case FORCE_DROP -> false;
            case FOLLOW_GAMERULE -> worldGameruleKeep;
        };
    }
}
