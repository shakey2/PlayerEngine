package com.player2.playerengine.containeraccess;

import java.util.Locale;

/**
 * The three token-frugal read-only scan modes of {@code scan_storage} (Part C4.5).
 *
 * <ul>
 *   <li>{@link #LIGHT} — aggregate per-item totals + empty-slot count (cheapest; the C5
 *       ingestion input).</li>
 *   <li>{@link #DEEP} — per-slot listing with compressed empty ranges (the C5 drift-detection
 *       input and the prep for slot-precise transactions).</li>
 *   <li>{@link #TARGETED} — live counts for specific requested items only (have/want).</li>
 * </ul>
 */
public enum ScanMode {
    LIGHT,
    DEEP,
    TARGETED;

    /** The lowercase mode token of the command grammar and the scan-header format. */
    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a command mode token ({@code light | deep | targeted}, case-insensitive).
     * Returns {@code null} for anything else — the caller maps that to
     * {@code invalid_argument: mode must be light, deep or targeted} via
     * {@code finishWithError} (never a {@code CommandException}).
     */
    public static ScanMode fromToken(String token) {
        if (token == null) {
            return null;
        }
        switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "light":
                return LIGHT;
            case "deep":
                return DEEP;
            case "targeted":
                return TARGETED;
            default:
                return null;
        }
    }
}
