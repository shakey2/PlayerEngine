package com.player2.playerengine.retrieval.overlay;

import java.util.Collections;
import java.util.Map;

/**
 * Root object for a {@code tool_overrides.json} overlay file.
 *
 * <p>Schema version 1 is the only supported version; files with any other
 * {@code schemaVersion} are rejected by {@link ToolOverlayLoader} with a log
 * entry (never throw).
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "tools": {
 *     "<toolId>": { "addKeywords": ["..."], "addExamples": ["..."] }
 *   }
 * }
 * }</pre>
 */
public final class ToolOverlay {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Must equal {@link #SUPPORTED_SCHEMA_VERSION}; any other value is rejected. */
    public int schemaVersion;

    /** Map of tool id → additive patch. May be {@code null} in JSON. */
    public Map<String, OverlayEntry> tools;

    public ToolOverlay() {}

    /** Null-safe accessor that always returns an unmodifiable map. */
    public Map<String, OverlayEntry> safeTools() {
        return tools == null ? Collections.emptyMap()
                             : Collections.unmodifiableMap(tools);
    }
}
