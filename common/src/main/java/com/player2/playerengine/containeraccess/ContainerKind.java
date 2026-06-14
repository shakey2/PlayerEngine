package com.player2.playerengine.containeraccess;

import java.util.Locale;

/**
 * Classification of a resolved storage container (Part C4.5).
 *
 * <p>The lowercase {@link #token()} is the {@code kindToken} of the frozen scan-header grammar
 * ({@code chest | double_chest | trapped_chest | double_trapped_chest | barrel | shulker_box |
 * generic}) and must never change without revising every AI-visible format in
 * {@link ScanReportFormatter}.
 */
public enum ContainerKind {
    CHEST,
    DOUBLE_CHEST,
    TRAPPED_CHEST,
    DOUBLE_TRAPPED_CHEST,
    BARREL,
    SHULKER_BOX,
    /** Any other vanilla/modded {@code Container} block entity (furnace, hopper, ...): passed
     * through generically — no animation, no dedicated handling in C4.5. */
    GENERIC;

    /** The lowercase machine token used verbatim in scan headers and log records. */
    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** True for the four chest kinds (lid animation via block event id 1 + chest sounds). */
    public boolean isChest() {
        return this == CHEST || this == DOUBLE_CHEST || this == TRAPPED_CHEST || this == DOUBLE_TRAPPED_CHEST;
    }

    /** True when the container spans two block positions (54-slot CompoundContainer view). */
    public boolean isDouble() {
        return this == DOUBLE_CHEST || this == DOUBLE_TRAPPED_CHEST;
    }
}
