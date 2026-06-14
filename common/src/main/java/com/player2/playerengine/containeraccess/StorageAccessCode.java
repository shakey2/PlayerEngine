package com.player2.playerengine.containeraccess;

import java.util.Locale;

/**
 * The typed error vocabulary for the Part C4.5 storage scan/transaction paths.
 *
 * <p>Each code carries a machine token (lowercase, e.g. {@code "insufficient_items"}) used
 * verbatim in model feedback and logs; human detail text is appended after {@code ": "}.
 * All failure paths in the {@code containeraccess} layer return a code + detail — they never
 * throw to the caller and never raise {@code CommandException} (Decision 11: name/count errors
 * must reach the model through {@code finishWithError}, which only runs once {@code call()} has
 * started).
 *
 * <p>{@link #CONTAINER_CHANGED} is special: it is a {@code finishWithNote} <em>degradation</em>
 * token, never a {@code finishWithError} code — by the time it fires, something already moved
 * (validation passed and a concurrent mutation shortened the transfer; Decision 4).
 */
public enum StorageAccessCode {
    OK,
    /** Malformed command args (bad mode token, targeted scan without items, 3+-part entry). */
    INVALID_ARGUMENT,
    /** Item string did not resolve to a registered item (includes the deliberate AIR case). */
    INVALID_ITEM_NAME,
    /** Count {@code <= 0}, non-numeric, or above {@link StorageItemArgs#MAX_COUNT}. */
    INVALID_COUNT,
    /** Slot outside {@code 0..totalSlots-1} (container) or {@code 0..35} (bot main inventory). */
    INVALID_SLOT,
    /** Slot-precise withdraw from an empty slot. */
    SLOT_EMPTY,
    /** Slot-precise deposit into a slot holding a different item / already full. */
    SLOT_OCCUPIED,
    /** Explicit count > available in container (withdraw) or bot inventory (deposit). */
    INSUFFICIENT_ITEMS,
    /** Bot inventory cannot hold the withdrawal / container cannot fit the deposit. */
    INSUFFICIENT_SPACE,
    /** WorldlyContainer rejection (e.g. shulker box inside shulker box). */
    ITEM_NOT_ALLOWED,
    /** Chunk loaded but no supported container block entity at pos. */
    CONTAINER_MISSING,
    /** Pre-navigation distance check failed (beyond
     * {@link ContainerResolver#STORAGE_TRAVEL_CAP_BLOCKS}); the bot never moves. */
    CONTAINER_TOO_FAR,
    /** Navigation timed out, or (defensive) chunk not loaded at resolve time. */
    CONTAINER_UNREACHABLE,
    /** Unsupported container category, e.g. ender chest (per-player storage). */
    CONTAINER_UNSUPPORTED,
    /** Degradation token: contents changed between validation and transfer (a genuine
     * concurrent mutation race). Reported via {@code finishWithNote}, never an error. */
    CONTAINER_CHANGED;

    /** The lowercase machine token used verbatim in model feedback and log records. */
    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
