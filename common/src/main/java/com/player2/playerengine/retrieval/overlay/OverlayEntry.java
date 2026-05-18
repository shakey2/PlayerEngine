package com.player2.playerengine.retrieval.overlay;

import java.util.Collections;
import java.util.List;

/**
 * Per-tool additive patch inside a {@link ToolOverlay}.
 *
 * <p>Only {@code addKeywords} and {@code addExamples} are supported — existing
 * {@link com.player2.playerengine.retrieval.ToolDocument} fields cannot be
 * replaced or deleted via an overlay.
 *
 * <p>Either list may be {@code null} in the JSON (treated as empty).
 */
public final class OverlayEntry {

    /** Additional keywords to merge into the tool's keyword list. May be {@code null}. */
    public List<String> addKeywords;

    /** Additional usage examples to merge into the tool's example list. May be {@code null}. */
    public List<String> addExamples;

    public OverlayEntry() {}

    /** Null-safe accessor that always returns an unmodifiable list. */
    public List<String> safeKeywords() {
        return addKeywords == null ? Collections.emptyList()
                                  : Collections.unmodifiableList(addKeywords);
    }

    /** Null-safe accessor that always returns an unmodifiable list. */
    public List<String> safeExamples() {
        return addExamples == null ? Collections.emptyList()
                                   : Collections.unmodifiableList(addExamples);
    }
}
